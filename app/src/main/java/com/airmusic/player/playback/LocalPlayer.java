package com.airmusic.player.playback;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.airmusic.player.library.Track;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Local music playback based on MediaPlayer, supporting sequence, repeat-one,
 * shuffle and folder-loop play modes.
 */
public class LocalPlayer {

    private static final String TAG = "LocalPlayer";

    public interface Listener {
        void onLocalTrackChanged(Track track);

        void onLocalStateChanged(boolean playing);

        void onLocalPlaybackEnded();
    }

    private final Context context;
    private final Listener listener;

    private MediaPlayer player;
    private ParcelFileDescriptor openPfd;
    private List<Track> playlist = new ArrayList<>();
    private List<Integer> shuffleOrder = new ArrayList<>();
    private int currentIndex = -1;
    private PlayMode mode = PlayMode.SEQUENCE;
    private boolean playing;
    private final Random random = new Random();
    private float balance;
    private float masterGain = 1f;

    public LocalPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized void setPlaylist(List<Track> tracks, int startIndex) {
        releasePlayer();
        playlist = new ArrayList<>(tracks);
        currentIndex = startIndex >= 0 && startIndex < playlist.size() ? startIndex : (playlist.isEmpty() ? -1 : 0);
        buildShuffleOrder();
        if (currentIndex >= 0) {
            openCurrentTrack(0);
        }
    }

    public synchronized void setMode(PlayMode mode) {
        this.mode = mode;
        if (mode == PlayMode.SHUFFLE) {
            buildShuffleOrder();
            if (currentIndex >= 0) {
                for (int i = 0; i < shuffleOrder.size(); i++) {
                    if (shuffleOrder.get(i) == currentIndex) {
                        // rotate so current track stays at the current shuffle position
                        break;
                    }
                }
            }
        }
    }

    /** Sets the left/right balance (-1 = full left, 0 = center, +1 = full right). */
    public synchronized void setBalance(float balance) {
        this.balance = Math.max(-1f, Math.min(1f, balance));
        if (player != null) {
            player.setVolume(panLeft() * masterGain, panRight() * masterGain);
        }
    }

    public float getBalance() {
        return balance;
    }

    private float panLeft() {
        return balance <= 0f ? 1f : 1f - balance;
    }

    private float panRight() {
        return balance >= 0f ? 1f : 1f + balance;
    }

    /** Extra master gain in [0, 1] for smooth volume transitions. */
    public synchronized void setMasterGain(float gain) {
        this.masterGain = Math.max(0f, Math.min(1f, gain));
        if (player != null) {
            player.setVolume(panLeft() * masterGain, panRight() * masterGain);
        }
    }

    public float getMasterGain() {
        return masterGain;
    }

    public PlayMode getMode() {
        return mode;
    }

    public synchronized List<Track> getPlaylist() {
        return new ArrayList<>(playlist);
    }

    public synchronized Track getCurrentTrack() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) return null;
        return playlist.get(currentIndex);
    }

    public synchronized boolean isPlaying() {
        return playing && player != null;
    }

    public synchronized void play() {
        if (player == null && currentIndex >= 0) {
            openCurrentTrack(0);
        }
        if (player != null) {
            player.start();
            playing = true;
            if (listener != null) listener.onLocalStateChanged(true);
        }
    }

    public synchronized void pause() {
        if (player != null && playing) {
            player.pause();
        }
        playing = false;
        if (listener != null) listener.onLocalStateChanged(false);
    }

    public synchronized void stop() {
        playing = false;
        releasePlayer();
        if (listener != null) listener.onLocalStateChanged(false);
    }

    public synchronized void next() {
        if (playlist.isEmpty()) return;
        int nextIndex = nextIndex(true);
        if (nextIndex < 0) {
            stop();
            return;
        }
        currentIndex = nextIndex;
        openCurrentTrack(0);
        if (listener != null) listener.onLocalTrackChanged(getCurrentTrack());
        play();
    }

    public synchronized void previous() {
        if (playlist.isEmpty()) return;
        int prevIndex;
        if (mode == PlayMode.SHUFFLE && shuffleOrder.size() > 1) {
            int pos = shuffleOrder.indexOf(currentIndex);
            pos = (pos - 1 + shuffleOrder.size()) % shuffleOrder.size();
            prevIndex = shuffleOrder.get(pos);
        } else {
            prevIndex = currentIndex - 1;
            if (prevIndex < 0) {
                prevIndex = (mode == PlayMode.FOLDER_LOOP || mode == PlayMode.REPEAT_ONE)
                        ? playlist.size() - 1 : 0;
            }
        }
        currentIndex = prevIndex;
        openCurrentTrack(0);
        if (listener != null) listener.onLocalTrackChanged(getCurrentTrack());
        play();
    }

    public synchronized void seekTo(int positionMs) {
        if (player != null) {
            try {
                player.seekTo(positionMs);
            } catch (Exception e) {
                Log.w(TAG, "seek failed", e);
            }
        }
    }

    public synchronized int getPosition() {
        if (player != null) {
            try {
                return player.getCurrentPosition();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public synchronized int getDuration() {
        if (player != null) {
            try {
                return player.getDuration();
            } catch (Exception e) {
                return 0;
            }
        }
        Track track = getCurrentTrack();
        return track != null ? (int) track.durationMs : 0;
    }

    private int nextIndex(boolean forward) {
        if (playlist.isEmpty()) return -1;
        if (mode == PlayMode.SHUFFLE && shuffleOrder.size() > 1) {
            int pos = shuffleOrder.indexOf(currentIndex);
            int nextPos = forward ? (pos + 1) % shuffleOrder.size() : (pos - 1 + shuffleOrder.size()) % shuffleOrder.size();
            return shuffleOrder.get(nextPos);
        }
        if (mode == PlayMode.REPEAT_ONE) {
            return currentIndex;
        }
        int next = forward ? currentIndex + 1 : currentIndex - 1;
        if (next >= playlist.size()) {
            return mode == PlayMode.FOLDER_LOOP ? 0 : -1;
        }
        if (next < 0) {
            return mode == PlayMode.FOLDER_LOOP ? playlist.size() - 1 : 0;
        }
        return next;
    }

    private void buildShuffleOrder() {
        shuffleOrder = new ArrayList<>();
        for (int i = 0; i < playlist.size(); i++) shuffleOrder.add(i);
        Collections.shuffle(shuffleOrder, random);
    }

    private void openCurrentTrack(int positionMs) {
        releasePlayer();
        if (currentIndex < 0 || currentIndex >= playlist.size()) return;
        Track track = playlist.get(currentIndex);
        MediaPlayer mp = new MediaPlayer();
        try {
            ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(track.uri, "r");
            if (pfd == null) {
                Log.e(TAG, "Cannot open " + track.uri);
                return;
            }
            mp.setDataSource(pfd.getFileDescriptor());
            openPfd = pfd;
            mp.setOnCompletionListener(mp1 -> onTrackCompleted());
            mp.setOnErrorListener((mp1, what, extra) -> {
                Log.e(TAG, "MediaPlayer error " + what + "/" + extra);
                return false;
            });
            mp.prepare();
            mp.setVolume(panLeft() * masterGain, panRight() * masterGain);
            if (positionMs > 0) mp.seekTo(positionMs);
            player = mp;
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "Cannot play " + track.uri, e);
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void onTrackCompleted() {
        int next = nextIndex(true);
        if (next < 0) {
            // sequence ended
            synchronized (this) {
                playing = false;
                releasePlayer();
            }
            if (listener != null) listener.onLocalPlaybackEnded();
            if (listener != null) listener.onLocalStateChanged(false);
            return;
        }
        synchronized (this) {
            currentIndex = next;
            openCurrentTrack(0);
            if (player != null) {
                player.start();
                playing = true;
            }
        }
        if (listener != null) listener.onLocalTrackChanged(getCurrentTrack());
    }

    private void releasePlayer() {
        if (openPfd != null) {
            try {
                openPfd.close();
            } catch (IOException ignored) {
            }
            openPfd = null;
        }
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    public synchronized void release() {
        playing = false;
        releasePlayer();
        playlist.clear();
        currentIndex = -1;
    }
}
