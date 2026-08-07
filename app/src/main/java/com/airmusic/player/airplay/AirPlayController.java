package com.airmusic.player.airplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.airmusic.player.util.AndroidLogHandler;

import androidx.core.content.ContextCompat;

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
    }

    private final Context context;
    private final Events events;
    private final AtomicBoolean started = new AtomicBoolean(false);
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
            if (started.get() && !isAirPlayRunning()) {
                Log.i(TAG, "Network ready, restarting AirPlay service");
                restart(deviceName);
            }
        }
    };

    /**
     * Periodic self-heal: if the service is supposed to run but mDNS/RTSP is
     * not actually up (e.g. Wi-Fi was not ready at boot), restart it.
     */
    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (started.get() && !isAirPlayRunning() && isNetworkAvailable()) {
                Log.w(TAG, "AirPlay service not healthy, restarting");
                restart(deviceName);
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
            Logger.getLogger("").addHandler(new AndroidLogHandler());
            Logger.getLogger("nz.co.iswe.android.airplay").setLevel(Level.WARNING);
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
		});

		started.set(true);
		registerNetworkWatcher();
		handler.removeCallbacks(healthCheck);
		handler.postDelayed(healthCheck, HEALTH_CHECK_INITIAL_DELAY_MS);
		engineThread = new Thread(() -> {
			try {
				server.startService();
				Log.i(TAG, "AirPlay engine started");
			} catch (Throwable t) {
				Log.e(TAG, "Failed to start AirPlay engine", t);
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
            Log.w(TAG, "stop failed", t);
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
        stop();
        start(deviceName);
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
		if (started.get() && !isAirPlayRunning() && isNetworkAvailable()) {
			Log.i(TAG, "Network available, scheduling AirPlay restart");
			handler.removeCallbacks(delayedRestart);
			handler.postDelayed(delayedRestart, RESTART_DELAY_MS);
		}
	}

	/** Ends the active AirPlay session so the app can take playback back. */
	public void disconnectSession() {
		AirPlayServer.getIstance().disconnectSession();
	}
}
