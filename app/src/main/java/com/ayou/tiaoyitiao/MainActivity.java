package com.ayou.tiaoyitiao;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;

/**
 * Single-screen app: only the 跳广告 (ad-skip) feature.
 * No tabs, no device diagnostics, no monitoring — just SkipAdView
 * on top of the rabbit watermark background.
 */
public class MainActivity extends Activity {
    private SkipAdView skipAdView;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout content = new FrameLayout(this);
        setRabbitBackground(content);
        skipAdView = new SkipAdView(this);
        content.addView(skipAdView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(content);
        skipAdView.setActive(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (skipAdView != null) skipAdView.setActive(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (skipAdView != null) skipAdView.setActive(false);
    }

    /** App-wide background: the rabbit as a faint centered watermark. */
    private void setRabbitBackground(FrameLayout content) {
        try {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeResource(
                    getResources(), R.drawable.app_bg_rabbit);
            if (bm == null) return;
            android.graphics.drawable.BitmapDrawable bg =
                    new android.graphics.drawable.BitmapDrawable(getResources(), bm);
            bg.setGravity(Gravity.CENTER);
            bg.setAlpha(72); // subtle so all text stays readable
            content.setBackground(bg);
        } catch (Exception ignored) { }
    }
}
