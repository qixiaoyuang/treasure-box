package com.ayou.tiaoyitiao;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 跳广告：监听窗口变化，自动点击开屏广告等界面上的"跳过"按钮。
 * 只点击应用自己提供的"跳过"控件，不做其他操作。
 * 需要用户在系统「无障碍」设置中手动开启本服务。
 */
public class SkipAdService extends AccessibilityService {
    private static final long CLICK_COOLDOWN_MS = 800;
    private static final int MAX_RETRY = 6;
    private static final long RETRY_WINDOW_MS = 6000;
    private static final long LAUNCH_GAP_MS = 30000;   // away this long => next window change is a (re)launch
    private static final long SPLASH_WINDOW_MS = 10000; // heuristic taps only right after launch
    private static final long BLIND_WINDOW_MS = 4000; // blind taps only while the splash is up
    /** A freshly launched splash screen is simple; a main screen is not. */
    private static final int SPLASH_NODE_LIMIT = 30;
    private static final long HEURISTIC_COOLDOWN_MS = 15000;
    private long lastClickMs = 0;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int retryCount = 0;
    private long retryUntilMs = 0;
    private String retryPkg = null;
    private String lastNoSkipPkg = null;
    private long lastNoSkipMs = 0;
    // per-package timing for launch detection + heuristic throttling
    private final java.util.Map<String, Long> launchMs = new java.util.HashMap<>();
    private final java.util.Map<String, Long> lastSeenMs = new java.util.HashMap<>();
    private final java.util.Map<String, Long> lastHeuristicMs = new java.util.HashMap<>();
    private final java.util.Map<String, String> lastActivity = new java.util.HashMap<>();
    // Log-confirmed fake-success apps: ACTION_CLICK reports success but the ad
    // stays (tv.danmaku.bili: clicked "跳过 5" three times, button kept coming
    // back; com.ct.client: same fake-success history; com.sohu.sohuVideo:
    // "跳过" click faked, then an untappable "跳过 N" countdown). Always go
    // straight to a real gesture tap for these.
    private static final java.util.Set<String> GESTURE_FIRST_PKGS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "tv.danmaku.bili",
                    "com.ct.client",
                    "com.sohu.sohuVideo"));
    // The "few nodes" blind-tap heuristic is for third-party splash screens
    // only: never fire it inside system surfaces (it once tapped while the
    // user was in Settings enabling this very service).
    private static final java.util.Set<String> NO_HEURISTIC_PKGS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "com.android.settings",
                    "com.android.systemui",
                    "com.miui.home",
                    "com.miui.personalassistant",
                    "com.android.packageinstaller",
                    "com.miui.securitycenter"));
    // Last time we clicked a skip button per package: a button that reappears
    // shortly after our click proves the click was fake.
    private final java.util.Map<String, Long> lastClickPkgMs = new java.util.HashMap<>();
    private static final long REDISCOVERY_MS = 30_000;
    // Packages whose skip button recently fake-succeeded on ACTION_CLICK
    // (reported success but the ad stayed): skip the polite API and go
    // straight to a real gesture tap for a while.
    private final java.util.Map<String, Long> fakeClickMs = new java.util.HashMap<>();
    private static final long DISTRUST_MS = 60_000;
    // BACK-key escalation for countdown-type ads, throttled per package.
    private final java.util.Map<String, Long> lastBackMs = new java.util.HashMap<>();
    private static final long BACK_COOLDOWN_MS = 30_000;
    // BACK is counterproductive here (user-confirmed 2026-10-02): it exits
    // the whole app, and the splash ad just shows again on relaunch — worse
    // than waiting out the countdown. Bilbil's countdown ignores taps AND
    // survives BACK; leave it alone and let the 5s play out.
    private static final java.util.Set<String> NO_BACK_PKGS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "tv.danmaku.bili"));

    /** "跳过 5" / "Skip 3s" style countdown buttons. */
    private static boolean isCountdownButton(AccessibilityNodeInfo n) {
        try {
            CharSequence t = n.getText();
            if (t == null) t = n.getContentDescription();
            if (t == null) return false;
            String s = t.toString();
            return s.matches("(?i).*跳过\\s*\\d+.*") || s.matches("(?i).*skip\\s*\\d+.*");
        } catch (Exception ignored) {
            return false;
        }
    }
    /**
     * Apps whose splash skip button is known to be invisible to accessibility
     * (drawn as a bitmap / hidden from the tree). For these, the heuristic
     * tap does not require ad evidence; it fires from the delayed
     * launch check only, when the ad is most likely rendered.
     */
    private static final java.util.Set<String> KNOWN_INVISIBLE_SKIP =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "com.sina.weibo", "com.taobao.taobao", "com.xunmeng.pinduoduo",
                    "com.phoenix.read"));

    // ---- User-chosen "enhanced" packages ("应用加强" list) ----
    // Same aggressive treatment as KNOWN_INVISIBLE_SKIP: blind-tap the
    // top-right corner shortly after launch even without ad evidence.
    // Persisted in SharedPreferences so it survives restarts; mirrored in
    // a static set for the hot path (service and UI run in one process).
    private static final String ENHANCED_PREF = "skipad_enhanced_pkgs";
    private static volatile java.util.Set<String> sEnhanced =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static android.content.SharedPreferences enhancedPrefs(
            android.content.Context c) {
        return c.getApplicationContext().getSharedPreferences(
                ENHANCED_PREF, android.content.Context.MODE_PRIVATE);
    }

    /** All user-enhanced package names (copy). */
    public static java.util.Set<String> getEnhancedPkgs(android.content.Context c) {
        try {
            return new java.util.HashSet<>(enhancedPrefs(c).getStringSet(
                    "pkgs", java.util.Collections.<String>emptySet()));
        } catch (Exception e) {
            return new java.util.HashSet<String>();
        }
    }

    public static boolean isEnhanced(android.content.Context c, String pkg) {
        try {
            java.util.Set<String> s = enhancedPrefs(c).getStringSet("pkgs", null);
            return s != null && s.contains(pkg);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setEnhanced(android.content.Context c, String pkg, boolean on) {
        try {
            android.content.SharedPreferences p = enhancedPrefs(c);
            java.util.Set<String> s = new java.util.HashSet<>(p.getStringSet(
                    "pkgs", java.util.Collections.<String>emptySet()));
            if (on) s.add(pkg); else s.remove(pkg);
            p.edit().putStringSet("pkgs", s).apply();
            sEnhanced =
                    java.util.Collections.synchronizedSet(new java.util.HashSet<>(s));
        } catch (Exception ignored) { }
    }

    private static void loadEnhanced(android.content.Context c) {
        try {
            sEnhanced =
                    java.util.Collections.synchronizedSet(getEnhancedPkgs(c));
        } catch (Exception ignored) { }
    }

    // ---- On-device diagnostics: what the service saw / did ----
    // Kept in memory for speed AND appended to a file, because the log used
    // to live only in a static list: every process kill (e.g. MIUI/HyperOS
    // "clear background") wiped it and the page showed "暂无记录".
    private static final java.util.List<String> DIAG =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    private static final int DIAG_MAX = 200;
    private static android.content.Context sAppCtx;
    private static boolean sDiagLoaded;

    /** Give the static log a Context so it can persist (safe to call any time). */
    public static void attach(android.content.Context c) {
        if (c != null) sAppCtx = c.getApplicationContext();
    }

    // ---- Skip counters: today + all-time, shown under the status line ----
    private static final String STATS_PREF = "skipad_stats";
    // Taps on the same ad (fake-success rechecks, 补点) collapse into one.
    private static final long COUNT_DEDUP_MS = 60_000;
    private final java.util.Map<String, Long> lastCountedMs = new java.util.HashMap<>();

    private static android.content.SharedPreferences statsPrefs(
            android.content.Context c) {
        return c.getApplicationContext().getSharedPreferences(
                STATS_PREF, android.content.Context.MODE_PRIVATE);
    }

    private static String todayStr() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /** Count one skipped ad (deduped per package). */
    private void recordSkip(String pkg) {
        long now = System.currentTimeMillis();
        Long last = lastCountedMs.get(pkg);
        if (last != null && now - last < COUNT_DEDUP_MS) return;
        lastCountedMs.put(pkg, now);
        try {
            android.content.SharedPreferences p = statsPrefs(this);
            String today = todayStr();
            int total = p.getInt("total", 0) + 1;
            int dayCount = today.equals(p.getString("day", ""))
                    ? p.getInt("dayCount", 0) + 1 : 1;
            p.edit().putInt("total", total)
                    .putString("day", today).putInt("dayCount", dayCount).apply();
        } catch (Exception ignored) { }
    }

    /** Returns {todayCount, totalCount}. */
    public static int[] getSkipStats(android.content.Context c) {
        try {
            android.content.SharedPreferences p = statsPrefs(c);
            String today = todayStr();
            int dayCount = today.equals(p.getString("day", ""))
                    ? p.getInt("dayCount", 0) : 0;
            return new int[]{dayCount, p.getInt("total", 0)};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        attach(this);
        loadEnhanced(this);
    }

    private static java.io.File diagFile() {
        try {
            if (sAppCtx == null) return null;
            return new java.io.File(sAppCtx.getFilesDir(), "skipad_diag.log");
        } catch (Exception e) {
            return null;
        }
    }

    private static void ensureDiagLoaded() {
        if (sDiagLoaded || sAppCtx == null) return;
        sDiagLoaded = true;
        java.io.File f = diagFile();
        if (f == null || !f.exists()) return;
        try (java.io.BufferedReader br =
                     new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            synchronized (DIAG) {
                while ((line = br.readLine()) != null) {
                    if (!line.isEmpty()) DIAG.add(line);
                }
                while (DIAG.size() > DIAG_MAX) DIAG.remove(0);
            }
        } catch (Exception ignored) { }
    }

    private static void appendDiagLine(String line) {
        java.io.File f = diagFile();
        if (f == null) return;
        try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
            w.write(line);
            w.write('\n');
        } catch (Exception ignored) { }
        try {
            if (f.length() > 32 * 1024) compactDiag(f);
        } catch (Exception ignored) { }
    }

    private static void compactDiag(java.io.File f) {
        java.util.List<String> all = new java.util.ArrayList<>();
        try (java.io.BufferedReader br =
                     new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) all.add(line);
        } catch (Exception ignored) { return; }
        int from = Math.max(0, all.size() - DIAG_MAX);
        try (java.io.FileWriter w = new java.io.FileWriter(f, false)) {
            for (int i = from; i < all.size(); i++) {
                w.write(all.get(i));
                w.write('\n');
            }
        } catch (Exception ignored) { }
    }

    public static void diag(String s) {
        try {
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            String line = ts + " " + s;
            synchronized (DIAG) {
                ensureDiagLoaded();
                DIAG.add(line);
                while (DIAG.size() > DIAG_MAX) DIAG.remove(0);
            }
            appendDiagLine(line);
        } catch (Exception ignored) { }
    }

    public static String diagText() {
        ensureDiagLoaded();
        synchronized (DIAG) {
            StringBuilder sb = new StringBuilder();
            for (String l : DIAG) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    public static void clearDiag() {
        synchronized (DIAG) { DIAG.clear(); }
        java.io.File f = diagFile();
        if (f != null) {
            try { f.delete(); } catch (Exception ignored) { }
        }
    }

    private static String describe(AccessibilityNodeInfo n) {
        try {
            android.graphics.Rect b = new android.graphics.Rect();
            n.getBoundsInScreen(b);
            CharSequence t = n.getText();
            CharSequence wp = n.getPackageName();
            return "win=" + (wp == null ? "?" : wp)
                    + " class=" + n.getClassName()
                    + " text=" + (t == null ? "null" : "\"" + t + "\"")
                    + " clickable=" + n.isClickable()
                    + " visible=" + n.isVisibleToUser()
                    + " bounds=" + b.toShortString();
        } catch (Exception e) {
            return "?";
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            onAccessibilityEventInner(event);
        } catch (Throwable t) {
            // An accessibility service must never crash: one uncaught
            // throwable and the system marks it "faulty". Swallow, log, live.
            diag(this.getString(R.string.skipad_diag_event_ex, t));
        }
    }

    private void onAccessibilityEventInner(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        // 别点自己家的界面。
        CharSequence pkg = event.getPackageName();
        if (pkg == null || getPackageName().equals(pkg.toString())) return;
        String pkgStr = pkg.toString();

        long now = System.currentTimeMillis();
        // Track (re)launches: a window-state change after the app was away for
        // a while means a fresh start, where splash ads appear. In-app page
        // turns must not be mistaken for launches.
        Long seen = lastSeenMs.get(pkgStr);
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = event.getClassName();
            if (cls != null) lastActivity.put(pkgStr, cls.toString());
            if (seen == null || now - seen > LAUNCH_GAP_MS) {
                launchMs.put(pkgStr, now);
                scheduleLaunchCheck(pkgStr);
            }
        }
        lastSeenMs.put(pkgStr, now);

        if (now - lastClickMs < CLICK_COOLDOWN_MS) return;

        if (attemptSkip(pkgStr)) {
            lastClickMs = now;
        }
    }

    /**
     * One delayed look shortly after a (re)launch: if the ad screen is fully
     * static, no further events arrive, so the event-driven path alone would
     * never get a second chance.
     */
    private void scheduleLaunchCheck(final String pkg) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root == null) return;
                    CharSequence rp = root.getPackageName();
                    if (rp == null || !rp.toString().equals(pkg)) return;
                    if (findSkipNodeAnywhere() == null) {
                        maybeHeuristicTap(pkg, true);
                    }
                } catch (Throwable t) {
                    diag(SkipAdService.this.getString(R.string.skipad_diag_launch_ex, t));
                }
            }
        }, 1500);
    }

    /** Find a skip node in the active window and click it. Returns true if clicked. */
    private boolean attemptSkip(String pkg) {
        try {
            AccessibilityNodeInfo target = findSkipNodeAnywhere();
            if (target == null) {
                maybeHeuristicTap(pkg, false);
                // Throttled "saw nothing" note: tells recognition vs click apart.
                long n = System.currentTimeMillis();
                if (!pkg.equals(lastNoSkipPkg) || n - lastNoSkipMs > 10000) {
                    lastNoSkipPkg = pkg;
                    lastNoSkipMs = n;
                    diag(this.getString(R.string.skipad_diag_no_skip, pkg));
                }
                return false;
            }
            diag(this.getString(R.string.skipad_diag_found_skip, pkg, describe(target)));
            // The node may live in another package's window (found via the
            // multi-window search during an app transition); the retry and
            // the fake-click memory must use the node's package.
            CharSequence wp = null;
            try { wp = target.getPackageName(); } catch (Exception ignored) { }
            String targetPkg = wp != null ? wp.toString() : pkg;
            long now = System.currentTimeMillis();
            // User-confirmed (Sohu, 2026-10-02): the system BACK key dismisses
            // countdown-locked ads whose taps are all swallowed by the ad SDK.
            // Go straight to BACK on first sight of a countdown button - no
            // tap round-trips first. Throttled per app.
            if (isCountdownButton(target)
                    && !NO_BACK_PKGS.contains(targetPkg)
                    && now - lastBackMs.getOrDefault(targetPkg, 0L) > BACK_COOLDOWN_MS) {
                lastBackMs.put(targetPkg, now);
                boolean backOk = false;
                try {
                    backOk = performGlobalAction(GLOBAL_ACTION_BACK);
                } catch (Exception ignored) { }
                diag(this.getString(R.string.skipad_diag_back_key, targetPkg,
                        backOk ? this.getString(R.string.skipad_sent)
                                : this.getString(R.string.common_failed)));
                if (backOk) recordSkip(targetPkg);
                lastClickPkgMs.put(targetPkg, now);
                retryCount = 0;
                retryPkg = targetPkg;
                retryUntilMs = now + RETRY_WINDOW_MS;
                scheduleVerify();
                return true;
            }
            // Rediscovery: the button is back shortly after we clicked it, so
            // the last click was fake even though it reported success (the
            // retry can't always witness this: the node may leave the tree
            // during the transition). Distrust ACTION_CLICK for a while.
            boolean rediscovered = now - lastClickPkgMs.getOrDefault(targetPkg, 0L)
                    < REDISCOVERY_MS;
            if (rediscovered) {
                fakeClickMs.put(targetPkg, now);
                // Countdown-type skip buttons ("跳过 5") whose taps are all
                // ignored: the ad SDK doesn't honor touches during the
                // countdown. Escalate to BACK once in a while — for splash
                // ads BACK usually dismisses the ad outright.
                if (isCountdownButton(target)
                        && !NO_BACK_PKGS.contains(targetPkg)
                        && now - lastBackMs.getOrDefault(targetPkg, 0L) > BACK_COOLDOWN_MS) {
                    lastBackMs.put(targetPkg, now);
                    boolean backOk = false;
                    try {
                        backOk = performGlobalAction(GLOBAL_ACTION_BACK);
                    } catch (Exception ignored) { }
                    diag(this.getString(R.string.skipad_diag_back_key, targetPkg,
                            backOk ? this.getString(R.string.skipad_sent)
                                    : this.getString(R.string.common_failed)));
                    if (backOk) recordSkip(targetPkg);
                    lastClickPkgMs.put(targetPkg, now);
                    retryCount = 0;
                    retryPkg = targetPkg;
                    retryUntilMs = System.currentTimeMillis() + RETRY_WINDOW_MS;
                    scheduleVerify();
                    return true;
                }
                diag(this.getString(R.string.skipad_diag_rediscovered, targetPkg));
            }
            boolean distrust = rediscovered
                    || now - fakeClickMs.getOrDefault(targetPkg, 0L) < DISTRUST_MS
                    || GESTURE_FIRST_PKGS.contains(targetPkg);
            if (clickNode(target, distrust,
                    distrust ? (rediscovered ? this.getString(R.string.skipad_why_rediscovered)
                            : GESTURE_FIRST_PKGS.contains(targetPkg) ? this.getString(R.string.skipad_why_hardrule) : this.getString(R.string.skipad_why_fakesuccess))
                            : "")) {
                // The ad screen is static after layout, so no more events may
                // arrive: verify shortly and retry while the ad is still up.
                recordSkip(targetPkg);
                lastClickPkgMs.put(targetPkg, now);
                retryCount = 0;
                retryPkg = targetPkg;
                retryUntilMs = System.currentTimeMillis() + RETRY_WINDOW_MS;
                scheduleVerify();
                return true;
            }
            diag(this.getString(R.string.skipad_diag_click_failed, pkg));
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Search the active window first, then every window the service can see.
     * Some ad SDKs put the skip button in an overlay window that is not the
     * "active" one, so getRootInActiveWindow() alone misses it.
     */
    private AccessibilityNodeInfo findSkipNodeAnywhere() {
        try {
            AccessibilityNodeInfo n = findSkipNode(getRootInActiveWindow());
            if (n != null) return n;
        } catch (Exception ignored) { }
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    try {
                        AccessibilityNodeInfo n = findSkipNode(w.getRoot());
                        if (n != null) return n;
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Last resort for skip buttons that are invisible to accessibility (drawn
     * on a canvas/SurfaceView, or hidden from the tree): if the freshly
     * launched app shows an ad screen but no skip node, tap once at the
     * conventional top-right skip position. Gated to avoid mis-taps:
     * <ul>
     * <li>with ad evidence (a top-area "广告" marker, or a splash/ad-looking
     * activity) — may fire from live events;</li>
     * <li>blind (a package known to hide its skip button, or a suspiciously
     * simple freshly launched window) — only from the delayed launch check,
     * only within a few seconds of launch, throttled per app.</li>
     * </ul>
     */
    private void maybeHeuristicTap(String pkg, boolean allowBlind) {
        if (NO_HEURISTIC_PKGS.contains(pkg)) return;
        long now = System.currentTimeMillis();
        Long last = lastHeuristicMs.get(pkg);
        if (last != null && now - last < HEURISTIC_COOLDOWN_MS) return;
        Long launched = launchMs.get(pkg);
        if (launched == null) return;
        long age = now - launched;
        if (age > SPLASH_WINDOW_MS) return;
        String reason = null;
        int marker = adMarkerReason(pkg);
        if (marker != 0) {
            reason = this.getString(marker);
        } else if (isSplashActivity(pkg)) {
            reason = this.getString(R.string.skipad_reason_splash);
        } else if (allowBlind && age <= BLIND_WINDOW_MS) {
            if (KNOWN_INVISIBLE_SKIP.contains(pkg) || sEnhanced.contains(pkg)) {
                reason = sEnhanced.contains(pkg)
                        ? this.getString(R.string.skipad_reason_enhanced)
                        : this.getString(R.string.skipad_reason_invisible);
            } else if (countNodes(pkg) <= SPLASH_NODE_LIMIT) {
                reason = this.getString(R.string.skipad_reason_fewnodes);
            }
        }
        if (reason == null) return;
        lastHeuristicMs.put(pkg, now);
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        boolean ok = tapAt((int) (w * 0.90), (int) (h * 0.06));
        diag(this.getString(R.string.skipad_diag_heuristic, pkg, reason,
                ok ? this.getString(R.string.skipad_sent) : this.getString(R.string.common_failed)));
        if (ok) recordSkip(pkg);
    }

    /** Nodes in the package's windows (walk capped); splash screens are simple. */
    private int countNodes(String pkg) {
        int total = 0;
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return 0;
            for (android.view.accessibility.AccessibilityWindowInfo win : ws) {
                AccessibilityNodeInfo root = win.getRoot();
                if (root == null) continue;
                CharSequence rp = root.getPackageName();
                if (rp == null || !rp.toString().equals(pkg)) continue;
                total += countSubtree(root, SPLASH_NODE_LIMIT + 1 - total);
                if (total > SPLASH_NODE_LIMIT) break;
            }
        } catch (Exception ignored) { }
        return total;
    }

    private int countSubtree(AccessibilityNodeInfo n, int cap) {
        if (cap <= 0 || n == null) return 0;
        int c = 1;
        int kids;
        try {
            kids = n.getChildCount();
        } catch (Exception e) {
            return c;
        }
        for (int i = 0; i < kids && c < cap; i++) {
            AccessibilityNodeInfo k = null;
            try { k = n.getChild(i); } catch (Exception ignored) { }
            if (k != null) c += countSubtree(k, cap - c);
        }
        return c;
    }

    /** The foreground activity's class name looks like a splash/ad screen. */
    private boolean isSplashActivity(String pkg) {
        String a = lastActivity.get(pkg);
        if (a == null) return false;
        String l = a.toLowerCase(java.util.Locale.US);
        return l.contains("splash") || l.contains("advert");
    }

    /**
     * True if this package's windows show an "广告" label near the top, or a
     * "摇一摇" (shake-to-open) hint: the latter marks shake ads, which we then
     * try to close fast — before an accidental shake lets the ad SDK hijack
     * the phone. Returns the reason string-resource id, or 0 if no marker.
     */
    private int adMarkerReason(String pkg) {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return 0;
            int h = getResources().getDisplayMetrics().heightPixels;
            for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                if (w == null) continue;
                try {
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    CharSequence wp = root.getPackageName();
                    if (wp == null || !wp.toString().equals(pkg)) continue;
                    int r = findMarkerReason(root, h);
                    if (r != 0) return r;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private int findMarkerReason(AccessibilityNodeInfo node, int screenH) {
        if (node == null) return 0;
        // Iterative DFS (see findSkipNode): recursion overflowed the stack.
        java.util.ArrayDeque<AccessibilityNodeInfo> stack = new java.util.ArrayDeque<>();
        stack.push(node);
        int budget = 10000;
        while (!stack.isEmpty() && budget-- > 0) {
            AccessibilityNodeInfo n = stack.pop();
            if (n == null) continue;
            try {
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                String[] texts = {
                        t == null ? null : t.toString().trim(),
                        d == null ? null : d.toString().trim() };
                for (String s : texts) {
                    if (s == null || s.isEmpty()) continue;
                    int r = 0;
                    if (s.contains("广告") && s.length() <= 8) {
                        r = R.string.skipad_reason_admarker;
                    } else if (s.contains("摇一摇") && s.length() <= 10) {
                        r = R.string.skipad_reason_shake;
                    }
                    if (r != 0) {
                        android.graphics.Rect b = new android.graphics.Rect();
                        n.getBoundsInScreen(b);
                        if (!b.isEmpty() && b.centerY() < screenH * 0.25) return r;
                    }
                }
                int kids = n.getChildCount();
                for (int i = kids - 1; i >= 0; i--) {
                    AccessibilityNodeInfo c = null;
                    try { c = n.getChild(i); } catch (Exception ignored) { }
                    if (c != null) stack.push(c);
                }
            } catch (Exception ignored) { }
        }
        return 0;
    }

    /** Raw coordinate tap, no node needed. */
    private boolean tapAt(int x, int y) {
        try {
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(x, y);
            android.accessibilityservice.GestureDescription.StrokeDescription s =
                    new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 80);
            android.accessibilityservice.GestureDescription g =
                    new android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(s).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private void scheduleVerify() {
        handler.removeCallbacks(verifyRunnable);
        handler.postDelayed(verifyRunnable, 1000);
    }

    private final Runnable verifyRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (retryCount >= MAX_RETRY
                        || System.currentTimeMillis() > retryUntilMs) {
                    diag(SkipAdService.this.getString(R.string.skipad_diag_verify_done, retryPkg,
                            retryCount > 0 ? SkipAdService.this.getString(R.string.skipad_verify_retapped, retryCount)
                                    : SkipAdService.this.getString(R.string.skipad_verify_gone)));
                    retryCount = 0;
                    return;
                }
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) {
                    scheduleVerify(); // transient; keep watching
                    return;
                }
                AccessibilityNodeInfo target = findSkipNodeAnywhere();
                if (target == null) {
                    // The button may be mid-transition (or the ad may be
                    // gone); keep watching until the window expires instead
                    // of giving up on the first miss.
                    scheduleVerify();
                    return;
                }
                // Only retry inside the app we originally clicked in.
                CharSequence pkg = root.getPackageName();
                if (pkg == null || !pkg.toString().equals(retryPkg)) {
                    // Transient transition (e.g. a system overlay momentarily
                    // active): keep watching until the retry window expires
                    // instead of silently dropping the retry chain.
                    scheduleVerify();
                    return;
                }
                retryCount++;
                diag(SkipAdService.this.getString(R.string.skipad_diag_verify_retry,
                        retryPkg, retryCount, describe(target)));
                // Proven fake-success: remember to distrust ACTION_CLICK for
                // this package's skip button for a while.
                fakeClickMs.put(retryPkg, System.currentTimeMillis());
                if (clickNode(target, true, SkipAdService.this.getString(R.string.skipad_why_verify))) {
                    recordSkip(retryPkg);
                    scheduleVerify();
                }
            } catch (Throwable t) {
                diag(SkipAdService.this.getString(R.string.skipad_diag_verify_ex, t));
            }
        }
    };

    /** Depth-first search for a node that looks like a "skip ad" button. */
    private AccessibilityNodeInfo findSkipNode(AccessibilityNodeInfo node) {
        // Prefer visible nodes: hidden decoys with the same text must not
        // shadow the real button.
        AccessibilityNodeInfo n = findSkipNode(node, true);
        return n != null ? n : findSkipNode(node, false);
    }

    private AccessibilityNodeInfo findSkipNode(AccessibilityNodeInfo node, boolean visibleOnly) {
        if (node == null) return null;
        // Iterative DFS: app trees can be deep/wide enough to overflow the
        // call stack (seen in the wild as StackOverflowError).
        java.util.ArrayDeque<AccessibilityNodeInfo> stack = new java.util.ArrayDeque<>();
        stack.push(node);
        int budget = 10000; // pathological trees get cut off, not stalled on
        while (!stack.isEmpty() && budget-- > 0) {
            AccessibilityNodeInfo n = stack.pop();
            if (n == null) continue;
            try {
                if (!visibleOnly || n.isVisibleToUser()) {
                    if (isSkipNode(n)) return n;
                }
                int kids = n.getChildCount();
                for (int i = kids - 1; i >= 0; i--) {
                    AccessibilityNodeInfo c = null;
                    try { c = n.getChild(i); } catch (Exception ignored) { }
                    if (c != null) stack.push(c);
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Tight match: short text containing "跳过"/"skip", or a top-right countdown. */
    private boolean isSkipNode(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null) {
            String t = text.toString().trim();
            if (looksLikeSkip(t)) return true;
            if (isCountdown(t) && isTopRight(node)) return true;
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString().trim();
            if (looksLikeSkip(d)) return true;
            if (isCountdown(d) && isTopRight(node)) return true;
        }
        // Ad-SDK skip buttons sometimes carry no text at all, only a view id
        // like "tt_splash_skip_btn". Only trust it in the conventional
        // top-right skip-button area to avoid hitting e.g. media "next" keys.
        try {
            String id = node.getViewIdResourceName();
            if (id != null
                    && id.toLowerCase(java.util.Locale.US).contains("skip")
                    && isTopRight(node)) {
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private boolean looksLikeSkip(String t) {
        if (t.isEmpty() || t.length() > 8) return false;
        // "跳过开屏广告" etc. are settings descriptions, not buttons; only the
        // terse "跳过广告" is still accepted as a button label.
        if (t.contains("广告") && t.length() > 4) return false;
        return t.contains("跳过") || t.toLowerCase(java.util.Locale.US).contains("skip");
    }

    /** Countdown-only skip buttons, e.g. "5s" / "3秒", conventionally top-right. */
    private boolean isCountdown(String t) {
        return t.matches("(?i)^\\d{1,2}\\s*(s|sec|秒)$");
    }

    private boolean isTopRight(AccessibilityNodeInfo node) {
        try {
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            return bounds.centerX() > w * 0.6 && bounds.centerY() < h * 0.4;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Click the node itself if clickable, else the nearest clickable ancestor.
     * Falls back to a gesture tap on the node's center: some ad buttons are
     * not marked clickable (or the click action silently fails, e.g. inside
     * WebViews), while a tap at their coordinates still works.
     */
    /**
     * Click a skip node. First tries the accessibility ACTION_CLICK on the
     * node (or nearest clickable ancestor); some apps report success without
     * dismissing the ad, so callers that already saw one fake success pass
     * forceGesture to skip straight to a real coordinate tap.
     */
    private boolean clickNode(AccessibilityNodeInfo node, boolean forceGesture, String why) {
        if (!forceGesture) {
            AccessibilityNodeInfo cur = node;
            try {
                while (cur != null) {
                    if (cur.isClickable()) {
                        boolean ok = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        diag(this.getString(R.string.skipad_diag_a11y_click,
                                ok ? this.getString(R.string.skipad_click_ok)
                                        : this.getString(R.string.common_failed),
                                describe(cur)));
                        if (ok) {
                            return true;
                        }
                        break;
                    }
                    cur = cur.getParent();
                }
            } catch (Exception ignored) {
            }
        }
        boolean g = tapCenter(node);
        diag(this.getString(R.string.skipad_diag_gesture, why,
                g ? this.getString(R.string.skipad_sent) : this.getString(R.string.common_failed),
                describe(node)));
        return g;
    }

    /** Simulate a tap on the node's center; refuses oversized nodes. */
    private boolean tapCenter(AccessibilityNodeInfo node) {
        try {
            android.graphics.Rect b = new android.graphics.Rect();
            node.getBoundsInScreen(b);
            if (b.isEmpty()) return false;
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            long area = (long) b.width() * (long) b.height();
            if (area > (long) w * h * 10 / 100) return false; // too big: don't blind-tap
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(b.centerX(), b.centerY());
            android.accessibilityservice.GestureDescription.StrokeDescription s =
                    new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 80);
            android.accessibilityservice.GestureDescription g =
                    new android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(s).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(verifyRunnable);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        // The system unbinds us both when the user turns the service off and
        // on plain process death. Only the former clears the sticky "已开启".
        SkipAdView.onServiceUnbind(this);
        // Service is going away: the keep-alive notification must not linger.
        KeepAliveService.stop(this);
        return super.onUnbind(intent);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Best effort: when the system (re)binds us, arm the keep-alive so a
        // later "clear background" skips our process. May throw if we are in
        // background on Android 12+; the UI path covers the normal case.
        KeepAliveService.start(this);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(verifyRunnable);
        super.onDestroy();
    }
}
