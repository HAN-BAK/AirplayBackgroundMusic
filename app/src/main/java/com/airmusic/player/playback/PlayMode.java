package com.airmusic.player.playback;

import com.airmusic.player.util.Prefs;

public enum PlayMode {
    SEQUENCE(Prefs.PLAY_MODE_SEQUENCE),
    REPEAT_ONE(Prefs.PLAY_MODE_REPEAT_ONE),
    SHUFFLE(Prefs.PLAY_MODE_SHUFFLE),
    FOLDER_LOOP(Prefs.PLAY_MODE_FOLDER_LOOP);

    public final String key;

    PlayMode(String key) {
        this.key = key;
    }

    public static PlayMode fromKey(String key) {
        for (PlayMode mode : values()) {
            if (mode.key.equals(key)) return mode;
        }
        return SEQUENCE;
    }

    public PlayMode next() {
        PlayMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
