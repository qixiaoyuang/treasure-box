package com.ayou.tiaoyitiao;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small programmatic-UI helpers shared by all tabs. */
public class Ui {
    public static final int RED = 0xFFF44336;

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static TextView sectionHeader(Context c, String title) {
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(RED);
        t.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 6));
        return t;
    }

    /** Two-column row: gray label left, dark value right. Returns the row; value is child(1). */
    public static LinearLayout row(Context c, String label, String value) {        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(dp(c, 16), dp(c, 7), dp(c, 16), dp(c, 7));
        TextView k = new TextView(c);
        k.setText(label);
        k.setTextSize(14);
        k.setTextColor(0xFF888888);
        k.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(0xFF222222);
        v.setGravity(Gravity.END);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.6f));
        l.addView(k);
        l.addView(v);
        return l;
    }

    // ---------- Truly edge-to-edge for full-screen test activities ----------

    private static final int IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    /** Call from onCreate() before setContentView(). Draws under status/nav bars and cutout. */
    public static void applyEdgeToEdge(Activity a) {
        try {
            applyEdgeToEdgeUnsafe(a);
        } catch (Throwable ignored) {
            // Never let an OEM-specific fullscreen quirk crash the activity.
        }
    }

    private static void applyEdgeToEdgeUnsafe(Activity a) {
        Window w = a.getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(attrs);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
        }
    }

    /** Call from onWindowFocusChanged(true) on API < 30 to re-hide system bars. */
    public static void reapplyImmersive(Activity a) {
        try {
            if (Build.VERSION.SDK_INT < 30) {
                a.getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
            }
        } catch (Throwable ignored) { }
    }
}
