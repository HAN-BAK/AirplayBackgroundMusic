package com.airmusic.player.util;

import android.graphics.Bitmap;

import com.airmusic.player.playback.PlayMode;

/**
 * Immutable-ish snapshot of everything the UI shows.
 */
public class PlayerUiState {

    public enum Source { IDLE, LOCAL, AIRPLAY, REMOTE }

    public Source source = Source.IDLE;
    public boolean playing;
    public boolean airPlayPaused;
    public boolean receiverPausedByUser;
    public String clientName = "";
    public String title = "未在播放";
    public String artist = "";
    public String album = "";
    public Bitmap art;
    public int positionMs;
    public int durationMs;
    public PlayMode mode = PlayMode.SEQUENCE;
    public String statusText = "";

    public PlayerUiState copy() {
        PlayerUiState s = new PlayerUiState();
        s.source = source;
        s.playing = playing;
        s.airPlayPaused = airPlayPaused;
        s.receiverPausedByUser = receiverPausedByUser;
        s.clientName = clientName;
        s.title = title;
        s.artist = artist;
        s.album = album;
        s.art = art;
        s.positionMs = positionMs;
        s.durationMs = durationMs;
        s.mode = mode;
        s.statusText = statusText;
        return s;
    }
}
