package com.airmusic.player.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

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
    private static final String KEY_MULTICAST_DELAY_COMP = "multicast_delay_comp_ms";

    private static final String KEY_LAST_TRACK_URI = "last_track_uri";
    private static final String KEY_LAST_TRACK_POSITION = "last_track_position";

    private final Context context;
    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.context = context.getApplicationContext();
        sp = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getAirPlayName() {
        String saved = sp.getString(KEY_AIRPLAY_NAME, null);
        if (saved != null && saved.trim().length() > 0) {
            return saved.trim();
        }
        return getSystemDeviceName();
    }

    /** The device name configured in Android system settings. */
    private String getSystemDeviceName() {
        try {
            if (Build.VERSION.SDK_INT >= 25) {
                String n = Settings.Global.getString(
                        context.getContentResolver(), Settings.Global.DEVICE_NAME);
                if (n != null && n.trim().length() > 0) return n.trim();
            }
        } catch (Throwable ignored) {
        }
        return Build.MODEL;
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

    /** Receiver-side latency compensation for multi-room sync (-500..500 ms). */
    public int getMulticastDelayCompMs() {
        return sp.getInt(KEY_MULTICAST_DELAY_COMP, 0);
    }

    public void setMulticastDelayCompMs(int ms) {
        int clamped = Math.max(-500, Math.min(500, ms));
        sp.edit().putInt(KEY_MULTICAST_DELAY_COMP, clamped).apply();
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
