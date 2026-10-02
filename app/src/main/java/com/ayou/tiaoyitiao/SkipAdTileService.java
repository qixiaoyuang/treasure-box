package com.ayou.tiaoyitiao;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Control-center / notification-shade tile ("应用快捷开关" on MIUI).
 * Shows whether the ad-skip accessibility service is currently on.
 * Tap behavior: if the service is on, just confirm with a toast; if off,
 * jump straight to the system accessibility settings so one more tap flips
 * the toggle. Android forbids any app from flipping that toggle in code,
 * so this is the shortest possible tap-to-run path.
 */
public class SkipAdTileService extends TileService {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = SkipAdView.isEnabled(this);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(on ? getString(R.string.tile_subtitle_on)
                        : getString(R.string.tile_subtitle_off));
            }
        } catch (Throwable ignored) { }
        tile.updateTile();
    }

    @Override
    public void onClick() {
        final boolean on = SkipAdView.isEnabled(this);
        unlockAndRun(() -> {
            if (on) {
                // Already running: confirm and stay put, don't yank the user
                // out of what they were doing.
                try {
                    android.widget.Toast.makeText(this,
                            getString(R.string.tile_toast_running),
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) { }
                return;
            }
            // Off: take the user directly to the system switch. The toggle
            // itself can only be flipped by hand in Settings.
            Intent i = new Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivityAndCollapse(i);
            } catch (Exception e) {
                try {
                    startActivity(i);
                } catch (Exception ignored) { }
            }
        });
    }
}
