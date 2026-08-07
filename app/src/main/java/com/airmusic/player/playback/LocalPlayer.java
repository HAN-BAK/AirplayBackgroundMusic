package com.airmusic.player.playback;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.airmusic.player.library.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Local music playback based on ExoPlayer, supporting sequence, repeat-one,
 * shuffle and folder-loop play modes.
 *
 * <p>ExoPlayer renders through its own AudioTrack instead of MediaPlayer.
 * Some TV-box firmwares hijack MediaPlayer into a hardware player (nxplayer)
 * that downmixes audio to mono, breaking balance and volume consistency with
 * AirPlay; ExoPlayer avoids that path entirely.</p>
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
    private final BalanceAudioProcessor balanceProcessor = new BalanceAudioProcessor();

    private ExoPlayer player;
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

    private ExoPlayer ensurePlayer() {
        if (player == null) {
            player = new ExoPlayer.Builder(context, new BalanceRenderersFactory(context, balanceProcessor)).build();
            player.setVolume(masterGain);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_ENDED) {
                        onTrackCompleted();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    playing = isPlaying;
                    if (listener != null) listener.onLocalStateChanged(isPlaying);
                }

                @Override
                public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    Log.e(TAG, "ExoPlayer error: " + error);
                }
            });
        }
        return player;
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
        }
    }

    /** Sets the left/right balance (-1 = full left, 0 = center, +1 = full right). */
    public synchronized void setBalance(float balance) {
        this.balance = Math.max(-1f, Math.min(1f, balance));
        balanceProcessor.setBalance(this.balance);
    }

    public float getBalance() {
        return balance;
    }

    /** Extra master gain in [0, 1] for smooth volume transitions. */
    public synchronized void setMasterGain(float gain) {
        this.masterGain = Math.max(0f, Math.min(1f, gain));
        if (player != null) {
            player.setVolume(masterGain);
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
            player.play();
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
                return (int) player.getCurrentPosition();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public synchronized int getDuration() {
        if (player != null) {
            try {
                long d = player.getDuration();
                if (d > 0) return (int) d;
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
        ExoPlayer p = ensurePlayer();
        try {
            p.setMediaItem(MediaItem.fromUri(track.uri));
            p.prepare();
            if (positionMs > 0) p.seekTo(positionMs);
        } catch (Exception e) {
            Log.e(TAG, "Cannot play " + track.uri, e);
        }
    }

    private void onTrackCompleted() {
        int next = nextIndex(true);
        if (next < 0) {
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
                player.play();
                playing = true;
            }
        }
        if (listener != null) listener.onLocalTrackChanged(getCurrentTrack());
    }

    private void releasePlayer() {
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
