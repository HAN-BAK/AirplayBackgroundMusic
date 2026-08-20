package com.airmusic.player.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.audio.AudioProcessor;

import com.airmusic.player.MainActivity;
import com.airmusic.player.R;
import com.airmusic.player.airplay.AirPlayController;
import com.airmusic.player.airplay.DacpClient;
import com.airmusic.player.library.MusicLibrary;
import com.airmusic.player.library.Track;
import com.airmusic.player.multicast.MultiRoomDiscovery;
import com.airmusic.player.multicast.MultiRoomAudioPlayer;
import com.airmusic.player.multicast.MultiRoomManager;
import com.airmusic.player.multicast.MultiRoomStreamer;
import com.airmusic.player.playback.LocalPlayer;
import com.airmusic.player.playback.PlayMode;
import com.airmusic.player.playback.EqAudioProcessor;
import com.airmusic.player.playback.FirEqualizer;
import com.airmusic.player.receiver.UsbMediaReceiver;
import com.airmusic.player.util.PlayerUiState;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.StateBus;
import com.airmusic.player.util.DiagnosticLog;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that owns both playback paths (local and AirPlay),
 * applies the source-switching rules and shows the media notification.
 */
public class PlaybackService extends Service {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.airmusic.player.util.LocaleHelper.attach(newBase));
    }

    private static final String TAG = "PlaybackService";

    public static final String ACTION_START = "com.airmusic.player.START";
    public static final String ACTION_PLAY = "com.airmusic.player.PLAY";
    public static final String ACTION_PAUSE = "com.airmusic.player.PAUSE";
    public static final String ACTION_TOGGLE = "com.airmusic.player.TOGGLE";
    public static final String ACTION_NEXT = "com.airmusic.player.NEXT";
    public static final String ACTION_PREV = "com.airmusic.player.PREV";
    public static final String ACTION_STOP_SERVICE = "com.airmusic.player.STOP_SERVICE";
    public static final String ACTION_RESCAN = "com.airmusic.player.RESCAN";
    public static final String ACTION_RESTART_AIRPLAY = "com.airmusic.player.RESTART_AIRPLAY";
    public static final String ACTION_PLAY_TRACK = "com.airmusic.player.PLAY_TRACK";
    public static final String EXTRA_TRACK_URI = "track_uri";

    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1001;
    /** If no AirPlay audio packet arrives within this window, treat it as a phone-side pause. */
    private static final long AIRPLAY_STALL_TIMEOUT_MS = 3500;
    private static final long AIRPLAY_POLL_INTERVAL_MS = 2000;

    private static PlaybackService instance;

    public static PlaybackService getInstance() {
        return instance;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private AirPlayController airPlayController;
    private DacpClient dacpClient;
    private LocalPlayer localPlayer;
    private FirEqualizer equalizer;
    private FirEqualizer airEqualizer;
    private FirEqualizer multiRoomEqualizer;
    private EqAudioProcessor eqProcessor;
    private MultiRoomManager multiRoomManager;
    private MultiRoomStreamer multiRoomStreamer;
    private MultiRoomAudioPlayer multiRoomAudioPlayer;
    /** Last embedded cover art bytes for the current local track. */
    private byte[] lastArtBytes;
    /** While true, multi-room streamer syncs are deferred (during a seek). */
    private boolean suppressStreamerSync;
    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;
    private UsbMediaReceiver usbMediaReceiver;

    private List<Track> tracks = new java.util.ArrayList<>();
    private PlayerUiState state = new PlayerUiState();

    // Source-switching state machine
    private boolean resumeLocalAfterAirPlay;
    private boolean airPlaySessionActive;
    private boolean airPlayUserPaused;
    /** True after the audio watchdog declared a phone-side pause. */
    private boolean airPlayWatchdogPaused;
    /** Restore local / AirPlay playback after the multi-room session ends. */
    private boolean resumeLocalAfterMultiRoom;
    private boolean resumeAirPlayAfterMultiRoom;
    /** AirPlay interrupted multi-room; restore multi-room when AirPlay ends. */
    private boolean resumeMultiRoomAfterAirPlay;
    /** Last known AirPlay metadata, restored after a multi-room session. */
    private String airMetaTitle = "";
    private String airMetaArtist = "";
    private String airMetaAlbum = "";
    private Bitmap airMetaArt;
    private long airMetaDurationMs;
    /** Last known multi-room metadata, restored after an AirPlay interruption. */
    private String multiRoomMetaTitle = "";
    private String multiRoomMetaArtist = "";
    private String multiRoomMetaAlbum = "";
    private Bitmap multiRoomMetaArt;
    private long multiRoomMetaDurationMs;
    private Runnable multiRoomFadeRunnable;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (state.source == PlayerUiState.Source.LOCAL && state.playing) {
                long now = System.currentTimeMillis();
                long pos = localPlayer.getPosition();
                if (lastTickerWall > 0 && lastTickerPos >= 0) {
                    long wallDelta = now - lastTickerWall;
                    long posDelta = pos - lastTickerPos;
                    if (wallDelta > 200 && wallDelta < 1500 && posDelta >= 0
                            && posDelta < wallDelta - 180) {
                        Log.w(TAG, "AUDIO STALL posDelta=" + posDelta
                                + "ms wallDelta=" + wallDelta + "ms pos=" + pos);
                        DiagnosticLog.w(TAG, "AUDIO STALL posDelta=" + posDelta
                                + "ms wallDelta=" + wallDelta + "ms pos=" + pos);
                    }
                }
                lastTickerWall = now;
                lastTickerPos = pos;
                state.positionMs = (int) pos;
                int duration = localPlayer.getDuration();
                if (duration > 0) state.durationMs = duration;
                StateBus.get().postState(state);
                if (now - lastTrackSaveTime > 5000) {
                    saveLastTrack();
                    lastTrackSaveTime = now;
                }
            }
            main.postDelayed(this, 500);
        }
    };

    private long lastTrackSaveTime;
    private long lastTickerWall;
    private long lastTickerPos = -1;

    /**
     * Keeps the app in sync with phone-side play/pause:
     * <ul>
     * <li>Audio-packet watchdog: iOS stops sending RTP audio when paused
     * (without always sending an RTSP FLUSH), so a silent gap means paused.</li>
     * <li>DACP playstatus polling as an additional signal when available.</li>
     * </ul>
     */
    private final Runnable airPlayStatusPoller = new Runnable() {
        @Override
        public void run() {
            if (!airPlaySessionActive) {
                return;
            }
            checkAirPlayAudioWatchdog();
            if (dacpClient != null) {
                dacpClient.playStatus(status -> main.post(() -> applyAirPlayStatus(status)));
            }
            main.postDelayed(this, AIRPLAY_POLL_INTERVAL_MS);
        }
    };

    /**
     * Watches the AirPlay audio stream:
     * <ul>
     * <li>If the sender stops delivering audio packets for longer than
     * {@link #AIRPLAY_STALL_TIMEOUT_MS}, mirror the phone-side pause.</li>
     * <li>If audio starts flowing again after a watchdog pause, mirror the
     * phone-side resume (the engine only fires a resume event when an RTSP
     * FLUSH preceded the pause, which iOS does not always send).</li>
     * </ul>
     */
    private void checkAirPlayAudioWatchdog() {
        if (!airPlaySessionActive) return;
        long lastPacket = airPlayController == null ? 0L : airPlayController.getLastAudioPacketTime();
        long now = System.currentTimeMillis();

        if (lastPacket > 0 && now - lastPacket > AIRPLAY_STALL_TIMEOUT_MS) {
            if (!airPlayWatchdogPaused
                    && state.source == PlayerUiState.Source.AIRPLAY
                    && state.playing
                    && !state.receiverPausedByUser) {
                Log.i(TAG, "AirPlay audio stalled " + (now - lastPacket)
                        + "ms, treating as phone-side pause");
                DiagnosticLog.i(TAG, "AirPlay audio stalled " + (now - lastPacket)
                        + "ms, treating as phone-side pause");
                airPlayWatchdogPaused = true;
                handleAirPlayPause();
            }
            return;
        }

        if (airPlayWatchdogPaused) {
            Log.i(TAG, "AirPlay audio flowing again, phone resumed");
            DiagnosticLog.i(TAG, "AirPlay audio flowing again, phone resumed");
            airPlayWatchdogPaused = false;
            if (state.source == PlayerUiState.Source.AIRPLAY && !state.playing) {
                handleAirPlayStart(state.clientName, "", "", "", false);
            } else if (state.source == PlayerUiState.Source.LOCAL) {
                handleAirPlayStart(state.clientName, "", "", "", false);
            }
        }
    }

    // Smooth volume transitions when switching between local and AirPlay
    private float localFade = 1f;
    private Runnable fadeRunnable;
    private Runnable airFadeRunnable;

    private final LocalPlayer.Listener localListener = new LocalPlayer.Listener() {
        @Override
        public void onLocalTrackChanged(Track track) {
            loadMetadata(track);
            if (multiRoomManager != null && multiRoomManager.hasTargets()) {
                multiRoomManager.sendMeta(
                        track.displayTitle(), track.displayArtist(), track.displayAlbum(), track.durationMs);
            }
            syncMultiRoomStreamer();
            scheduleMultiRoomCalibration();
        }

        @Override
        public void onLocalStateChanged(boolean playing) {
			if (state.source == PlayerUiState.Source.LOCAL) {
				state.playing = playing;
				publish();
				saveLastTrack();
			}
            if (multiRoomManager != null && multiRoomManager.hasTargets()) {
                if (playing) {
                    multiRoomManager.sendPlay(localPlayer.getPosition());
                } else {
                    multiRoomManager.sendFlush();
                    multiRoomManager.sendPause();
                }
                syncMultiRoomStreamer();
            }
            if (playing) {
                scheduleMultiRoomCalibration();
            }
        }

        @Override
        public void onLocalPlaybackEnded() {
            if (state.source == PlayerUiState.Source.LOCAL) {
				state.source = PlayerUiState.Source.IDLE;
				state.playing = false;
				publish();
			}
            if (multiRoomManager != null && multiRoomManager.hasTargets()) {
                multiRoomManager.sendStop();
            }
            stopMultiRoomStreamer();
        }
    };

    /**
     * Receiver side of multi-room sync: a master device pushes metadata,
     * cover art and play state; this device mirrors them in the UI. PCM audio
     * streaming arrives in the next phase.
     */
    private void pushMultiRoomState() {
        MultiRoomManager m = multiRoomManager;
        if (m == null || !m.hasTargets()) return;
        m.sendMeta(state.title, state.artist, state.album, state.durationMs);
        if (lastArtBytes != null && lastArtBytes.length > 0) {
            m.sendArt(lastArtBytes);
        }
        if (state.source == PlayerUiState.Source.LOCAL && state.playing) {
            m.sendPlay(localPlayer.getPosition());
        } else if (state.source == PlayerUiState.Source.LOCAL) {
            m.sendPause();
        }
        syncMultiRoomStreamer();
    }

    /**
     * Receiver side: a master started streaming to this device. Pause any
     * local or AirPlay playback so multi-room audio is the only source; the
     * flags below tell {@link #exitMultiRoomRemoteMode()} what to restore.
     */
    private void enterMultiRoomRemoteMode() {
        if (state.source == PlayerUiState.Source.REMOTE) return;
        Log.i(TAG, "enter multi-room remote, current source=" + state.source);
        if (state.source == PlayerUiState.Source.LOCAL && localPlayer.isPlaying()) {
            resumeLocalAfterMultiRoom = true;
            resumeAirPlayAfterMultiRoom = false;
            fadeLocalOut(localPlayer::pause);
        } else if (state.source == PlayerUiState.Source.AIRPLAY && state.playing) {
            resumeAirPlayAfterMultiRoom = true;
            resumeLocalAfterMultiRoom = false;
            // Silencing the receiver output keeps the iOS session alive:
            // a DACP pause makes iOS tear down the session, so it could not
            // be restored after multi-room ends.
            airPlayUserPaused = false;
            airPlayController.setVolumeGain(0f);
            airPlayController.pauseReceiverOutput();
        } else {
            resumeLocalAfterMultiRoom = false;
            resumeAirPlayAfterMultiRoom = false;
        }
    }

    /** Receiver side: the multi-room session ended; restore what was paused. */
    private void exitMultiRoomRemoteMode() {
        final boolean restoreLocal = resumeLocalAfterMultiRoom
                && localPlayer.getCurrentTrack() != null;
        final boolean restoreAirPlay = resumeAirPlayAfterMultiRoom && airPlaySessionActive;
        resumeLocalAfterMultiRoom = false;
        resumeAirPlayAfterMultiRoom = false;

        // 2500 ms fade-out of the multi-room audio, then hand back to the
        // previous source with its own fade-in.
        fadeMultiRoomOut(() -> {
            if (restoreLocal) {
                if (multiRoomAudioPlayer != null) multiRoomAudioPlayer.stop();
                cancelFade();
                localFade = 0f;
                localPlayer.setMasterGain(0f);
                localPlayer.play();
                state.source = PlayerUiState.Source.LOCAL;
                state.playing = true;
                fadeLocalIn(250);
                switchToLocalTrack();
            } else if (restoreAirPlay) {
                if (multiRoomAudioPlayer != null) multiRoomAudioPlayer.stop();
                // Restore the AirPlay metadata that was shown before.
                state.title = airMetaTitle.length() > 0 ? airMetaTitle
                        : getString(R.string.source_airplay);
                state.artist = airMetaArtist;
                state.album = airMetaAlbum;
                state.art = airMetaArt;
                if (airMetaDurationMs > 0) state.durationMs = (int) airMetaDurationMs;
                airPlayController.resumeReceiverOutput();
                airPlayController.setVolumeGain(0f);
                state.source = PlayerUiState.Source.AIRPLAY;
                state.playing = true;
                publish();
                fadeAirPlayIn(250);
            } else {
                if (multiRoomAudioPlayer != null) multiRoomAudioPlayer.stop();
                state.source = PlayerUiState.Source.IDLE;
                state.playing = false;
                state.title = getString(R.string.source_idle);
                state.artist = "";
                state.album = "";
                state.art = null;
                publish();
            }
        });
    }

    /** Fades the multi-room audio player's output down over 2500 ms. */
    private void fadeMultiRoomOut(Runnable onDone) {
        cancelMultiRoomFade();
        MultiRoomAudioPlayer p = multiRoomAudioPlayer;
        if (p == null || !p.isRunning()) {
            // Nothing playing: complete immediately so state restores fast.
            if (onDone != null) onDone.run();
            return;
        }
        final long durationMs = 2500;
        final long stepMs = 25;
        final int steps = (int) (durationMs / stepMs);
        final float[] stepCount = {0};
        multiRoomFadeRunnable = new Runnable() {
            @Override
            public void run() {
                stepCount[0]++;
                float gain = Math.max(0f, 1f - stepCount[0] / (float) steps);
                if (p != null) {
                    p.setOutputGain(gain);
                }
                if (stepCount[0] >= steps) {
                    if (p != null) {
                        p.setOutputGain(0f);
                    }
                    multiRoomFadeRunnable = null;
                    if (onDone != null) onDone.run();
                    return;
                }
                main.postDelayed(this, stepMs);
            }
        };
        main.post(multiRoomFadeRunnable);
    }

    /** Fades the multi-room audio player's output up to full over 2500 ms. */
    private void fadeMultiRoomIn() {
        cancelMultiRoomFade();
        final long durationMs = 2500;
        final long stepMs = 25;
        final int steps = (int) (durationMs / stepMs);
        final float[] stepCount = {0};
        multiRoomFadeRunnable = new Runnable() {
            @Override
            public void run() {
                stepCount[0]++;
                float gain = Math.min(1f, stepCount[0] / (float) steps);
                if (multiRoomAudioPlayer != null) {
                    multiRoomAudioPlayer.setOutputGain(gain);
                }
                if (stepCount[0] >= steps) {
                    if (multiRoomAudioPlayer != null) {
                        multiRoomAudioPlayer.setOutputGain(1f);
                    }
                    multiRoomFadeRunnable = null;
                    return;
                }
                main.postDelayed(this, stepMs);
            }
        };
        main.post(multiRoomFadeRunnable);
    }

    private void cancelMultiRoomFade() {
        if (multiRoomFadeRunnable != null) {
            main.removeCallbacks(multiRoomFadeRunnable);
            multiRoomFadeRunnable = null;
        }
    }

    /** Starts/stops the PCM streamer to match local playback + targets. */
    private void syncMultiRoomStreamer() {
        if (suppressStreamerSync) return;
        MultiRoomManager m = multiRoomManager;
        if (m == null || !m.hasTargets() || state.source != PlayerUiState.Source.LOCAL
                || !state.playing) {
            stopMultiRoomStreamer();
            return;
        }
        Track track = localPlayer.getCurrentTrack();
        if (track == null) {
            stopMultiRoomStreamer();
            return;
        }
        if (multiRoomStreamer != null && !multiRoomStreamer.getUri().equals(track.uri)) {
            // Track changed: tear down and start a fresh stream from 0.
            stopMultiRoomStreamer();
        }
        if (multiRoomStreamer == null) {
            long startPos = Math.max(0, (long) localPlayer.getPosition());
            long startWall = System.currentTimeMillis() - startPos;
            // The pushed stream is this device's audible output too, so the
            // ExoPlayer is muted (it stays running as the session brain).
            cancelFade();
            localFade = 0f;
            localPlayer.setMasterGain(0f);
            // The muted ExoPlayer is only the session brain while the
            // streamer owns the audible output; skip its equalizer so the
            // box CPU is free for the multi-room playback thread.
            equalizer.setForcedBypass(true);
            MultiRoomAudioPlayer localOut = getMultiRoomAudioPlayer();
            localOut.setBalance(prefs.getBalance());
            localOut.resetForStream();
            localOut.start();
            multiRoomStreamer = new MultiRoomStreamer(this, track.uri, m,
                    startPos, () -> (long) localPlayer.getPosition(), startWall);
            final long fStartPos = startPos;
            final long fStartWall = startWall;
            multiRoomStreamer.setLocalSink(new MultiRoomStreamer.Sink() {
                @Override
                public void onFormat(int sampleRate, int channels) {
                    MultiRoomAudioPlayer p = getMultiRoomAudioPlayer();
                    p.setFormat(sampleRate, channels);
                    // anchorLocalWall = the wall time at which the stream
                    // position was fStartPos (= startWall + fStartPos).
                    p.setLocalTimeline(fStartPos, fStartWall + fStartPos, 0);
                }

                @Override
                public void onChunk(byte[] pcm, long posMs) {
                    getMultiRoomAudioPlayer().onChunk(pcm, posMs);
                }
            });
            multiRoomStreamer.start();
            main.removeCallbacks(multiRoomClockTicker);
            main.post(multiRoomClockTicker);
        } else {
            // Stream already running: make sure a volume fade didn't bring the
            // ExoPlayer back (it must stay silent while the stream is the
            // audible output), otherwise both would play at once.
            cancelFade();
            localFade = 0f;
            localPlayer.setMasterGain(0f);
        }
    }

    /** Restarts the streamer once after a seek, from the settled position. */
    private final Runnable multiRoomRestartRunnable = new Runnable() {
        @Override
        public void run() {
            suppressStreamerSync = false;
            state.positionMs = (int) localPlayer.getPosition();
            stopMultiRoomStreamer();
            syncMultiRoomStreamer();
        }
    };

    private void stopMultiRoomStreamer() {
        main.removeCallbacks(multiRoomClockTicker);
        MultiRoomStreamer streamer = multiRoomStreamer;
        multiRoomStreamer = null;
        if (streamer != null) {
            streamer.stop();
        }
        MultiRoomAudioPlayer p = multiRoomAudioPlayer;
        if (p != null && p.isRunning()) {
            p.stop();
        }
        // Hand the audible output back to the ExoPlayer only when a multi-room
        // stream was actually running and muted it (e.g. receivers were
        // deselected). A plain song switch calls this with no streamer active
        // and must NOT trigger a service fade that would fight the local
        // song-switch fade (it used to cancel it and cause volume pumping).
        if (streamer != null && state.source == PlayerUiState.Source.LOCAL
                && localPlayer.isPlaying()) {
            fadeLocalIn();
        }
    }

    /** Sends clock samples + time-sync requests at 500 ms while streaming. */
    private final Runnable multiRoomClockTicker = new Runnable() {
        @Override
        public void run() {
            MultiRoomManager m = multiRoomManager;
            if (m != null && m.hasTargets() && multiRoomStreamer != null
                    && state.source == PlayerUiState.Source.LOCAL && state.playing) {
                long masterLatency = 0;
                if (multiRoomAudioPlayer != null) {
                    masterLatency = multiRoomAudioPlayer.getOutputLatencyMs();
                }
                m.sendClock(multiRoomStreamer.getStreamPositionMs(), masterLatency);
                m.sendTsRequests();
                main.postDelayed(this, 500);
            }
        }
    };

    /**
     * Three seconds after a track switch / play command, force a quick NTP
     * round so receivers re-anchor to the new stream immediately.
     */
    private final Runnable multiRoomCalibration = new Runnable() {
        @Override
        public void run() {
            MultiRoomManager m = multiRoomManager;
            if (m != null && m.hasTargets()) {
                Log.i(TAG, "post-switch latency calibration");
                m.forceTimeSync();
            }
        }
    };

    private void scheduleMultiRoomCalibration() {
        main.removeCallbacks(multiRoomCalibration);
        main.postDelayed(multiRoomCalibration, 3000);
    }

    private MultiRoomAudioPlayer getMultiRoomAudioPlayer() {
        if (multiRoomAudioPlayer == null) {
            multiRoomAudioPlayer = new MultiRoomAudioPlayer();
            multiRoomAudioPlayer.setBalance(prefs.getBalance());
            multiRoomAudioPlayer.setFirEqualizer(multiRoomEqualizer);
            // Keep the master's queue healthy (150 ms) and let the receiver
            // pull ahead by 80 ms via the broadcast compensation below.
            multiRoomAudioPlayer.setLatencyCompensationMs(150);
        }
        return multiRoomAudioPlayer;
    }

    private final MultiRoomManager.Events multiRoomEvents = new MultiRoomManager.Events() {
        @Override
        public void onRemoteMeta(String title, String artist, String album, long durationMs) {
            main.post(() -> {
                enterMultiRoomRemoteMode();
                state.source = PlayerUiState.Source.REMOTE;
                if (title != null && title.length() > 0) {
                    state.title = title;
                    multiRoomMetaTitle = title;
                }
                if (artist != null && artist.length() > 0) {
                    state.artist = artist;
                    multiRoomMetaArtist = artist;
                }
                if (album != null && album.length() > 0) {
                    state.album = album;
                    multiRoomMetaAlbum = album;
                }
                if (durationMs > 0) {
                    state.durationMs = (int) durationMs;
                    multiRoomMetaDurationMs = durationMs;
                }
                publish();
            });
        }

        @Override
        public void onRemoteArt(byte[] imageData) {
            main.post(() -> {
                try {
                    Bitmap art = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                    if (art != null) {
                        state.art = art;
                        multiRoomMetaArt = art;
                        publish();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "remote art decode failed", t);
                }
            });
        }

        @Override
        public void onRemotePlay(int positionMs) {
            main.post(() -> {
                enterMultiRoomRemoteMode();
                state.source = PlayerUiState.Source.REMOTE;
                state.playing = true;
                state.positionMs = positionMs;
                MultiRoomAudioPlayer p = getMultiRoomAudioPlayer();
                p.setBalance(prefs.getBalance());
                if (p.isRunning()) {
                    p.resume();
                } else {
                    p.start();
                }
                p.setOutputGain(0f);
                fadeMultiRoomIn();
                publish();
            });
        }

        @Override
        public void onRemotePause() {
            main.post(() -> {
                if (state.source == PlayerUiState.Source.REMOTE) {
                    state.playing = false;
                    getMultiRoomAudioPlayer().pause();
                    publish();
                }
            });
        }

        @Override
        public void onRemoteStop() {
            main.post(() -> {
                if (state.source == PlayerUiState.Source.REMOTE) {
                    exitMultiRoomRemoteMode();
                    publish();
                }
            });
        }

        @Override
        public void onRemoteFlush() {
            main.post(() -> {
                if (multiRoomAudioPlayer != null) {
                    multiRoomAudioPlayer.flush();
                }
            });
        }

        @Override
        public void onRemoteFormat(int sampleRate, int channels) {
            main.post(() -> {
                MultiRoomAudioPlayer p = getMultiRoomAudioPlayer();
                p.setBalance(prefs.getBalance());
                p.setFormat(sampleRate, channels);
            });
        }

        @Override
        public void onRemoteClock(long masterPosMs, long masterWallMs, long offsetMs, long masterLatencyMs) {
            main.post(() -> {
                getMultiRoomAudioPlayer().updateClock(
                        masterPosMs, masterWallMs, offsetMs, masterLatencyMs);
                if (state.source == PlayerUiState.Source.REMOTE && state.playing) {
                    long anchorLocalWall = masterWallMs - offsetMs;
                    long nowPos = masterPosMs + (System.currentTimeMillis() - anchorLocalWall);
                    if (nowPos >= 0) state.positionMs = (int) nowPos;
                    publish();
                }
            });
        }

        @Override
        public void onRemoteAudio(byte[] pcm, long posMs) {
            getMultiRoomAudioPlayer().onChunk(pcm, posMs);
        }

        @Override
        public void onRemoteLatencyComp(int ms) {
            main.post(() -> {
                if (multiRoomAudioPlayer != null) {
                    multiRoomAudioPlayer.setLatencyCompensationMs(ms);
                }
            });
        }

        @Override
        public void onRemoteControl(String action, int positionMs) {
            main.post(() -> {
                Log.i(TAG, "remote control: " + action + " pos=" + positionMs);
                switch (action) {
                    case "play":
                        if (state.source == PlayerUiState.Source.LOCAL && !state.playing) {
                            togglePlay();
                        }
                        break;
                    case "pause":
                        if (state.source == PlayerUiState.Source.LOCAL && state.playing) {
                            togglePlay();
                        }
                        break;
                    case "toggle":
                        togglePlay();
                        break;
                    case "next":
                        next();
                        break;
                    case "prev":
                        previous();
                        break;
                    case "seek":
                        seekTo(positionMs);
                        break;
                    default:
                        break;
                }
            });
        }

        @Override
        public void onRemoteDisconnect() {
            main.post(() -> {
                if (state.source != PlayerUiState.Source.REMOTE) {
                    // AirPlay may be occupying the UI; the multi-room session
                    // is gone, so don't try to restore it later.
                    resumeMultiRoomAfterAirPlay = false;
                    return;
                }
                Log.i(TAG, "multi-room master disconnected, exiting remote mode");
                exitMultiRoomRemoteMode();
                state.statusText = "";
                publish();
            });
        }

        @Override
        public void onTargetsChanged(int count) {
            main.post(() -> {
                state.statusText = count > 0
                        ? getString(R.string.playback_multicast_count, count) : "";
                if (count > 0) {
                    // A receiver just connected while playback was already
                    // running: push the current track + state immediately.
                    pushMultiRoomState();
                    // Receiver-side correction: pull the tablet ~80 ms ahead
                    // (it was audibly late with master-only compensation).
                    multiRoomManager.sendLatencyComp(-80);
                } else {
                    // All receivers gone: hand the audio back to ExoPlayer.
                    syncMultiRoomStreamer();
                }
                publish();
            });
        }
    };

    private final AirPlayController.Events airPlayEvents = new AirPlayController.Events() {
        @Override
        public void onSessionStart(String clientName, String dacpId, String activeRemote, String remoteIp) {
            main.post(() -> handleAirPlayStart(clientName, dacpId, activeRemote, remoteIp, true));
        }

        @Override
        public void onSessionPause() {
            main.post(PlaybackService.this::handleAirPlayPause);
        }

        @Override
        public void onSessionResume() {
            // Sender resumed streaming after a pause: take over again, but keep
            // the "resume local afterwards" flag across pause/resume cycles.
            main.post(() -> handleAirPlayStart("", "", "", "", false));
        }

        @Override
        public void onSessionStop() {
            main.post(PlaybackService.this::handleAirPlayStop);
        }

        @Override
        public void onVolume(float volumeDb) {
            Log.d(TAG, "AirPlay volume: " + volumeDb);
        }

        @Override
        public void onTrackInfo(String title, String artist, String album, Bitmap art, long durationMs) {
            main.post(() -> applyAirPlayTrackInfo(title, artist, album, art, durationMs));
        }

        @Override
        public void onProgress(long positionMs, long durationMs) {
            main.post(() -> {
                if (state.source == PlayerUiState.Source.AIRPLAY) {
                    state.positionMs = (int) positionMs;
                    if (durationMs > 0) state.durationMs = (int) durationMs;
                    publish();
                }
            });
        }

        @Override
        public void onPlayState(boolean playing) {
            main.post(() -> {
                if (state.source != PlayerUiState.Source.AIRPLAY) return;
                if (playing && !state.playing) {
                    handleAirPlayStart(state.clientName, "", "", "", false);
                } else if (!playing && state.playing) {
                    handleAirPlayPause();
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        DiagnosticLog.init(this);
        createNotificationChannel();

        state = new PlayerUiState();
        state.mode = PlayMode.fromKey(prefs.getPlayMode());
        state.title = getString(R.string.source_idle);
        state.artist = "";
        state.album = "";
        StateBus.get().postState(state);

        initMediaSession();
        startForeground(NOTIFICATION_ID, buildNotification());

        airPlayController = new AirPlayController(this, airPlayEvents);
        airPlayController.start(prefs.getAirPlayName());

        equalizer = new FirEqualizer();
        equalizer.setBandGains(prefs.getEqGains());
        eqProcessor = new EqAudioProcessor(equalizer);
        // AirPlay gets its own equalizer instance: local and AirPlay use
        // different sample rates, and a shared instance would keep flipping
        // its filter coefficients while the other path is still fading,
        // producing a transient buzz at every switch.
        airEqualizer = new FirEqualizer();
        airEqualizer.setBandGains(prefs.getEqGains());
        airPlayController.setFirEqualizer(airEqualizer);
        multiRoomEqualizer = new FirEqualizer();
        multiRoomEqualizer.setBandGains(prefs.getEqGains());

        multiRoomManager = new MultiRoomManager();
        multiRoomManager.start(prefs.getAirPlayName(), multiRoomEvents);

        localPlayer = new LocalPlayer(this, localListener, new AudioProcessor[]{eqProcessor});
        // Skip the 1000 ms local song-switch fade while multi-room is active:
        // the stream (or this device as a receiver) owns the audible output.
        localPlayer.setFadeGate(() -> {
            boolean mr = (multiRoomManager != null && multiRoomManager.hasTargets())
                    || multiRoomStreamer != null
                    || state.source == PlayerUiState.Source.REMOTE;
            return !mr;
        });
        localPlayer.setMode(state.mode);
        float balance = prefs.getBalance();
        localPlayer.setBalance(balance);
        airPlayController.setBalance(balance);

        registerUsbReceiver();
        acquireWakeLock();

        MusicLibrary.getInstance().rescan(this, (tracks, error) -> {
            this.tracks = tracks;
            if (error != null) {
                state.statusText = error;
            }
			if (state.source == PlayerUiState.Source.IDLE) {
				resumeLastTrackIfPossible(prefs.isAutoPlayOnStart());
			}
			publish();
		});

        main.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }
        String action = intent.getAction();
        switch (action) {
            case ACTION_START:
                break;
            case ACTION_PLAY:
                togglePlay();
                break;
            case ACTION_PAUSE:
                pausePlayback();
                break;
            case ACTION_TOGGLE:
                togglePlay();
                break;
            case ACTION_NEXT:
                next();
                break;
            case ACTION_PREV:
                previous();
                break;
            case ACTION_RESCAN:
                MusicLibrary.getInstance().rescan(this, (tracks, error) -> this.tracks = tracks);
                break;
            case ACTION_RESTART_AIRPLAY:
                airPlayController.restart(prefs.getAirPlayName());
                break;
            case ACTION_PLAY_TRACK:
                String uri = intent.getStringExtra(EXTRA_TRACK_URI);
                if (uri != null) playTrackByUri(uri);
                break;
            case ACTION_STOP_SERVICE:
                stopSelf();
                break;
            default:
                break;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        saveLastTrack();
        if (ticker != null) main.removeCallbacks(ticker);
        cancelFade();
        cancelAirFade();
        if (multiRoomManager != null) {
            multiRoomManager.stop();
            multiRoomManager = null;
        }
        stopMultiRoomStreamer();
        if (multiRoomAudioPlayer != null) {
            multiRoomAudioPlayer.stop();
            multiRoomAudioPlayer = null;
        }
        if (airPlayController != null) airPlayController.stop();
        main.removeCallbacks(airPlayStatusPoller);
        if (dacpClient != null) {
            dacpClient.release();
            dacpClient = null;
        }
        if (localPlayer != null) localPlayer.release();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        unregisterUsbReceiver();
        releaseWakeLock();
        metadataExecutor.shutdown();
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Public actions (used by activities)
    // ------------------------------------------------------------------

    public List<Track> getTracks() {
        return tracks;
    }

    /** The local track currently being played, or null outside local mode. */
    public Track getCurrentTrack() {
        if (state.source == PlayerUiState.Source.LOCAL && localPlayer != null) {
            return localPlayer.getCurrentTrack();
        }
        return null;
    }

    /** True while the library should highlight a local/multi-room track. */
    public boolean isShowingLibraryTrack() {
        return state.source == PlayerUiState.Source.LOCAL
                || state.source == PlayerUiState.Source.REMOTE;
    }

    /** Title of the currently shown track (multi-room receiver metadata). */
    public String getCurrentDisplayTitle() {
        return state.title;
    }

    /** Artist of the currently shown track (multi-room receiver metadata). */
    public String getCurrentDisplayArtist() {
        return state.artist;
    }

    /** Replaces the Chinese "unknown" fallback with the UI language's text. */
    private String localizeTrackField(String value, String unknownConst, int fallbackRes) {
        return unknownConst.equals(value) ? getString(fallbackRes) : value;
    }

    /** Replaces the service playlist (used after a library rescan). */
    public void setTracks(List<Track> tracks) {
        this.tracks = tracks == null
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(tracks);
    }

    /**
     * Removes deleted files from the playlist. If the currently playing
     * local track was deleted, playback immediately switches to the next
     * remaining track; while another source (AirPlay / multi-room) owns the
     * output the replacement track is only queued, not auto-started.
     */
    public void removeDeletedTracks(List<Track> deleted) {
        if (deleted == null || deleted.isEmpty()) return;
        java.util.Set<android.net.Uri> uris = new java.util.HashSet<>();
        for (Track t : deleted) {
            if (t != null && t.uri != null) uris.add(t.uri);
        }
        if (uris.isEmpty()) return;
        List<Track> filtered = new java.util.ArrayList<>();
        for (Track t : tracks) {
            if (t.uri == null || !uris.contains(t.uri)) filtered.add(t);
        }
        tracks = filtered;
        if (localPlayer != null) {
            localPlayer.removeTracks(uris, state.source == PlayerUiState.Source.LOCAL);
        }
    }

    public void playTrack(Track track) {
        cancelServiceFade();
        int index = tracks.indexOf(track);
        if (index < 0) {
            tracks = new java.util.ArrayList<>(MusicLibrary.getInstance().getCachedTracks());
            index = tracks.indexOf(track);
        }
        if (index < 0 && track != null) {
            tracks = new java.util.ArrayList<>();
            tracks.add(track);
            index = 0;
        }
        if (index < 0) return;
        state.source = PlayerUiState.Source.LOCAL;
        boolean fading = localPlayer.setPlaylist(tracks, index);
        if (!fading) {
            // Fresh start (nothing playing): fade the new song in ourselves.
            localPlayer.setMasterGain(0f);
            localPlayer.play();
            fadeLocalIn(1000);
            loadMetadata(localPlayer.getCurrentTrack());
        }
        scheduleMultiRoomCalibration();
    }

    public void playTrackByUri(String uri) {
        for (Track t : tracks) {
            if (t.uri.toString().equals(uri)) {
                playTrack(t);
                return;
            }
        }
    }

    public void togglePlay() {
        if (state.source == PlayerUiState.Source.REMOTE && multiRoomManager != null) {
            // Receiver UI: forward the transport command to the master.
            multiRoomManager.sendControlToMaster("toggle", 0);
            return;
        }
        if (state.source == PlayerUiState.Source.AIRPLAY) {
            if (state.playing) {
                pauseAirPlay();
            } else {
                resumeAirPlay();
            }
            return;
        }
        if (state.source == PlayerUiState.Source.LOCAL) {
            if (state.playing) {
                state.playing = false;
                publish();
                fadeLocalOut(localPlayer::pause);
            } else {
                state.playing = true;
                publish();
                localPlayer.setMasterGain(0f);
                localPlayer.play();
                fadeLocalIn();
            }
            return;
        }
        // idle: start local playback if we have tracks
        if (!tracks.isEmpty()) {
            playTrack(tracks.get(0));
        }
    }

    /**
     * Receiver-side disconnect: fade the multi-room audio out first, then ask
     * the master to drop us so the stream stays alive during the fade.
     */
    public void disconnectFromMaster() {
        if (state.source != PlayerUiState.Source.REMOTE) return;
        Log.i(TAG, "receiver disconnect requested; fading out then disconnecting");
        fadeMultiRoomOut(() -> {
            if (multiRoomAudioPlayer != null) multiRoomAudioPlayer.stop();
            if (multiRoomManager != null) {
                multiRoomManager.sendControlToMaster("disconnectMe", 0);
            }
        });
    }

    public void pausePlayback() {
        if (state.source == PlayerUiState.Source.REMOTE && multiRoomManager != null) {
            multiRoomManager.sendControlToMaster("pause", 0);
            return;
        }
        if (state.source == PlayerUiState.Source.LOCAL) {
            if (state.playing) {
                state.playing = false;
                publish();
                fadeLocalOut(localPlayer::pause);
            }
        } else if (state.source == PlayerUiState.Source.AIRPLAY && state.playing) {
            pauseAirPlay();
        }
    }

    public void next() {
        if (state.source == PlayerUiState.Source.REMOTE && multiRoomManager != null) {
            multiRoomManager.sendControlToMaster("next", 0);
            return;
        }
        if (state.source == PlayerUiState.Source.LOCAL) {
            // Stop any pending service fade (pause/toggle fade-out) so it
            // cannot fight the local song-switch fade over the volume.
            cancelServiceFade();
            Log.i(TAG, "next()");
            localPlayer.next();
        } else if (state.source == PlayerUiState.Source.AIRPLAY) {
            sendAirPlayTransport(true);
        }
    }

    public void previous() {
        if (state.source == PlayerUiState.Source.REMOTE && multiRoomManager != null) {
            multiRoomManager.sendControlToMaster("prev", 0);
            return;
        }
        if (state.source == PlayerUiState.Source.LOCAL) {
            cancelServiceFade();
            Log.i(TAG, "previous()");
            localPlayer.previous();
        } else if (state.source == PlayerUiState.Source.AIRPLAY) {
            sendAirPlayTransport(false);
        }
    }

    /**
     * Sends next/previous to the AirPlay sender via DACP, without ending the
     * AirPlay session. The sender then switches its own track and streams the
     * new audio over the same session.
     */
    private void sendAirPlayTransport(boolean forward) {
        if (dacpClient == null) {
            Log.w(TAG, "no DACP client, cannot control AirPlay transport");
            return;
        }
        if (forward) {
            dacpClient.next();
        } else {
            dacpClient.previous();
        }
    }

    public void seekTo(int positionMs) {
        if (state.source == PlayerUiState.Source.REMOTE && multiRoomManager != null) {
            multiRoomManager.sendControlToMaster("seek", positionMs);
            return;
        }
        if (state.source == PlayerUiState.Source.LOCAL) {
            localPlayer.seekTo(positionMs);
            if (multiRoomManager != null && multiRoomManager.hasTargets()) {
                // Receivers must drop buffered audio and follow the new
                // position; the streamer restarts from the new location.
                multiRoomManager.sendFlush();
                suppressStreamerSync = true;
                main.removeCallbacks(multiRoomRestartRunnable);
                main.postDelayed(multiRoomRestartRunnable, 300);
            }
        }
    }

    /** Applies a play mode (used by the settings screen). */
    public void applyPlayMode(String key) {
        PlayMode mode = PlayMode.fromKey(key);
        state.mode = mode;
        localPlayer.setMode(mode);
        publish();
    }

    /** Sets the left/right balance (-1 = full left, 0 = center, +1 = full right). */
    public void setBalance(float balance) {
        prefs.setBalance(balance);
        localPlayer.setBalance(balance);
        airPlayController.setBalance(balance);
    }

    /** Applies the 10-band equalizer globally (local, AirPlay, multi-room). */
    public void setEqualizerGains(double[] gainsDb) {
        prefs.setEqGains(gainsDb);
        if (equalizer != null) {
            equalizer.setBandGains(gainsDb);
        }
        if (airEqualizer != null) {
            airEqualizer.setBandGains(gainsDb);
        }
        if (multiRoomEqualizer != null) {
            multiRoomEqualizer.setBandGains(gainsDb);
        }
    }

    public FirEqualizer getEqualizer() {
        return equalizer;
    }

    public void restartAirPlay() {
        DiagnosticLog.i(TAG, "restartAirPlay requested from UI");
        airPlayController.restart(prefs.getAirPlayName());
    }

    public void rescanLibrary() {
        MusicLibrary.getInstance().clearCache();
        MusicLibrary.getInstance().rescan(this, (tracks, error) -> {
            this.tracks = tracks;
            publish();
		});
    }

    public MediaSessionCompat.Token getSessionToken() {
        return mediaSession == null ? null : mediaSession.getSessionToken();
    }

    /** Returns the current AirPlay service status for the settings screen. */
    public String getAirPlayStatus() {
        if (airPlayController == null) return "service: not initialized";
        return airPlayController.getStatusText();
    }

    /** Multi-room sync manager (discovery + master/receiver roles). */
    public MultiRoomManager getMultiRoomManager() {
        return multiRoomManager;
    }

    /** True while this device is broadcasting to or receiving from multi-room. */
    public boolean isMultiRoomActive() {
        return (multiRoomManager != null && multiRoomManager.hasTargets())
                || state.source == PlayerUiState.Source.REMOTE;
    }

    // ------------------------------------------------------------------
    // AirPlay session handling (source switching rules)
    // ------------------------------------------------------------------

    private void handleAirPlayStart(String clientName, String dacpId, String activeRemote, String remoteIp, boolean newSession) {
        if (state.source == PlayerUiState.Source.REMOTE) {
            // Multi-room audio is playing on this receiver: fade it out over
            // 2500 ms, then AirPlay takes over; remember to restore it when
            // the AirPlay session ends.
            if (!newSession) return; // state polls must not steal the UI
            Log.i(TAG, "AirPlay arrived during multi-room: fading multi-room out");
            airPlaySessionActive = true; // accept early metadata during the fade
            resumeMultiRoomAfterAirPlay = true;
            resumeLocalAfterAirPlay = false;
            fadeMultiRoomOut(() -> {
                if (multiRoomAudioPlayer != null) multiRoomAudioPlayer.stop();
                completeAirPlayStart(clientName, dacpId, activeRemote, remoteIp, newSession, 2500);
            });
            return;
        }
        // Master (or idle/plain) path: receiving AirPlay while broadcasting
        // multi-room drops every receiver.
        if (multiRoomManager != null && multiRoomManager.hasTargets()) {
            Log.i(TAG, "AirPlay received while broadcasting multi-room; disconnecting all receivers");
            multiRoomManager.disconnectAllAndRemember();
        }
        completeAirPlayStart(clientName, dacpId, activeRemote, remoteIp, newSession, 2000);
    }

    private void completeAirPlayStart(String clientName, String dacpId, String activeRemote, String remoteIp,
                                      boolean newSession, long fadeInMs) {
        DiagnosticLog.i(TAG, "AirPlay session start (new=" + newSession + ", client=" + clientName + ")");
        airPlaySessionActive = true;
        airPlayUserPaused = false;
        airPlayWatchdogPaused = false;
        main.removeCallbacks(airPlayStatusPoller);
        main.post(airPlayStatusPoller);
        if (newSession) {
            if (dacpClient != null) {
                dacpClient.release();
                dacpClient = null;
            }
            dacpClient = new DacpClient(dacpId, activeRemote, remoteIp);
            dacpClient.startDiscovery();
        }
        if (localPlayer.isPlaying()) {
            resumeLocalAfterAirPlay = true;
            fadeLocalOut(localPlayer::pause);
        } else if (newSession) {
            resumeLocalAfterAirPlay = false;
        }
        state.source = PlayerUiState.Source.AIRPLAY;
        state.playing = true;
		state.airPlayPaused = false;
		state.receiverPausedByUser = false;
		if (state.source == PlayerUiState.Source.AIRPLAY) {
			airPlayController.resumeReceiverOutput();
		}
        state.clientName = clientName == null || clientName.trim().length() == 0
                ? prefs.getAirPlayName() : clientName.trim();
        state.title = getString(R.string.source_airplay);
        state.artist = state.clientName;
        state.album = getString(R.string.playback_from_sender);
        state.art = null;
        state.positionMs = 0;
		state.durationMs = 0;
        if (airMetaTitle.length() > 0) state.title = airMetaTitle;
        if (airMetaArtist.length() > 0) state.artist = airMetaArtist;
        if (airMetaAlbum.length() > 0) state.album = airMetaAlbum;
        if (airMetaArt != null) state.art = airMetaArt;
        if (airMetaDurationMs > 0) state.durationMs = (int) airMetaDurationMs;
        airPlayController.setVolumeGain(0f);
        fadeAirPlayIn(fadeInMs);
		publish();
	}

    /**
     * Updates the UI with classic AirPlay track metadata (text + artwork).
     * Fields may arrive in separate packets, so null fields keep the current
     * value; the session must be active for the info to be shown.
     */
    private void applyAirPlayTrackInfo(String title, String artist, String album, Bitmap art, long durationMs) {
        if (!airPlaySessionActive) return;
        Log.i(TAG, "AirPlay metadata: title=" + title + " artist=" + artist + " album=" + album
                + " art=" + (art != null) + " dur=" + durationMs);
        // Always cache the metadata; the UI only shows it once AirPlay owns
        // the screen (e.g. after the multi-room fade-out completes).
        if (title != null && title.trim().length() > 0) airMetaTitle = title.trim();
        if (artist != null && artist.trim().length() > 0) airMetaArtist = artist.trim();
        if (album != null && album.trim().length() > 0) airMetaAlbum = album.trim();
        if (art != null) airMetaArt = art;
        if (durationMs > 0) airMetaDurationMs = durationMs;
        if (state.source != PlayerUiState.Source.AIRPLAY) return;
        if (title != null && title.trim().length() > 0) state.title = title.trim();
        if (artist != null && artist.trim().length() > 0) state.artist = artist.trim();
        if (album != null && album.trim().length() > 0) state.album = album.trim();
        if (art != null) state.art = art;
        if (durationMs > 0) state.durationMs = (int) durationMs;
        publish();
    }

    private void handleAirPlayPause() {
        if (state.source == PlayerUiState.Source.REMOTE) return; // multi-room owns the UI
        cancelAirFade();
        DiagnosticLog.i(TAG, "AirPlay session pause (userPaused=" + airPlayUserPaused
                + ", resumeLocal=" + resumeLocalAfterAirPlay + ")");
        state.airPlayPaused = true;
        // A pause requested from our own UI keeps AirPlay paused; a pause
        // initiated by the sender auto-resumes the local background music.
        if (!airPlayUserPaused && resumeLocalAfterAirPlay && localPlayer.getCurrentTrack() != null) {
            localPlayer.play();
            fadeLocalIn();
            state.source = PlayerUiState.Source.LOCAL;
            state.playing = true;
            switchToLocalTrack();
        } else {
            state.source = PlayerUiState.Source.AIRPLAY;
            state.playing = false;
        }
		publish();
	}

	/**
	 * Applies a DACP play status polled from the sender, keeping the app's
	 * play/pause state in sync when the phone is used to control playback.
	 *
	 * @param status 4 = playing, 3 = paused, 2 = stopped, -1 = unknown
	 */
	private void applyAirPlayStatus(int status) {
		if (status == 4) {
			// Phone resumed: mirror the sender-resume path, keeping the
			// previously known client name for the UI badge.
			if (!(state.source == PlayerUiState.Source.AIRPLAY && state.playing)) {
				handleAirPlayStart(state.clientName, "", "", "", false);
			}
		} else if (status == 3) {
			// Phone paused: mirror the sender-pause path.
			if (state.source == PlayerUiState.Source.AIRPLAY && state.playing) {
				handleAirPlayPause();
			}
		}
	}

	private void handleAirPlayStop() {
		if (resumeMultiRoomAfterAirPlay) {
			// AirPlay interrupted multi-room: fade AirPlay out over 2500 ms,
			// then resume the multi-room stream with a 2500 ms fade-in.
			Log.i(TAG, "AirPlay ended; fading out and resuming multi-room");
			resumeMultiRoomAfterAirPlay = false;
			airPlaySessionActive = false;
			airPlayUserPaused = false;
			airPlayWatchdogPaused = false;
			main.removeCallbacks(airPlayStatusPoller);
			if (dacpClient != null) {
				dacpClient.release();
				dacpClient = null;
			}
			fadeAirPlayOut(() -> {
				airPlayController.pauseReceiverOutput();
				// Restore the multi-room metadata that was shown before AirPlay.
				state.title = multiRoomMetaTitle.length() > 0
						? multiRoomMetaTitle : getString(R.string.source_remote);
				state.artist = multiRoomMetaArtist;
				state.album = multiRoomMetaAlbum;
				state.art = multiRoomMetaArt;
				if (multiRoomMetaDurationMs > 0) {
					state.durationMs = (int) multiRoomMetaDurationMs;
				}
				MultiRoomAudioPlayer p = getMultiRoomAudioPlayer();
				p.setBalance(prefs.getBalance());
				p.setOutputGain(0f);
				p.start();
				state.source = PlayerUiState.Source.REMOTE;
				state.playing = true;
				publish();
				fadeMultiRoomIn();
			});
			return;
		}
		if (state.source == PlayerUiState.Source.REMOTE) {
			// Keep the multi-room UI; just drop the dead AirPlay session so it
			// is not restored later.
			airPlaySessionActive = false;
			airPlayUserPaused = false;
			airPlayWatchdogPaused = false;
			main.removeCallbacks(airPlayStatusPoller);
			if (dacpClient != null) {
				dacpClient.release();
				dacpClient = null;
			}
			return;
		}
		cancelAirFade();
		DiagnosticLog.i(TAG, "AirPlay session stop (resumeLocal=" + resumeLocalAfterAirPlay + ")");
		airPlaySessionActive = false;
		airPlayUserPaused = false;
		airPlayWatchdogPaused = false;
		main.removeCallbacks(airPlayStatusPoller);
		if (dacpClient != null) {
			dacpClient.release();
			dacpClient = null;
		}
		state.airPlayPaused = false;
		state.receiverPausedByUser = false;
		airPlayController.resumeReceiverOutput();
        if (resumeLocalAfterAirPlay && localPlayer.getCurrentTrack() != null) {
            localPlayer.play();
            fadeLocalIn();
            state.source = PlayerUiState.Source.LOCAL;
            state.playing = true;
            switchToLocalTrack();
        } else if (state.source == PlayerUiState.Source.AIRPLAY) {
            state.source = PlayerUiState.Source.IDLE;
            state.playing = false;
            state.title = getString(R.string.source_idle);
            state.artist = "";
            state.album = "";
            state.art = null;
        }
		resumeLocalAfterAirPlay = false;
        airPlayController.setVolumeGain(1f);
        // After an AirPlay interruption on the master, rejoin the receivers
        // that were dropped; offline ones are skipped by the connect attempt.
        if (multiRoomManager != null) {
            multiRoomManager.reconnectRemembered();
        }
		publish();
	}

	/**
	 * Fades the local player volume down to zero, then runs {@code onDone}
	 * (usually pause). Total duration 2500 ms.
	 */
	private void fadeLocalOut(Runnable onDone) {
		cancelFade();
		Log.i(TAG, "fadeLocalOut start gain="
				+ (localPlayer != null ? localPlayer.getMasterGain() : localFade));
		// Start from the player's ACTUAL gain: the local song-switch fade
		// drives the player volume directly, so the service-side localFade
		// field can be stale (even 0), which would make this fade a no-op.
		final float start = localPlayer != null ? localPlayer.getMasterGain() : localFade;
		final long durationMs = 2500;
		final long stepMs = 25;
		final int steps = (int) (durationMs / stepMs);
		final float[] stepCount = {0};
		fadeRunnable = new Runnable() {
			@Override
			public void run() {
				stepCount[0]++;
				localFade = Math.max(0f, start * (1f - stepCount[0] / (float) steps));
				localPlayer.setMasterGain(localFade);
				if (stepCount[0] >= steps) {
					localFade = 0f;
					localPlayer.setMasterGain(0f);
					if (onDone != null) onDone.run();
					fadeRunnable = null;
					return;
				}
				main.postDelayed(this, stepMs);
			}
		};
		main.post(fadeRunnable);
	}

	/** Fades the local player volume up to full (2000 ms). */
	private void fadeLocalIn() {
		fadeLocalIn(2000);
	}

	/** Fades the local player volume up to full over {@code durationMs}. */
	private void fadeLocalIn(long durationMs) {
		Log.i(TAG, "fadeLocalIn(" + durationMs + ") streamer=" + (multiRoomStreamer != null));
		if (multiRoomStreamer != null) {
			// Multi-room stream owns the master's output; never unmute the
			// ExoPlayer while it is active (would cause double audio).
			cancelFade();
			localFade = 0f;
			localPlayer.setMasterGain(0f);
			return;
		}
		// The ExoPlayer is taking over the audible output again: re-enable
		// its equalizer (bypassed while the multi-room stream owned audio).
		equalizer.setForcedBypass(false);
		cancelFade();
		localFade = 0f;
		final long stepMs = 25;
		final int steps = (int) (durationMs / stepMs);
		final float[] stepCount = {0};
		fadeRunnable = new Runnable() {
			@Override
			public void run() {
				stepCount[0]++;
				localFade = Math.min(1f, stepCount[0] / (float) steps);
				localPlayer.setMasterGain(localFade);
				if (stepCount[0] >= steps) {
					localFade = 1f;
					localPlayer.setMasterGain(1f);
					fadeRunnable = null;
					return;
				}
				main.postDelayed(this, stepMs);
			}
		};
		main.post(fadeRunnable);
	}

	/** Fades the AirPlay receiver output gain from zero up to full (2000 ms). */
	private void fadeAirPlayIn() {
		fadeAirPlayIn(2000);
	}

	/** Fades the AirPlay receiver output gain from zero up to full. */
	private void fadeAirPlayIn(long durationMs) {
		cancelAirFade();
		final long stepMs = 25;
		final int steps = (int) (durationMs / stepMs);
		final float[] stepCount = {0};
		airFadeRunnable = new Runnable() {
			@Override
			public void run() {
				stepCount[0]++;
				float gain = Math.min(1f, stepCount[0] / (float) steps);
				airPlayController.setVolumeGain(gain);
				if (stepCount[0] >= steps) {
					airPlayController.setVolumeGain(1f);
					airFadeRunnable = null;
					return;
				}
				main.postDelayed(this, stepMs);
			}
		};
		main.post(airFadeRunnable);
	}

	/** Fades the AirPlay receiver output gain down to zero (2500 ms), then runs {@code onDone}. */
	private void fadeAirPlayOut(Runnable onDone) {
		cancelAirFade();
		final long durationMs = 2500;
		final long stepMs = 25;
		final int steps = (int) (durationMs / stepMs);
		final float[] stepCount = {0};
		airFadeRunnable = new Runnable() {
			@Override
			public void run() {
				stepCount[0]++;
				float gain = Math.max(0f, 1f - stepCount[0] / (float) steps);
				airPlayController.setVolumeGain(gain);
				if (stepCount[0] >= steps) {
					airPlayController.setVolumeGain(0f);
					airFadeRunnable = null;
					if (onDone != null) onDone.run();
					return;
				}
				main.postDelayed(this, stepMs);
			}
		};
		main.post(airFadeRunnable);
	}

    private void cancelFade() {
        cancelServiceFade();
        if (localPlayer != null) {
            localPlayer.cancelSwitchFade();
        }
	}

	/** Cancels only the service-side volume fade (pause/toggle/play fades),
	 *  leaving the local player's song-switch fade untouched. */
    private void cancelServiceFade() {
        Log.i(TAG, "cancelServiceFade fadeRunnable=" + (fadeRunnable != null));
		if (fadeRunnable != null) {
			main.removeCallbacks(fadeRunnable);
			fadeRunnable = null;
		}
	}

	private void cancelAirFade() {
		if (airFadeRunnable != null) {
			main.removeCallbacks(airFadeRunnable);
			airFadeRunnable = null;
		}
	}

	/**
	 * Refreshes the UI with the local track that just resumed after an
	 * AirPlay session (title/artist/album/art/duration).
	 */
	private void switchToLocalTrack() {
		Track track = localPlayer.getCurrentTrack();
		if (track != null) {
			state.art = null;
			loadMetadata(track);
		}
	}

    /**
     * Pauses AirPlay playback with a 2500 ms fade-out, then tells the sender
     * (via DACP) to pause and mutes the receiver as a safety net.
     */
    private void pauseAirPlay() {
        airPlayUserPaused = true;
        state.receiverPausedByUser = true;
        state.playing = false;
        publish();
        fadeAirPlayOut(() -> {
            airPlayController.pauseReceiverOutput();
            if (dacpClient != null) {
                dacpClient.playPause();
            }
        });
    }

    /**
     * Resumes AirPlay playback with a 2000 ms fade-in: tells the sender to
     * resume via DACP and unmutes the receiver.
     */
    private void resumeAirPlay() {
        cancelAirFade();
        airPlayUserPaused = false;
        state.receiverPausedByUser = false;
        airPlayController.setVolumeGain(0f);
        airPlayController.resumeReceiverOutput();
        if (dacpClient != null) {
            dacpClient.playPause();
        }
        state.playing = true;
        publish();
        fadeAirPlayIn();
    }

    // ------------------------------------------------------------------
    // Metadata loading
    // ------------------------------------------------------------------

    private void loadMetadata(Track track) {
        if (track == null) return;
        // Only clear the previous cover once this track is actually the one
        // being played (i.e. the fade-out finished and the player switched).
        // Tap-time previews must keep the old cover until the switch.
        Track cur = localPlayer != null ? localPlayer.getCurrentTrack() : null;
        boolean isCurrent = cur != null && cur.equals(track);
        state.title = localizeTrackField(track.displayTitle(),
                com.airmusic.player.library.Track.UNKNOWN_TITLE, R.string.unknown_title);
        state.artist = localizeTrackField(track.displayArtist(),
                com.airmusic.player.library.Track.UNKNOWN_ARTIST, R.string.unknown_artist);
        state.album = localizeTrackField(track.displayAlbum(),
                com.airmusic.player.library.Track.UNKNOWN_ALBUM, R.string.unknown_album);
        state.durationMs = (int) track.durationMs;
        // Keep the previous cover until the new one is ready; clearing it
        // here made the player screen flash to the placeholder on every
        // track switch. Tracks without embedded art clear to the placeholder
        // once the async metadata read finishes below.
		publish();

        final Track target = track;
        metadataExecutor.execute(() -> {
            String title = null;
            String artist = null;
            String album = null;
            long duration = target.durationMs;
            byte[] artBytes = null;

            MediaMetadataRetriever retriever = MusicLibrary.openRetriever(this, target.uri);
            if (retriever != null) {
                try {
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (dur != null) {
                        try {
                            duration = Long.parseLong(dur);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    artBytes = retriever.getEmbeddedPicture();
                } catch (Exception e) {
                    Log.w(TAG, "metadata failed", e);
                } finally {
                    try {
                        retriever.release();
                    } catch (Exception ignored) {
                    }
                }
            }

            final Bitmap art = artBytes == null ? null : BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length);
            final String fTitle = title;
            final String fArtist = artist;
            final String fAlbum = album;
            final long fDuration = duration;
            final byte[] fArtBytes = artBytes;
            lastArtBytes = artBytes;

            // Push metadata to multi-room receivers (master role).
            if (multiRoomManager != null && multiRoomManager.hasTargets()) {
                multiRoomManager.sendMeta(fTitle, fArtist, fAlbum, fDuration);
                if (fArtBytes != null && fArtBytes.length > 0) {
                    multiRoomManager.sendArt(fArtBytes);
                }
            }

            main.post(() -> {
                // A pending metadata read for a local track must never
                // overwrite the AirPlay session info.
                if (state.source != PlayerUiState.Source.LOCAL) return;
                Track current = localPlayer.getCurrentTrack();
                if (current == null || !current.equals(target)) return;
                if (fTitle != null && fTitle.trim().length() > 0) state.title = fTitle.trim();
                if (fArtist != null && fArtist.trim().length() > 0) state.artist = fArtist.trim();
                if (fAlbum != null && fAlbum.trim().length() > 0) state.album = fAlbum.trim();
                if (fDuration > 0) state.durationMs = (int) fDuration;
                state.art = art;
                publish();
            });
        });
    }

    /**
     * Restores the last played local track (and position) after the app
     * starts. When {@code autoplay} is false the track is loaded and shown
     * but kept paused; the user can resume it with a tap.
     */
    private void resumeLastTrackIfPossible(boolean autoplay) {
        if (tracks.isEmpty()) return;
        String lastUri = prefs.getLastTrackUri();
        Track target = null;
        if (lastUri != null) {
            for (Track t : tracks) {
                if (t.uri.toString().equals(lastUri)) {
                    target = t;
                    break;
                }
            }
        }
        if (target == null) {
            target = tracks.get(0);
        }
        int index = tracks.indexOf(target);
        state.source = PlayerUiState.Source.LOCAL;
        localPlayer.setMasterGain(0f);
        localPlayer.setPlaylist(tracks, index);
        int lastPos = prefs.getLastTrackPosition();
        localPlayer.seekTo(lastPos);
        state.positionMs = lastPos;
        if (autoplay) {
            localPlayer.play();
            fadeLocalIn();
            state.playing = true;
        } else {
            state.playing = false;
        }
        loadMetadata(target);
        publish();
    }

    private void saveLastTrack() {
        if (state.source == PlayerUiState.Source.LOCAL && localPlayer.getCurrentTrack() != null) {
            prefs.setLastTrack(localPlayer.getCurrentTrack().uri.toString(), localPlayer.getPosition());
        }
    }

    // ------------------------------------------------------------------
    // Media session & notification
    // ------------------------------------------------------------------

    private void initMediaSession() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession = new MediaSessionCompat(this, "AirMusic");
        mediaSession.setSessionActivity(openPi);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                togglePlay();
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }
        });
        mediaSession.setActive(true);
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;
        int playState = state.playing
                ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED;
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playState, state.positionMs, state.playing ? 1.0f : 0f)
                .build();
        mediaSession.setPlaybackState(playbackState);

        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs);
        if (state.art != null) {
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, state.art);
        }
        mediaSession.setMetadata(meta.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private PendingIntent actionIntent(String action) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text;
        if (state.source == PlayerUiState.Source.AIRPLAY) {
            text = state.playing
                    ? getString(R.string.notification_airplay_receiving) + " · " + state.clientName
                    : getString(R.string.notification_airplay_paused);
        } else {
            text = state.playing ? state.artist : getString(R.string.notification_paused);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(state.title)
                .setContentText(text)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(new NotificationCompat.Action(
                        R.drawable.ic_prev, getString(R.string.action_prev), actionIntent(ACTION_PREV)))
                .addAction(new NotificationCompat.Action(
                        state.playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        state.playing ? getString(R.string.action_pause) : getString(R.string.action_play),
                        actionIntent(ACTION_TOGGLE)))
                .addAction(new NotificationCompat.Action(
                        R.drawable.ic_next, getString(R.string.action_next), actionIntent(ACTION_NEXT)));

        if (state.art != null) {
            builder.setLargeIcon(state.art);
        }
        if (mediaSession != null) {
            builder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    private void updateNotification() {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        try {
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException e) {
            Log.w(TAG, "notification permission missing", e);
        }
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

	private void registerUsbReceiver() {
		usbMediaReceiver = new UsbMediaReceiver();
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
		filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
		filter.addAction(Intent.ACTION_MEDIA_EJECT);
		filter.addDataScheme("file");
		ContextCompat.registerReceiver(this, usbMediaReceiver, filter,
				ContextCompat.RECEIVER_NOT_EXPORTED);
	}

    private void unregisterUsbReceiver() {
        if (usbMediaReceiver != null) {
            try {
                unregisterReceiver(usbMediaReceiver);
            } catch (Exception ignored) {
            }
            usbMediaReceiver = null;
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airmusic:playback");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void publish() {
        StateBus.get().postState(state);
        updateMediaSession();
        updateNotification();
    }

    /** Public wrapper used to refresh the UI/notification after a language change. */
    public void publishState() {
        publish();
    }

    /**
     * Re-applies the selected UI language to the service resources (the
     * service may have been started at boot in a different language) and
     * rebuilds every user-visible state string from the current playback.
     */
    public void applyUiLanguage() {
        try {
            android.content.res.Configuration config =
                    new android.content.res.Configuration(getResources().getConfiguration());
            config.setLocale(com.airmusic.player.util.LocaleHelper.toLocale(
                    new Prefs(this).getLanguage()));
            getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        } catch (Throwable ignored) {
        }
        if (state.source == PlayerUiState.Source.LOCAL) {
            Track cur = localPlayer != null ? localPlayer.getCurrentTrack() : null;
            if (cur != null) {
                state.title = localizeTrackField(cur.displayTitle(),
                        com.airmusic.player.library.Track.UNKNOWN_TITLE, R.string.unknown_title);
                state.artist = localizeTrackField(cur.displayArtist(),
                        com.airmusic.player.library.Track.UNKNOWN_ARTIST, R.string.unknown_artist);
                state.album = localizeTrackField(cur.displayAlbum(),
                        com.airmusic.player.library.Track.UNKNOWN_ALBUM, R.string.unknown_album);
            }
        } else if (state.source == PlayerUiState.Source.AIRPLAY) {
            state.title = airMetaTitle.length() > 0 ? airMetaTitle
                    : getString(R.string.source_airplay);
            state.artist = airMetaArtist.length() > 0 ? airMetaArtist : state.clientName;
            state.album = airMetaAlbum.length() > 0 ? airMetaAlbum
                    : getString(R.string.playback_from_sender);
        } else if (state.source == PlayerUiState.Source.REMOTE) {
            state.title = multiRoomMetaTitle.length() > 0
                    ? multiRoomMetaTitle : getString(R.string.source_remote);
            state.artist = multiRoomMetaArtist;
            state.album = multiRoomMetaAlbum;
        } else {
            state.title = getString(R.string.source_idle);
            state.artist = "";
            state.album = "";
        }
        int count = multiRoomManager != null ? multiRoomManager.targetCount() : 0;
        state.statusText = count > 0
                ? getString(R.string.playback_multicast_count, count) : "";
        publish();
    }
}
