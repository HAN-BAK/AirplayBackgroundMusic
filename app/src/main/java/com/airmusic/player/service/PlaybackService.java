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

import com.airmusic.player.MainActivity;
import com.airmusic.player.R;
import com.airmusic.player.airplay.AirPlayController;
import com.airmusic.player.airplay.DacpClient;
import com.airmusic.player.library.MusicLibrary;
import com.airmusic.player.library.Track;
import com.airmusic.player.playback.LocalPlayer;
import com.airmusic.player.playback.PlayMode;
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

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (state.source == PlayerUiState.Source.LOCAL && state.playing) {
                state.positionMs = localPlayer.getPosition();
                int duration = localPlayer.getDuration();
                if (duration > 0) state.durationMs = duration;
                StateBus.get().postState(state);
            }
            main.postDelayed(this, 500);
        }
    };

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
        }

        @Override
        public void onLocalStateChanged(boolean playing) {
			if (state.source == PlayerUiState.Source.LOCAL) {
				state.playing = playing;
				publish();
				saveLastTrack();
			}
        }

        @Override
        public void onLocalPlaybackEnded() {
            if (state.source == PlayerUiState.Source.LOCAL) {
				state.source = PlayerUiState.Source.IDLE;
				state.playing = false;
				publish();
			}
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
        StateBus.get().postState(state);

        initMediaSession();
        startForeground(NOTIFICATION_ID, buildNotification());

        airPlayController = new AirPlayController(this, airPlayEvents);
        airPlayController.start(prefs.getAirPlayName());

        localPlayer = new LocalPlayer(this, localListener);
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
			if (prefs.isAutoPlayOnStart() && state.source == PlayerUiState.Source.IDLE) {
				resumeLastTrackIfPossible();
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

    public void playTrack(Track track) {
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
        localPlayer.setMasterGain(0f);
        localPlayer.setPlaylist(tracks, index);
        localPlayer.play();
        fadeLocalIn();
        loadMetadata(localPlayer.getCurrentTrack());
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
        if (state.source == PlayerUiState.Source.AIRPLAY) {
            if (state.playing) {
                pauseAirPlay();
            } else {
                resumeAirPlay();
            }
            return;
        }
        if (state.source == PlayerUiState.Source.LOCAL) {
            if (localPlayer.isPlaying()) {
                fadeLocalOut(localPlayer::pause);
            } else {
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

    public void pausePlayback() {
        if (state.source == PlayerUiState.Source.LOCAL) {
            if (localPlayer.isPlaying()) {
                fadeLocalOut(localPlayer::pause);
            }
        } else if (state.source == PlayerUiState.Source.AIRPLAY && state.playing) {
            pauseAirPlay();
        }
    }

    public void next() {
        if (state.source == PlayerUiState.Source.LOCAL) {
            localPlayer.next();
        } else if (state.source == PlayerUiState.Source.AIRPLAY) {
            sendAirPlayTransport(true);
        }
    }

    public void previous() {
        if (state.source == PlayerUiState.Source.LOCAL) {
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
        if (state.source == PlayerUiState.Source.LOCAL) {
            localPlayer.seekTo(positionMs);
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

    // ------------------------------------------------------------------
    // AirPlay session handling (source switching rules)
    // ------------------------------------------------------------------

    private void handleAirPlayStart(String clientName, String dacpId, String activeRemote, String remoteIp, boolean newSession) {
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
        state.title = "AirPlay 播放";
        state.artist = state.clientName;
        state.album = "来自发送设备";
        state.art = null;
        state.positionMs = 0;
		state.durationMs = 0;
        airPlayController.setVolumeGain(0f);
        fadeAirPlayIn();
		publish();
	}

    private void handleAirPlayPause() {
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
            state.title = "未在播放";
            state.artist = "";
            state.album = "";
            state.art = null;
        }
		resumeLocalAfterAirPlay = false;
        airPlayController.setVolumeGain(1f);
		publish();
	}

	/**
	 * Fades the local player volume down to zero, then runs {@code onDone}
	 * (usually pause). Total duration 2500 ms.
	 */
	private void fadeLocalOut(Runnable onDone) {
		cancelFade();
		final float start = localFade;
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
		cancelFade();
		localFade = 0f;
		final long durationMs = 2000;
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
		cancelAirFade();
		final long durationMs = 2000;
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
        state.title = track.displayTitle();
        state.artist = track.displayArtist();
        state.album = track.displayAlbum();
        state.durationMs = (int) track.durationMs;
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

    private void resumeLastTrackIfPossible() {
        String lastUri = prefs.getLastTrackUri();
        if (lastUri != null && !tracks.isEmpty()) {
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).uri.toString().equals(lastUri)) {
                    playTrack(tracks.get(i));
                    localPlayer.seekTo(prefs.getLastTrackPosition());
                    return;
                }
            }
        }
        if (!tracks.isEmpty()) {
            playTrack(tracks.get(0));
        }
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
}
