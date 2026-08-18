package com.airmusic.player.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final String KEY_SHOW_APPS_BUTTON = "show_apps_button";
    private static final String KEY_EQ_GAINS = "eq_gains";
    private static final String KEY_EQ_PRESETS = "eq_presets";

    private static final int EQ_BANDS = 10;

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

    /** Whether the apps-list button is shown on the main playback screen. */
    public boolean isShowAppsButton() {
        return sp.getBoolean(KEY_SHOW_APPS_BUTTON, true);
    }

    public void setShowAppsButton(boolean enabled) {
        sp.edit().putBoolean(KEY_SHOW_APPS_BUTTON, enabled).apply();
    }

    // ------------------------------------------------------------------
    // Equalizer
    // ------------------------------------------------------------------

    /** Current 10-band gains in dB (default all zero). */
    public double[] getEqGains() {
        double[] gains = new double[EQ_BANDS];
        String s = sp.getString(KEY_EQ_GAINS, null);
        if (s != null) {
            try {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < EQ_BANDS && i < a.length(); i++) {
                    gains[i] = a.optDouble(i, 0);
                }
            } catch (Throwable ignored) {
            }
        }
        return gains;
    }

    public void setEqGains(double[] gains) {
        try {
            JSONArray a = new JSONArray();
            for (int i = 0; i < EQ_BANDS; i++) {
                a.put(gains != null && i < gains.length ? gains[i] : 0);
            }
            sp.edit().putString(KEY_EQ_GAINS, a.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public java.util.List<String> getEqPresetNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                names.add(a.getJSONObject(i).optString("name", ""));
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    public double[] getEqPresetGains(String name) {
        double[] gains = new double[EQ_BANDS];
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (name.equals(o.optString("name"))) {
                    JSONArray g = o.optJSONArray("gains");
                    if (g != null) {
                        for (int j = 0; j < EQ_BANDS && j < g.length(); j++) {
                            gains[j] = g.optDouble(j, 0);
                        }
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return gains;
    }

    /** Adds a preset; returns false when the name already exists. */
    public boolean addEqPreset(String name, double[] gains) {
        if (name == null || name.trim().length() == 0) return false;
        name = name.trim();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                if (name.equals(a.getJSONObject(i).optString("name"))) return false;
            }
            JSONObject o = new JSONObject();
            o.put("name", name);
            JSONArray g = new JSONArray();
            for (int i = 0; i < EQ_BANDS; i++) {
                g.put(gains != null && i < gains.length ? gains[i] : 0);
            }
            o.put("gains", g);
            a.put(o);
            sp.edit().putString(KEY_EQ_PRESETS, a.toString()).apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void removeEqPreset(String name) {
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            JSONArray b = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                if (!name.equals(a.getJSONObject(i).optString("name"))) {
                    b.put(a.getJSONObject(i));
                }
            }
            sp.edit().putString(KEY_EQ_PRESETS, b.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Exports all presets as a JSON string (for file sharing). */
    public String exportEqPresets() {
        return sp.getString(KEY_EQ_PRESETS, "[]");
    }

    /** Imports presets, replacing same-name entries. Returns count added. */
    public int importEqPresets(String json) {
        int added = 0;
        try {
            JSONArray incoming = new JSONArray(json);
            JSONArray current = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject o = incoming.getJSONObject(i);
                String name = o.optString("name", "");
                if (name.length() == 0) continue;
                JSONArray b = new JSONArray();
                boolean replaced = false;
                for (int j = 0; j < current.length(); j++) {
                    JSONObject cur = current.getJSONObject(j);
                    if (name.equals(cur.optString("name"))) {
                        b.put(o);
                        replaced = true;
                    } else {
                        b.put(cur);
                    }
                }
                if (!replaced) b.put(o);
                current = b;
                added++;
            }
            sp.edit().putString(KEY_EQ_PRESETS, current.toString()).apply();
        } catch (Throwable ignored) {
        }
        return added;
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
