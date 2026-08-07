package com.airmusic.player.airplay;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.airmusic.player.util.AndroidLogHandler;

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
    private WifiManager.MulticastLock multicastLock;
    private Thread engineThread;

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

	/** Ends the active AirPlay session so the app can take playback back. */
	public void disconnectSession() {
		AirPlayServer.getIstance().disconnectSession();
	}
}
