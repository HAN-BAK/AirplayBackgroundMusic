package com.airmusic.player.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Central place for app settings.
 */
public final class Prefs {

    public static final String PLAY_MODE_SEQUENCE = "SEQUENCE";
    public static final String PLAY_MODE_REPEAT_ONE = "REPEAT_ONE";
    public static final String PLAY_MODE_SHUFFLE = "SHUFFLE";
    public static final String PLAY_MODE_FOLDER_LOOP = "FOLDER_LOOP";

    private static final String FILE = "airmusic_prefs";

    private static final String KEY_AIRPLAY_NAME = "airplay_name";
    private static final String KEY_MUSIC_FOLDER_URI = "music_folder_uri";
    private static final String KEY_MUSIC_FOLDER_PATH = "music_folder_path";
    private static final String KEY_MUSIC_FOLDER_DISPLAY = "music_folder_display";
    private static final String KEY_PLAY_MODE = "play_mode";
    private static final String KEY_AUTO_PLAY = "auto_play_on_start";
    private static final String KEY_BALANCE = "balance";

    private static final String KEY_LAST_TRACK_URI = "last_track_uri";
    private static final String KEY_LAST_TRACK_POSITION = "last_track_position";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getAirPlayName() {
        return sp.getString(KEY_AIRPLAY_NAME, "AirPlay音箱");
    }

    public void setAirPlayName(String name) {
        if (name != null && name.trim().length() > 0) {
            sp.edit().putString(KEY_AIRPLAY_NAME, name.trim()).apply();
        }
    }

    public String getMusicFolderUri() {
        return sp.getString(KEY_MUSIC_FOLDER_URI, null);
    }

    /** Raw filesystem path chosen with the built-in folder picker. */
    public String getMusicFolderPath() {
        return sp.getString(KEY_MUSIC_FOLDER_PATH, null);
    }

    public String getMusicFolderDisplay() {
        return sp.getString(KEY_MUSIC_FOLDER_DISPLAY, null);
    }

    public void setMusicFolder(String uri, String display) {
        sp.edit()
            .putString(KEY_MUSIC_FOLDER_URI, uri)
            .remove(KEY_MUSIC_FOLDER_PATH)
            .putString(KEY_MUSIC_FOLDER_DISPLAY, display)
            .apply();
    }

    public void setMusicFolderPath(String path, String display) {
        sp.edit()
            .putString(KEY_MUSIC_FOLDER_PATH, path)
            .remove(KEY_MUSIC_FOLDER_URI)
            .putString(KEY_MUSIC_FOLDER_DISPLAY, display)
            .apply();
    }

    public void clearMusicFolder() {
        sp.edit()
            .remove(KEY_MUSIC_FOLDER_URI)
            .remove(KEY_MUSIC_FOLDER_PATH)
            .remove(KEY_MUSIC_FOLDER_DISPLAY)
            .apply();
    }

    public String getPlayMode() {
        return sp.getString(KEY_PLAY_MODE, PLAY_MODE_SEQUENCE);
    }

    public void setPlayMode(String mode) {
        sp.edit().putString(KEY_PLAY_MODE, mode).apply();
    }

    public boolean isAutoPlayOnStart() {
        return sp.getBoolean(KEY_AUTO_PLAY, false);
    }

    public void setAutoPlayOnStart(boolean enabled) {
        sp.edit().putBoolean(KEY_AUTO_PLAY, enabled).apply();
    }

    /** Left/right balance, -1 = full left, 0 = center, +1 = full right. */
    public float getBalance() {
        return sp.getFloat(KEY_BALANCE, 0f);
    }

    public void setBalance(float balance) {
        float clamped = Math.max(-1f, Math.min(1f, balance));
        sp.edit().putFloat(KEY_BALANCE, clamped).apply();
    }

    public String getLastTrackUri() {
        return sp.getString(KEY_LAST_TRACK_URI, null);
    }

    public int getLastTrackPosition() {
        return sp.getInt(KEY_LAST_TRACK_POSITION, 0);
    }

    public void setLastTrack(String uri, int positionMs) {
        sp.edit()
            .putString(KEY_LAST_TRACK_URI, uri)
            .putInt(KEY_LAST_TRACK_POSITION, positionMs)
            .apply();
    }
}
