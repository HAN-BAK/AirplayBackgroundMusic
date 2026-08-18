package com.airmusic.player.playback;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
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
 *
 * <p>Every track gets a fresh ExoPlayer (decoder + AudioTrack + processor
 * chain), so no state leaks from one song into the next. Local song switches
 * (next/prev/library) use a short non-crossfade: the old song fades out over
 * 1000 ms, the player switches, then the new song fades in over 1000 ms. The
 * fade is skipped while multi-room is active (the stream owns the output).</p>
 */
public class LocalPlayer {

    private static final String TAG = "LocalPlayer";
    /** 100 steps of 10 ms = 1000 ms per fade half. */
    private static final int FADE_STEPS = 100;
    private static final long FADE_STEP_MS = 10;

    public interface Listener {
        void onLocalTrackChanged(Track track);

        void onLocalStateChanged(boolean playing);

        void onLocalPlaybackEnded();
    }

    /** Lets the owner (PlaybackService) opt out of the switch fade, e.g. while
     *  multi-room is broadcasting and the stream owns the audible output. */
    public interface FadeGate {
        boolean shouldFadeOnSwitch();
    }

    private final Context context;
    private final Listener listener;
    private final Handler fadeHandler = new Handler(Looper.getMainLooper());
    private final BalanceAudioProcessor balanceProcessor = new BalanceAudioProcessor();
    private final AudioProcessor[] preProcessors;

    private FadeGate fadeGate;
    private Runnable switchFadeRunnable;
    private List<Track> pendingPlaylist;
    private int pendingIndex = -1;
    /** Index the next queued next/prev tap should advance from. */
    private int queuedBaseIndex = -1;
    private boolean fadeBusy;
    private boolean fadeOutPhase;
    private int fadeStep;
    private float fadeStartGain = 1f;
    /** Set when a song ends while a user-initiated switch fade is running. */
    private boolean autoAdvancePending;

    private ExoPlayer player;
    private List<Track> playlist = new ArrayList<>();
    private List<Integer> shuffleOrder = new ArrayList<>();
    private int currentIndex = -1;
    private PlayMode mode = PlayMode.SEQUENCE;
    private boolean playing;
    private final Random random = new Random();
    private float balance;
    private float masterGain = 1f;

    public LocalPlayer(Context context, Listener listener, AudioProcessor[] preProcessors) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.preProcessors = preProcessors != null ? preProcessors : new AudioProcessor[0];
    }

    /** Sets the gate that decides whether song switches use the fade. */
    public synchronized void setFadeGate(FadeGate gate) {
        this.fadeGate = gate;
    }

    private boolean shouldFade() {
        return fadeGate == null || fadeGate.shouldFadeOnSwitch();
    }

    private ExoPlayer ensurePlayer() {
        if (player == null) {
            AudioProcessor[] all = new AudioProcessor[preProcessors.length + 1];
            System.arraycopy(preProcessors, 0, all, 0, preProcessors.length);
            all[preProcessors.length] = balanceProcessor;
            player = new ExoPlayer.Builder(context, new BalanceRenderersFactory(context, all)).build();
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

    /** @return true if a switch fade was started (caller must not play/metadata yet). */
    public synchronized boolean setPlaylist(List<Track> tracks, int startIndex) {
        int idx = startIndex >= 0 && startIndex < tracks.size()
                ? startIndex : (tracks.isEmpty() ? -1 : 0);
        if (idx >= 0 && playing && player != null && shouldFade()) {
            // Library switch while playing: fade the old song out first.
            requestFadeSwitch(new ArrayList<>(tracks), idx);
            return true;
        } else {
            playlist = new ArrayList<>(tracks);
            currentIndex = idx;
            buildShuffleOrder();
            if (currentIndex >= 0) {
                openCurrentTrack(0);
            }
            return false;
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
        cancelSwitchFade();
        if (player != null && playing) {
            player.pause();
        }
        playing = false;
        if (listener != null) listener.onLocalStateChanged(false);
    }

    public synchronized void stop() {
        cancelSwitchFade();
        playing = false;
        releasePlayer();
        if (listener != null) listener.onLocalStateChanged(false);
    }

    public synchronized void next() {
        if (playlist.isEmpty()) return;
        int base = queuedBaseIndex >= 0 ? queuedBaseIndex : currentIndex;
        int nextIndex = nextIndexFrom(base, true);
        if (nextIndex < 0) {
            stop();
            return;
        }
        if (playing && player != null && shouldFade()) {
            queuedBaseIndex = nextIndex;
            requestFadeSwitch(null, nextIndex);
        } else {
            switchTrack(nextIndex);
        }
    }

    public synchronized void previous() {
        if (playlist.isEmpty()) return;
        int base = queuedBaseIndex >= 0 ? queuedBaseIndex : currentIndex;
        int prevIndex;
        if (mode == PlayMode.REPEAT_ONE) {
            prevIndex = base;
        } else if (mode == PlayMode.SHUFFLE && shuffleOrder.size() > 1) {
            int pos = shuffleOrder.indexOf(base);
            pos = (pos - 1 + shuffleOrder.size()) % shuffleOrder.size();
            prevIndex = shuffleOrder.get(pos);
        } else {
            prevIndex = base - 1;
            if (prevIndex < 0) {
                prevIndex = (mode == PlayMode.FOLDER_LOOP) ? playlist.size() - 1 : 0;
            }
        }
        if (playing && player != null && shouldFade()) {
            queuedBaseIndex = prevIndex;
            requestFadeSwitch(null, prevIndex);
        } else {
            switchTrack(prevIndex);
        }
    }

    /** Switches instantly to {@code newIndex}. */
    private void switchTrack(int newIndex) {
        if (newIndex < 0 || newIndex >= playlist.size()) return;
        if (playing && player != null && shouldFade()) {
            requestFadeSwitch(null, newIndex);
            return;
        }
        // Not playing (e.g. paused after a fade-out): switch and start the
        // new song at the normal volume instead of staying silent at 0.
        setMasterGain(1f);
        currentIndex = newIndex;
        openCurrentTrack(0);
        play();
        if (listener != null) listener.onLocalTrackChanged(getCurrentTrack());
    }

    /**
     * Requests a non-crossfade song switch. Rapid taps coalesce: only the
     * latest target is played, so tapping next five times quickly goes
     * straight to the fifth song instead of playing every song in between.
     * The current song fades out over 1000 ms, the player switches, then the
     * new song fades in over 1000 ms; the fade is never cut in half.
     */
    private void requestFadeSwitch(List<Track> newPlaylist, int newIndex) {
        if (newPlaylist == null && newIndex < 0) return;
        if (newPlaylist != null && (newIndex < 0 || newIndex >= newPlaylist.size())) return;
        // Latest tap wins: replace the pending target.
        pendingPlaylist = newPlaylist != null ? new ArrayList<>(newPlaylist) : null;
        pendingIndex = newIndex;
        Log.i(TAG, "requestFadeSwitch -> index=" + newIndex
                + " fadeBusy=" + fadeBusy + " masterGain=" + masterGain);
        // Immediate feedback: the UI metadata moves to the tapped song right
        // away, even while the audio transition is still running.
        if (listener != null) {
            List<Track> src = newPlaylist != null ? newPlaylist : playlist;
            if (newIndex >= 0 && newIndex < src.size()) {
                listener.onLocalTrackChanged(src.get(newIndex));
            }
        }
        if (!fadeBusy) {
            startFadeSequence();
        }
    }

    /** Starts the fade-out phase of a switch transition. */
    private void startFadeSequence() {
        fadeBusy = true;
        fadeOutPhase = true;
        fadeStep = 0;
        fadeStartGain = getMasterGain();
        Log.i(TAG, "startFadeSequence out startGain=" + fadeStartGain);
        switchFadeRunnable = new Runnable() {
            @Override
            public void run() {
                stepFade();
            }
        };
        fadeHandler.post(switchFadeRunnable);
    }

    private void stepFade() {
        fadeStep++;
        if (fadeOutPhase) {
            float v = fadeStartGain * (1f - fadeStep / (float) FADE_STEPS);
            setMasterGain(Math.max(0f, v));
            if (fadeStep >= FADE_STEPS) {
                setMasterGain(0f);
                Log.i(TAG, "fade-out done -> performPendingSwitch pending=" + pendingIndex);
                performPendingSwitch();
                fadeOutPhase = false;
                fadeStep = 0;
                fadeHandler.postDelayed(switchFadeRunnable, FADE_STEP_MS);
                return;
            }
        } else {
            float v = fadeStep / (float) FADE_STEPS;
            setMasterGain(Math.min(1f, v));
            if (fadeStep >= FADE_STEPS) {
                setMasterGain(1f);
                Log.i(TAG, "fade-in done current=" + currentIndex
                        + " pending=" + pendingIndex
                        + " autoAdvancePending=" + autoAdvancePending);
                if (pendingPlaylist != null
                        || (pendingIndex >= 0 && pendingIndex != currentIndex)) {
                    // A newer tap arrived while fading in: switch again.
                    fadeOutPhase = true;
                    fadeStep = 0;
                    fadeStartGain = getMasterGain();
                    fadeHandler.postDelayed(switchFadeRunnable, FADE_STEP_MS);
                    return;
                }
                fadeBusy = false;
                switchFadeRunnable = null;
                pendingIndex = -1;
                pendingPlaylist = null;
                queuedBaseIndex = -1;
                if (autoAdvancePending) {
                    // A song ended while the fade was running; advance now.
                    autoAdvancePending = false;
                    autoAdvance();
                }
                return;
            }
        }
        fadeHandler.postDelayed(switchFadeRunnable, FADE_STEP_MS);
    }

    /** Applies the pending playlist/index after the fade-out completed.
     *  {@code pendingIndex} is kept so the fade-in can detect newer taps. */
    private void performPendingSwitch() {
        Log.i(TAG, "performPendingSwitch -> current=" + pendingIndex
                + " (was " + currentIndex + ")");
        if (pendingPlaylist != null) {
            playlist = pendingPlaylist;
            pendingPlaylist = null;
            buildShuffleOrder();
        }
        if (pendingIndex >= 0 && pendingIndex < playlist.size()) {
            currentIndex = pendingIndex;
        }
        openCurrentTrack(0);
        play();
        // Publish the track again AFTER the switch: the tap-time metadata
        // load usually finishes before the fade-out ends, so the async cover
        // read would be discarded by the "current track" guard. Firing it
        // here gives the cover a load with correct timing.
        if (listener != null) {
            listener.onLocalTrackChanged(getCurrentTrack());
        }
    }

    /** Cancels any in-flight song-switch fade (used by pause/stop and the
     *  service when it takes over the volume for AirPlay/multi-room). */
    public void cancelSwitchFade() {
        Log.i(TAG, "cancelSwitchFade");
        if (switchFadeRunnable != null) {
            fadeHandler.removeCallbacks(switchFadeRunnable);
            switchFadeRunnable = null;
        }
        fadeBusy = false;
        pendingIndex = -1;
        pendingPlaylist = null;
        queuedBaseIndex = -1;
        autoAdvancePending = false;
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

    private int nextIndexFrom(int base, boolean forward) {
        if (playlist.isEmpty()) return -1;
        if (mode == PlayMode.SHUFFLE && shuffleOrder.size() > 1) {
            int pos = shuffleOrder.indexOf(base);
            if (forward && pos == shuffleOrder.size() - 1) {
                // One full shuffled round finished: reshuffle so the next
                // round has a fresh order instead of repeating the same one.
                buildShuffleOrder();
                pos = shuffleOrder.indexOf(base);
            }
            int nextPos = forward ? (pos + 1) % shuffleOrder.size() : (pos - 1 + shuffleOrder.size()) % shuffleOrder.size();
            return shuffleOrder.get(nextPos);
        }
        if (mode == PlayMode.REPEAT_ONE) {
            return base;
        }
        int next = forward ? base + 1 : base - 1;
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
        p.setVolume(masterGain);
        try {
            p.setMediaItem(MediaItem.fromUri(track.uri));
            p.prepare();
            if (positionMs > 0) p.seekTo(positionMs);
        } catch (Exception e) {
            Log.e(TAG, "Cannot play " + track.uri, e);
        }
    }

    private void onTrackCompleted() {
        Log.i(TAG, "onTrackCompleted fadeBusy=" + fadeBusy);
        if (fadeBusy) {
            // A user-initiated switch fade is running; the song ended
            // underneath it. Defer the natural advance until the fade
            // finishes, otherwise the two transitions fight and the track
            // can bounce back and forth.
            autoAdvancePending = true;
            return;
        }
        autoAdvance();
    }

    /** Advances to the next song after the previous one ended naturally. */
    private void autoAdvance() {
        Log.i(TAG, "autoAdvance from=" + currentIndex);
        int next = nextIndexFrom(currentIndex, true);
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
            if (shouldFade()) {
                // The old song already ended; just fade the new one in.
                setMasterGain(0f);
            }
            openCurrentTrack(0);
            if (player != null) {
                player.play();
                playing = true;
            }
        }
        if (listener != null) listener.onLocalTrackChanged(getCurrentTrack());
        if (shouldFade()) {
            fadeInFromZero();
        }
    }

    /** Fades the master gain from 0 to 1 over 1000 ms. */
    private void fadeInFromZero() {
        cancelSwitchFade();
        fadeBusy = true;
        fadeOutPhase = false;
        fadeStep = 0;
        switchFadeRunnable = new Runnable() {
            @Override
            public void run() {
                stepFade();
            }
        };
        fadeHandler.post(switchFadeRunnable);
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
        cancelSwitchFade();
        playing = false;
        releasePlayer();
        playlist.clear();
        currentIndex = -1;
    }
}
