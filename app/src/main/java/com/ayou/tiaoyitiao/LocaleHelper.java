package com.ayou.tiaoyitiao;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * In-app language switch: 中文 / English.
 * Every Activity/Service wraps its base context with {@link #wrap(Context)},
 * so all views, toasts, dialogs and notifications resolve strings in the
 * chosen language. Default follows the system language.
 */
public class LocaleHelper {
    private static final String PREF = "locale_prefs";
    private static final String KEY = "app_lang"; // "zh" or "en"; "" = not chosen yet

    public static final String ZH = "zh";
    public static final String EN = "en";

    /** Currently selected language code. */
    public static String getLang(Context c) {
        String v = prefs(c).getString(KEY, "");
        if (!v.isEmpty()) return v;
        return "en".equals(Locale.getDefault().getLanguage()) ? EN : ZH;
    }

    public static void setLang(Context c, String lang) {
        prefs(c).edit().putString(KEY, lang).apply();
    }

    public static boolean isEnglish(Context c) {
        return EN.equals(getLang(c));
    }

    /** Returns a context whose resources resolve string resources in the
     * selected language. */
    public static Context wrap(Context base) {
        Locale locale = EN.equals(getLang(base)) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        return base.createConfigurationContext(cfg);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
