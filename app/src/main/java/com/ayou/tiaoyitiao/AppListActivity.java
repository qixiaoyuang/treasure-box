package com.ayou.tiaoyitiao;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "应用加强": lists every launcher app on the device. Toggling an app
 * puts it on the service's enhanced list: shortly after launch the
 * service blind-taps the top-right corner even when no skip button is
 * visible in the accessibility tree (same aggressive treatment as the
 * built-in invisible-skip packages).
 */
public class AppListActivity extends Activity {

    private static class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        boolean enhanced;
    }

    private LinearLayout listBox;
    private EditText searchEt;
    private final List<AppEntry> all = new ArrayList<>();
    private String filter = "";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout content = new FrameLayout(this);
        setRabbitBackground(content);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 12), p, p);
        content.addView(root, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_list_title));
        title.setTextSize(20);
        title.setTextColor(0xFF222222);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText(getString(R.string.app_list_note));
        note.setTextSize(12);
        note.setTextColor(0xFF888888);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = Ui.dp(this, 6);
        root.addView(note, np);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(-1, -2);
        brp.topMargin = Ui.dp(this, 10);
        root.addView(btnRow, brp);

        Button allOn = new Button(this);
        allOn.setAllCaps(false);
        allOn.setText(getString(R.string.app_list_all_on));
        allOn.setOnClickListener(v -> setAll(true));
        btnRow.addView(allOn, new LinearLayout.LayoutParams(0, -2, 1f));

        Button allOff = new Button(this);
        allOff.setAllCaps(false);
        allOff.setText(getString(R.string.app_list_all_off));
        allOff.setOnClickListener(v -> setAll(false));
        btnRow.addView(allOff, new LinearLayout.LayoutParams(0, -2, 1f));

        searchEt = new EditText(this);
        searchEt.setHint(getString(R.string.app_list_search_hint));
        searchEt.setSingleLine(true);
        LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(-1, -2);
        sep.topMargin = Ui.dp(this, 10);
        root.addView(searchEt, sep);
        searchEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.US);
                renderList();
            }
        });

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svp.topMargin = Ui.dp(this, 8);
        root.addView(sv, svp);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new ScrollView.LayoutParams(-1, -2));

        setContentView(content);
        loadApps();
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
            bg.setAlpha(72);
            content.setBackground(bg);
        } catch (Exception ignored) { }
    }

    private void loadApps() {
        new Thread(() -> {
            List<AppEntry> tmp = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
                String self = getPackageName();
                java.util.Set<String> enh = SkipAdService.getEnhancedPkgs(this);
                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (self.equals(pkg)) continue;
                    AppEntry e = new AppEntry();
                    e.pkg = pkg;
                    try {
                        e.label = ri.loadLabel(pm).toString();
                        e.icon = ri.loadIcon(pm);
                    } catch (Exception ex) {
                        e.label = pkg;
                    }
                    e.enhanced = enh.contains(pkg);
                    tmp.add(e);
                }
                // Enhanced first, then alphabetical.
                Collections.sort(tmp, (a, c) -> {
                    if (a.enhanced != c.enhanced) return a.enhanced ? -1 : 1;
                    return a.label.compareToIgnoreCase(c.label);
                });
            } catch (Exception ignored) { }
            final List<AppEntry> done = tmp;
            runOnUiThread(() -> {
                all.clear();
                all.addAll(done);
                renderList();
            });
        }).start();
    }

    private void renderList() {
        listBox.removeAllViews();
        for (AppEntry e : all) {
            if (!filter.isEmpty()
                    && !e.label.toLowerCase(Locale.US).contains(filter)
                    && !e.pkg.toLowerCase(Locale.US).contains(filter)) continue;
            listBox.addView(rowFor(e));
        }
    }

    private LinearLayout rowFor(final AppEntry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

        ImageView iv = new ImageView(this);
        if (e.icon != null) iv.setImageDrawable(e.icon);
        row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams txp = new LinearLayout.LayoutParams(0, -2, 1f);
        txp.leftMargin = Ui.dp(this, 12);
        row.addView(tx, txp);

        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextSize(15);
        name.setTextColor(0xFF222222);
        tx.addView(name);

        TextView pkg = new TextView(this);
        pkg.setText(e.pkg);
        pkg.setTextSize(11);
        pkg.setTextColor(0xFF999999);
        tx.addView(pkg);

        Switch sw = new Switch(this);
        sw.setChecked(e.enhanced);
        sw.setOnCheckedChangeListener((v, on) -> {
            e.enhanced = on;
            SkipAdService.setEnhanced(this, e.pkg, on);
        });
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private void setAll(boolean on) {
        for (AppEntry e : all) {
            e.enhanced = on;
            SkipAdService.setEnhanced(this, e.pkg, on);
        }
        renderList();
    }
}
