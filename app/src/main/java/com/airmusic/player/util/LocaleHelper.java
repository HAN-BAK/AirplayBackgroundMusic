package com.airmusic.player.util;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * App-level language switching. The chosen language is stored in Prefs and
 * applied to every activity (and the playback service) through
 * attachBaseContext(). Each activity remembers the language it was created
 * with and recreates itself on resume when that language changed while the
 * activity was paused, so activities deeper in the back stack also refresh.
 */
public final class LocaleHelper {

    private LocaleHelper() {
    }

    /** Applies the saved language to a base context (call from attachBaseContext). */
    public static Context attach(Context base) {
        String lang = new Prefs(base).getLanguage();
        Locale locale = toLocale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    /** Returns the currently saved UI language ("zh", "en", "ja" or "ko"). */
    public static String currentLanguage(Context context) {
        return new Prefs(context).getLanguage();
    }

    public static Locale toLocale(String lang) {
        if ("en".equals(lang)) return Locale.ENGLISH;
        if ("ja".equals(lang)) return Locale.JAPANESE;
        if ("ko".equals(lang)) return Locale.KOREAN;
        return Locale.SIMPLIFIED_CHINESE;
    }
}
