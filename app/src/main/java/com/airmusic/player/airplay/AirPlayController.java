package com.airmusic.player.airplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.airmusic.player.util.AndroidLogHandler;
import com.airmusic.player.util.DiagnosticLog;

import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import nz.co.iswe.android.airplay.AirPlayListener;
import nz.co.iswe.android.airplay.AirPlayServer;

/**
 * Wraps the GPL AirPlay (RAOP) engine: manages its lifecycle, the Wi-Fi
 * multicast lock and forwards session events to the app.
 */
public class AirPlayController {

    private static final String TAG = "AirPlayController";
    private static final long HEALTH_CHECK_INITIAL_DELAY_MS = 8000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 30000;
    private static final long RESTART_DELAY_MS = 3000;

    public interface Events {
        void onSessionStart(String clientName, String dacpId, String activeRemote, String remoteIp);

		void onSessionPause();

		void onSessionResume();

        void onSessionStop();

        void onVolume(float volumeDb);

        /** Track metadata from classic AirPlay (any field may be null). */
        default void onTrackInfo(String title, String artist, String album, Bitmap art, long durationMs) {
        }

        /** Playback progress (milliseconds). */
        default void onProgress(long positionMs, long durationMs) {
        }

        /** Play/pause state reported by the sender. */
        default void onPlayState(boolean playing) {
        }
    }

    private final Context context;
    private final Events events;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager.MulticastLock multicastLock;
    private Thread engineThread;
    private volatile String deviceName = "";
    private BroadcastReceiver connectivityReceiver;

    /**
     * Restarts the AirPlay service once the network is available after a
     * failed boot-time start.
     */
    private final Runnable delayedRestart = new Runnable() {
        @Override
        public void run() {
            healAirPlayService();
        }
    };

    /**
     * Periodic self-heal: if the service is supposed to run but mDNS/RTSP is
     * not actually up (e.g. Wi-Fi was not ready at boot), restart it.
     */
    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (isNetworkAvailable()) {
                healAirPlayService();
            }
            handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
        }
    };

    public AirPlayController(Context context, Events events) {
        this.context = context.getApplicationContext();
        this.events = events;
        installLogging();
    }

    private static void installLogging() {
        try {
            Logger root = Logger.getLogger("");
            int before = 0;
            int removed = 0;
            for (java.util.logging.Handler h : root.getHandlers()) {
                before++;
                // Android ships a default handler that also prints to logcat;
                // keep only ours so every line is logged exactly once.
                root.removeHandler(h);
                removed++;
            }
            root.addHandler(new AndroidLogHandler());
            DiagnosticLog.i(TAG, "installLogging: removed " + removed + " default handler(s) (before=" + before + ")");
            Logger.getLogger("nz.co.iswe.android.airplay").setLevel(Level.INFO);
            Logger.getLogger("org.phlo.AirReceiver").setLevel(Level.WARNING);
            Logger.getLogger("javax.jmdns").setLevel(Level.WARNING);
        } catch (Throwable t) {
            Log.w(TAG, "log config failed", t);
        }
    }

	public synchronized void start(String deviceName) {
		if (started.get()) return;
		this.deviceName = deviceName == null ? "" : deviceName;

		WifiManager wifi = (WifiManager) context.getApplicationContext()
				.getSystemService(Context.WIFI_SERVICE);
		if (wifi != null) {
			multicastLock = wifi.createMulticastLock("airmusic-airplay");
			multicastLock.setReferenceCounted(false);
			multicastLock.acquire();
		}

		AirPlayServer server = AirPlayServer.getIstance();
		server.setDeviceName(deviceName);
		server.setListener(new AirPlayListener() {
            @Override
            public void onAirPlaySessionStart(String clientName, String dacpId, String activeRemote, String remoteIp) {
                if (events != null) events.onSessionStart(clientName, dacpId, activeRemote, remoteIp);
            }

			@Override
			public void onAirPlaySessionPause() {
				if (events != null) events.onSessionPause();
			}

			@Override
			public void onAirPlaySessionResume() {
				if (events != null) events.onSessionResume();
			}

            @Override
            public void onAirPlaySessionStop() {
                if (events != null) events.onSessionStop();
            }

            @Override
            public void onAirPlayVolume(float volumeDb) {
                if (events != null) events.onVolume(volumeDb);
			}

			@Override
			public void onAirPlayMetadata(String title, String artist, String album) {
				if (events != null) events.onTrackInfo(title, artist, album, null, -1);
			}

			@Override
			public void onAirPlayCoverArt(byte[] imageData) {
				try {
					Bitmap bmp = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
					if (events != null) events.onTrackInfo(null, null, null, bmp, -1);
				} catch (Throwable t) {
					Log.w(TAG, "cover art decode failed", t);
				}
			}

			@Override
			public void onAirPlayProgress(long positionMs, long durationMs) {
				if (events != null) events.onProgress(positionMs, durationMs);
			}

			@Override
			public void onAirPlayPlayState(boolean playing) {
				if (events != null) events.onPlayState(playing);
			}
		});

		started.set(true);
		registerNetworkWatcher();
		handler.removeCallbacks(healthCheck);
		handler.postDelayed(healthCheck, HEALTH_CHECK_INITIAL_DELAY_MS);
		engineThread = new Thread(() -> {
			try {
				server.startService();
				DiagnosticLog.i(TAG, "AirPlay engine started");
			} catch (Throwable t) {
				DiagnosticLog.e(TAG, "Failed to start AirPlay engine: " + t);
			}
		}, "airplay-engine");
		engineThread.start();
	}

	public synchronized void stop() {
		handler.removeCallbacks(healthCheck);
		handler.removeCallbacks(delayedRestart);
		unregisterNetworkWatcher();
		try {
			AirPlayServer.getIstance().stopService();
		} catch (Throwable t) {
            DiagnosticLog.w(TAG, "stop failed: " + t);
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (Throwable ignored) {
            }
		}
		multicastLock = null;
		engineThread = null;
		started.set(false);
	}

	public synchronized void restart(String deviceName) {
		restartCount.incrementAndGet();
		stop();
		handler.removeCallbacks(delayedRestart);
		// Give the old listener time to fully release port 5000 before rebinding.
		handler.postDelayed(() -> {
			if (!started.get()) {
				start(deviceName);
			}
		}, 1000);
	}

    public boolean isStarted() {
        return started.get();
    }

    public void pauseReceiverOutput() {
        AirPlayServer.getIstance().pauseReceiverOutput();
    }

	public void resumeReceiverOutput() {
		AirPlayServer.getIstance().resumeReceiverOutput();
	}

	/** Sets the left/right balance (-1 = full left, 0 = center, +1 = full right). */
	public void setBalance(float balance) {
		AirPlayServer.getIstance().setBalance(balance);
	}

	/** Sets an extra output gain in [0, 1] (fade control). */
	public void setVolumeGain(float gain) {
		AirPlayServer.getIstance().setVolumeGain(gain);
	}

	/**
	 * Returns the timestamp of the last received AirPlay audio packet, or 0
	 * if no audio has been received yet (watchdog for phone-side pause).
	 */
	public long getLastAudioPacketTime() {
		return AirPlayServer.getIstance().getLastAudioPacketTime();
	}

	/**
	 * True when the AirPlay server is fully usable: RTSP bound and the
	 * AirTunes service published via mDNS so senders can find it.
	 */
	public boolean isAirPlayRunning() {
		AirPlayServer server = AirPlayServer.getIstance();
		return server.isStarted() && server.isRtspBound() && server.isMdnsRegistered();
	}

	/** True when the device currently has an active network connection. */
	public boolean isNetworkAvailable() {
		try {
			ConnectivityManager cm = (ConnectivityManager) context
					.getSystemService(Context.CONNECTIVITY_SERVICE);
			if (cm == null) return false;
			NetworkInfo info = cm.getActiveNetworkInfo();
			return info != null && info.isConnected();
		} catch (Throwable t) {
			// When in doubt, allow the service to try starting.
			return true;
		}
	}

	private void registerNetworkWatcher() {
		if (connectivityReceiver != null) return;
		IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
		connectivityReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				AirPlayController.this.onConnectivityChanged();
			}
		};
		ContextCompat.registerReceiver(context, connectivityReceiver, filter,
				ContextCompat.RECEIVER_NOT_EXPORTED);
	}

	private void unregisterNetworkWatcher() {
		if (connectivityReceiver != null) {
			try {
				context.unregisterReceiver(connectivityReceiver);
			} catch (Exception ignored) {
			}
			connectivityReceiver = null;
		}
	}

	private void onConnectivityChanged() {
		if (isNetworkAvailable()) {
			handler.removeCallbacks(delayedRestart);
			handler.postDelayed(delayedRestart, RESTART_DELAY_MS);
		}
	}

	/**
	 * Heals the AirPlay service:
	 * <ul>
	 * <li>RTSP up but mDNS missing (the usual boot case) -> re-publish mDNS
	 * only, without touching the listener or its port.</li>
	 * <li>RTSP also down -> full restart.</li>
	 * </ul>
	 */
	private void healAirPlayService() {
		if (!started.get() || isAirPlayRunning()) return;
		AirPlayServer server = AirPlayServer.getIstance();
		if (server.isRtspBound() && !server.isMdnsRegistered()) {
			DiagnosticLog.i(TAG, "RTSP up but mDNS missing, re-publishing mDNS");
			boolean ok = server.repairMdns();
			DiagnosticLog.i(TAG, "mDNS repair result: " + ok);
		} else {
			DiagnosticLog.w(TAG, "AirPlay service not healthy, restarting");
			restart(deviceName);
		}
	}

	/**
	 * Human-readable status of the AirPlay service, used by the settings
	 * screen to show whether the receiver is really discoverable.
	 */
	public String getStatusText() {
		AirPlayServer server = AirPlayServer.getIstance();
		StringBuilder sb = new StringBuilder();
		sb.append("service: ").append(started.get() ? "running" : "stopped").append('\n');
		sb.append("rtsp: ").append(server.isRtspBound() ? "bound" : "not bound").append('\n');
		sb.append("mdns: ").append(server.isMdnsRegistered() ? "registered" : "not registered").append('\n');
		sb.append("network: ").append(isNetworkAvailable() ? "connected" : "disconnected").append('\n');
		sb.append("auto restarts: ").append(restartCount.get());
		return sb.toString();
	}

	/** Ends the active AirPlay session so the app can take playback back. */
	public void disconnectSession() {
		AirPlayServer.getIstance().disconnectSession();
	}
}
