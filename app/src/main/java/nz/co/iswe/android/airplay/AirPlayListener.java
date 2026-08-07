/*
 * This file is part of AirReceiver / DroidAirPlay (GPL-3.0).
 */
package nz.co.iswe.android.airplay;

/**
 * Callbacks for AirPlay (RAOP) playback session lifecycle.
 */
public interface AirPlayListener {

	/**
	 * A sender started streaming (RECORD received).
	 *
	 * @param clientName   device name from the X-Apple-Client-Name header
	 * @param dacpId       DACP-ID header, used for DACP remote control
	 * @param activeRemote Active-Remote header, required for DACP commands
	 * @param remoteIp     IP address of the sending device
	 */
	void onAirPlaySessionStart(String clientName, String dacpId, String activeRemote, String remoteIp);

	/** The sender paused / flushed the stream. */
	void onAirPlaySessionPause();

	/** The sender resumed streaming after a pause. */
	void onAirPlaySessionResume();

	/** The sender stopped / disconnected (TEARDOWN or connection closed). */
	void onAirPlaySessionStop();

	/** Sender requested a volume change. Value is in dB, 0 = unity. */
	void onAirPlayVolume(float volumeDb);
}
