package com.ayou.tiaoyitiao;

import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Sub-tab "跳广告": shows whether SkipAdService is enabled and jumps to
 * system Accessibility settings. The service itself can only be toggled
 * by the user in system settings.
 */
public class SkipAdView extends LinearLayout {
    private static final String PREF = "skipad_prefs";
    private static final String KEY_LOG_ON = "diag_log_on";

    private final MainActivity act;
    private TextView statusTv;
    private TextView statsTv;
    private TextView logTv;
    private Button openBtn;
    private android.widget.Switch logSwitch;
    private LinearLayout logBtns;
    private boolean logOn = true;
    private boolean active;

    public SkipAdView(MainActivity a) {
        super(a);
        act = a;
        setOrientation(VERTICAL);
        SkipAdService.attach(a.getApplicationContext());
        try {
            logOn = a.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                    .getBoolean(KEY_LOG_ON, true);
        } catch (Exception ignored) { }

        ScrollView sv = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(VERTICAL);
        int p = Ui.dp(a, 20);
        root.setPadding(p, Ui.dp(a, 32), p, p);
        sv.addView(root, new ScrollView.LayoutParams(-1, -2));
        addView(sv, new LinearLayout.LayoutParams(-1, -1));

        TextView title = new TextView(a);
        title.setText(a.getString(R.string.skiptab_title));
        title.setTextSize(22);
        title.setTextColor(0xFF222222);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusTv = new TextView(a);
        statusTv.setTextSize(15);
        statusTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp =
                new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(a, 16);
        root.addView(statusTv, sp);

        statsTv = new TextView(a);
        statsTv.setTextSize(13);
        statsTv.setTextColor(0xFF888888);
        statsTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stp =
                new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = Ui.dp(a, 8);
        root.addView(statsTv, stp);
        updateStats();

        openBtn = new Button(a);
        openBtn.setAllCaps(false);
        openBtn.setText(a.getString(R.string.skiptab_open_settings));
        openBtn.setTextSize(16);
        openBtn.setTextColor(0xFFFFFFFF);
        openBtn.setBackgroundColor(Ui.RED);
        LinearLayout.LayoutParams bp =
                new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Ui.dp(a, 24);
        root.addView(openBtn, bp);
        openBtn.setOnClickListener(v -> {
            try {
                act.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) { }
        });

        Button battBtn = new Button(a);
        battBtn.setAllCaps(false);
        battBtn.setText(a.getString(R.string.skiptab_keepalive));
        battBtn.setTextSize(14);
        LinearLayout.LayoutParams bbp =
                new LinearLayout.LayoutParams(-1, -2);
        bbp.topMargin = Ui.dp(a, 12);
        root.addView(battBtn, bbp);
        battBtn.setOnClickListener(v -> {
            try {
                android.os.PowerManager pm =
                        (android.os.PowerManager) a.getSystemService(
                                android.content.Context.POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName())) {
                    return;
                }
                Intent i = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + a.getPackageName()));
                act.startActivity(i);
            } catch (Exception ignored) { }
        });

        Button appsBtn = new Button(a);
        appsBtn.setAllCaps(false);
        appsBtn.setText(a.getString(R.string.skiptab_app_list));
        appsBtn.setTextSize(14);
        LinearLayout.LayoutParams abp =
                new LinearLayout.LayoutParams(-1, -2);
        abp.topMargin = Ui.dp(a, 12);
        root.addView(appsBtn, abp);
        appsBtn.setOnClickListener(v -> {
            try {
                act.startActivity(new Intent(act, AppListActivity.class));
            } catch (Exception ignored) { }
        });

        Button helpToggle = new Button(a);
        helpToggle.setAllCaps(false);
        helpToggle.setText(a.getString(R.string.skiptab_help_title) + " ▸");
        helpToggle.setTextSize(14);
        LinearLayout.LayoutParams htp =
                new LinearLayout.LayoutParams(-1, -2);
        htp.topMargin = Ui.dp(a, 24);
        root.addView(helpToggle, htp);

        final TextView help = new TextView(a);
        help.setTextSize(13);
        help.setTextColor(0xFF888888);
        help.setLineSpacing(Ui.dp(a, 4), 1f);
        LinearLayout.LayoutParams hp =
                new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Ui.dp(a, 8);
        root.addView(help, hp);
        help.setText(a.getString(R.string.skiptab_help, brandGuide(a)));
        help.setVisibility(GONE);
        helpToggle.setOnClickListener(v -> {
            boolean show = help.getVisibility() != VISIBLE;
            help.setVisibility(show ? VISIBLE : GONE);
            helpToggle.setText(a.getString(R.string.skiptab_help_title)
                    + (show ? " ▾" : " ▸"));
        });

        Button whyToggle = new Button(a);
        whyToggle.setAllCaps(false);
        whyToggle.setText(a.getString(R.string.skiptab_why_title) + " ▸");
        whyToggle.setTextSize(14);
        LinearLayout.LayoutParams wtp =
                new LinearLayout.LayoutParams(-1, -2);
        wtp.topMargin = Ui.dp(a, 12);
        root.addView(whyToggle, wtp);

        final TextView why = new TextView(a);
        why.setTextSize(13);
        why.setTextColor(0xFF888888);
        why.setLineSpacing(Ui.dp(a, 4), 1f);
        LinearLayout.LayoutParams wyp =
                new LinearLayout.LayoutParams(-1, -2);
        wyp.topMargin = Ui.dp(a, 8);
        root.addView(why, wyp);
        why.setText(a.getString(R.string.skiptab_why_content));
        why.setVisibility(GONE);
        whyToggle.setOnClickListener(v -> {
            boolean show = why.getVisibility() != VISIBLE;
            why.setVisibility(show ? VISIBLE : GONE);
            whyToggle.setText(a.getString(R.string.skiptab_why_title)
                    + (show ? " ▾" : " ▸"));
        });

        LinearLayout logHead = new LinearLayout(a);
        logHead.setOrientation(HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(-1, -2);
        ltp.topMargin = Ui.dp(a, 24);
        root.addView(logHead, ltp);

        TextView logTitle = new TextView(a);
        logTitle.setText(a.getString(R.string.skiptab_log_title));
        logTitle.setTextSize(14);
        logTitle.setTextColor(0xFF222222);
        logHead.addView(logTitle,
                new LinearLayout.LayoutParams(0, -2, 1f));

        logSwitch = new android.widget.Switch(a);
        logSwitch.setChecked(logOn);
        logHead.addView(logSwitch,
                new LinearLayout.LayoutParams(-2, -2));
        logSwitch.setOnCheckedChangeListener((v, on) -> {
            logOn = on;
            try {
                a.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_LOG_ON, on).apply();
            } catch (Exception ignored) { }
            applyLogVisibility();
        });

        logTv = new TextView(a);
        logTv.setTextSize(11);
        logTv.setTextColor(0xFF555555);
        logTv.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2);
        lp2.topMargin = Ui.dp(a, 8);
        root.addView(logTv, lp2);

        logBtns = new LinearLayout(a);
        logBtns.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-1, -2);
        lbp.topMargin = Ui.dp(a, 8);
        root.addView(logBtns, lbp);

        Button refreshLogBtn = new Button(a);
        refreshLogBtn.setAllCaps(false);
        refreshLogBtn.setText(a.getString(R.string.skiptab_refresh_log));
        refreshLogBtn.setOnClickListener(v -> refreshLog());
        logBtns.addView(refreshLogBtn,
                new LinearLayout.LayoutParams(0, -2, 1f));

        Button clearLogBtn = new Button(a);
        clearLogBtn.setAllCaps(false);
        clearLogBtn.setText(a.getString(R.string.skiptab_clear_log));
        clearLogBtn.setOnClickListener(v -> {
            SkipAdService.clearDiag();
            refreshLog();
        });
        logBtns.addView(clearLogBtn,
                new LinearLayout.LayoutParams(0, -2, 1f));

        applyLogVisibility();
        refresh();
    }

    /** Called by MainActivity when this tab becomes visible / hidden. */
    public void setActive(boolean b) {
        active = b;
        if (b) {
            refresh();
            startLogPoll();
        } else {
            stopLogPoll();
        }
    }

    private final Runnable logPoll = new Runnable() {
        @Override
        public void run() {
            if (!active || !logOn) return;
            refreshLog();
            updateStats();
            postDelayed(this, 1500);
        }
    };

    private void startLogPoll() {
        stopLogPoll();
        if (active && logOn) postDelayed(logPoll, 1500);
    }

    private void stopLogPoll() {
        removeCallbacks(logPoll);
    }

    private void applyLogVisibility() {
        int v = logOn ? VISIBLE : GONE;
        if (logTv != null) logTv.setVisibility(v);
        if (logBtns != null) logBtns.setVisibility(v);
        if (logOn) {
            refreshLog();
            startLogPoll();
        } else {
            stopLogPoll();
        }
    }

    /** Re-read the enabled state (call when the tab becomes visible). */
    public void refresh() {
        statusRetries = 0;
        updateStats();
        refreshOnce();
    }

    private int statusRetries = 0;

    private void refreshOnce() {
        final boolean on = isEnabled(act);
        // Keep-alive follows the real switch: armed while enabled so that
        // "clear background" skips our process instead of killing it (which
        // on MIUI also flips the accessibility toggle off); withdrawn when
        // disabled so no stale notification lingers.
        if (on) {
            KeepAliveService.start(act);
        } else {
            KeepAliveService.stop(act);
        }
        post(() -> {
            statusTv.setText(on ? act.getString(R.string.skiptab_status_on)
                    : act.getString(R.string.skiptab_status_off));
            statusTv.setTextColor(on ? 0xFF2E7D32 : 0xFF888888);
            openBtn.setText(on ? act.getString(R.string.skiptab_manage_settings)
                    : act.getString(R.string.skiptab_open_settings));
            refreshLog();
        });
        // The system binds the service asynchronously after the user flips
        // the toggle: a single snapshot can land in the blind spell (toggle
        // on, service not bound yet) and wrongly show 未开启. Re-check a few
        // times so the page flips to 已开启 on its own.
        if (!on && statusRetries < 8) {
            statusRetries++;
            postDelayed(this::refreshOnce, 1500);
        }
    }

    private void refreshLog() {
        try {
            String t = SkipAdService.diagText();
            logTv.setText(t.isEmpty() ? act.getString(R.string.skiptab_log_empty) : t);
        } catch (Exception ignored) { }
    }

    /** "今日跳过 X 个 · 累计 Y 个" under the status line. */
    private void updateStats() {
        try {
            if (statsTv == null) return;
            int[] s = SkipAdService.getSkipStats(act);
            statsTv.setText(act.getString(R.string.skiptab_stats, s[0], s[1]));
        } catch (Exception ignored) { }
    }

    /**
     * The enabled state is read straight from the system every time: the
     * accessibility toggle lives in Settings.Secure, so it already survives
     * app restarts by itself. No local "memory" — a cached ON would lie when
     * the system turns the service off behind our back (MIUI does this after
     * clearing the app from recents). What you see is what the system says.
     */
    public static boolean isEnabled(android.content.Context c) {
        return inSecureSetting(c) || isBoundLive(c);
    }

    /** The user's toggle: persists in the system until the user cancels it. */
    static boolean inSecureSetting(android.content.Context c) {
        try {
            String pkg = c.getPackageName();
            String flat = new android.content.ComponentName(c, SkipAdService.class).flattenToString();
            // Some OEMs (e.g. MIUI) store the shorthand "pkg/.Class" form.
            String shorthand = pkg + "/." + SkipAdService.class.getSimpleName();
            String enabled = android.provider.Settings.Secure.getString(c.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                for (String s : enabled.split(":")) {
                    String t = s.trim();
                    if (t.equalsIgnoreCase(flat) || t.equalsIgnoreCase(shorthand)) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** The service is actually bound right now. */
    private static boolean isBoundLive(android.content.Context c) {
        try {
            String pkg = c.getPackageName();
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            c.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                for (android.accessibilityservice.AccessibilityServiceInfo info :
                        am.getEnabledAccessibilityServiceList(
                                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    if (info.getResolveInfo() != null
                            && info.getResolveInfo().serviceInfo != null) {
                        android.content.pm.ServiceInfo si = info.getResolveInfo().serviceInfo;
                        if (pkg.equals(si.packageName)
                                && SkipAdService.class.getName().equals(si.name)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Nothing to remember locally anymore; kept for the service's call. */
    static void onServiceUnbind(android.content.Context c) {
    }

    /**
     * Brand-specific anti-kill guidance: same APK shows the right settings
     * path on each phone. Step 1 (ignore battery optimizations) is a system
     * dialog and works on every brand; step 2's menu path differs.
     */
    private static String brandGuide(android.content.Context c) {
        String man = android.os.Build.MANUFACTURER;
        man = man == null ? "" : man.toLowerCase(java.util.Locale.US);
        String step1 = c.getString(R.string.skiptab_guide_step1);
        if (man.contains("xiaomi") || man.contains("redmi") || man.contains("poco")) {
            return c.getString(R.string.skiptab_guide_combine, step1,
                    c.getString(R.string.skiptab_guide_xiaomi));
        }
        if (man.contains("huawei") || man.contains("honor")) {
            return c.getString(R.string.skiptab_guide_combine, step1,
                    c.getString(R.string.skiptab_guide_huawei));
        }
        if (man.contains("oppo") || man.contains("realme")) {
            return c.getString(R.string.skiptab_guide_combine, step1,
                    c.getString(R.string.skiptab_guide_oppo));
        }
        if (man.contains("oneplus")) {
            return c.getString(R.string.skiptab_guide_combine, step1,
                    c.getString(R.string.skiptab_guide_oneplus));
        }
        if (man.contains("vivo") || man.contains("iqoo")) {
            return c.getString(R.string.skiptab_guide_combine, step1,
                    c.getString(R.string.skiptab_guide_vivo));
        }
        if (man.contains("meizu") || man.contains("zte")
                || man.contains("nubia") || man.contains("samsung")
                || man.contains("google") || man.contains("motorola")
                || man.contains("sony") || man.contains("asus")) {
            return c.getString(R.string.skiptab_guide_native);
        }
        return c.getString(R.string.skiptab_guide_combine, step1,
                c.getString(R.string.skiptab_guide_default));
    }
}
