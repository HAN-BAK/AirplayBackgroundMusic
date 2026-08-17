/*
 * This file is part of AirReceiver / DroidAirPlay (GPL-3.0).
 *
 * AirReceiver is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AirReceiver is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AirReceiver.  If not, see <http://www.gnu.org/licenses/>.
 */

package nz.co.iswe.android.airplay;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import nz.co.iswe.android.airplay.audio.RaopAudioHandler;
import nz.co.iswe.android.airplay.network.NetworkUtils;
import nz.co.iswe.android.airplay.network.raop.RaopRtspPipelineFactory;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;

/**
 * AirTunes/RAOP (AirPlay 1) server.
 *
 * Adapted from DroidAirPlay / AirReceiver. This version can be started and
 * stopped multiple times, advertises a configurable device name over mDNS and
 * reports playback session events through {@link AirPlayListener}.
 */
public class AirPlayServer implements Runnable {

	private static final Logger LOG = Logger.getLogger(AirPlayServer.class.getName());

	/**
	 * The AirTunes/RAOP service type
	 */
	static final String AIR_TUNES_SERVICE_TYPE = "_raop._tcp.local.";

	/**
	 * The AirTunes/RAOP M-DNS service properties (TXT record).
	 * cn=0,1 advertises PCM + Apple Lossless, which makes iOS send ALAC.
	 */
	static final Map<String, String> AIRTUNES_SERVICE_PROPERTIES = map(
		"txtvers", "1",
		"tp", "UDP",
		"ch", "2",
		"ss", "16",
		"sr", "44100",
		"pw", "false",
		"sm", "false",
		"sv", "false",
		"ek", "1",
		"et", "0,1",
		"cn", "0,1",
		// Request classic AirPlay metadata: 0 = text, 1 = artwork, 2 = progress.
		// Without this flag iOS does not send cover art / song info / progress.
		"md", "0,1,2",
		"vn", "3"
	);

	private static AirPlayServer instance;

	/** Listener for playback session events (start/pause/stop/volume). */
	private volatile AirPlayListener listener;

	/** Device name shown to AirPlay senders. */
	private volatile String deviceName = "Android音箱";

	/** The AirTunes/RAOP RTSP port. */
	private int rtspPort = 5000;

	/** Global executor service. */
	protected ExecutorService executorService;

	/** Channel execution handler. */
	protected ExecutionHandler channelExecutionHandler;

	/** All open RTSP channels. */
	protected ChannelGroup channelGroup;

	/** JmDNS responders (one per network interface). */
	protected List<JmDNS> jmDNSInstances;
	/** Auxiliary services already registered per JmDNS instance ("type|name"). */
	private final java.util.Map<JmDNS, java.util.Set<String>> auxiliaryRegistered =
			new java.util.HashMap<JmDNS, java.util.Set<String>>();

	/** The RTSP server bootstrap. */
	private ServerBootstrap rtspBootstrap;

	/** The RTSP server channel. */
	private org.jboss.netty.channel.Channel rtspChannel;

	/** Currently active audio handler (per RTSP connection). */
	private volatile RaopAudioHandler currentAudioHandler;

	/** Number of network interfaces on which the mDNS service was registered. */
	private volatile int mdnsRegistrationCount;

	/** Left/right balance, -1 = full left, 0 = center, +1 = full right. */
	private volatile float balance;

	/** Extra output gain in [0, 1] for smooth volume transitions. */
	private volatile float volumeGain = 1.0f;

	private boolean started;

	public static AirPlayServer getInstance() {
		return getIstance();
	}

	public static AirPlayServer getIstance() {
		if (instance == null) {
			instance = new AirPlayServer();
		}
		return instance;
	}

	private AirPlayServer() {
		jmDNSInstances = new java.util.LinkedList<JmDNS>();
	}

	public void setListener(final AirPlayListener listener) {
		this.listener = listener;
	}

	public AirPlayListener getListener() {
		return listener;
	}

	public void setDeviceName(final String deviceName) {
		if (deviceName != null && deviceName.trim().length() > 0) {
			this.deviceName = deviceName.trim();
		}
	}

	public String getDeviceName() {
		return deviceName;
	}

	public int getRtspPort() {
		return rtspPort;
	}

	public void setRtspPort(final int rtspPort) {
		this.rtspPort = rtspPort;
	}

	public boolean isStarted() {
		return started;
	}

	/**
	 * True when the AirTunes service was successfully published on at least
	 * one network interface. If false, senders cannot discover this device.
	 */
	public boolean isMdnsRegistered() {
		return mdnsRegistrationCount > 0;
	}

	/** True when the RTSP listener is actually bound. */
	public boolean isRtspBound() {
		return rtspChannel != null && rtspChannel.isBound();
	}

	/**
	 * Starts the RTSP server and registers the mDNS service. Safe to call
	 * multiple times; repeated calls while running are ignored.
	 */
	public synchronized void startService() {
		if (started) {
			LOG.info("AirPlay server already started");
			return;
		}

		executorService = Executors.newCachedThreadPool();
		channelExecutionHandler = new ExecutionHandler(new OrderedMemoryAwareThreadPoolExecutor(4, 0, 0));
		channelGroup = new DefaultChannelGroup();

		final ServerBootstrap bootstrap = new ServerBootstrap(
			new NioServerSocketChannelFactory(executorService, executorService));
		bootstrap.setPipelineFactory(new RaopRtspPipelineFactory());
		bootstrap.setOption("reuseAddress", true);
		bootstrap.setOption("child.tcpNoDelay", true);
		bootstrap.setOption("child.keepAlive", true);

		try {
			rtspChannel = bootstrap.bind(new InetSocketAddress(Inet4Address.getByName("0.0.0.0"), rtspPort));
			channelGroup.add(rtspChannel);
			rtspBootstrap = bootstrap;
			LOG.info("Launched RTSP service on port " + rtspPort);
		} catch (final Throwable e) {
			LOG.log(Level.SEVERE, "Failed to bind RTSP bootstrap on port " + rtspPort, e);
			// Clean up everything so a later retry can actually succeed.
			if (rtspChannel != null) {
				try {
					rtspChannel.close();
				} catch (final Throwable ignored) {
				}
				rtspChannel = null;
			}
			try {
				bootstrap.releaseExternalResources();
			} catch (final Throwable ignored) {
			}
			try {
				executorService.shutdown();
			} catch (final Throwable ignored) {
			}
			if (channelExecutionHandler != null) {
				try {
					channelExecutionHandler.releaseExternalResources();
				} catch (final Throwable ignored) {
				}
			}
			channelGroup = null;
			rtspBootstrap = null;
			executorService = null;
			channelExecutionHandler = null;
			started = false;
			return;
		}

		mdnsRegistrationCount = registerMdns();
		started = true;
	}

	/**
	 * Re-publishes the AirTunes mDNS service without touching the RTSP
	 * listener. Used when the receiver is up but senders cannot discover it
	 * (e.g. Wi-Fi became ready after boot).
	 *
	 * @return true when at least one interface registered successfully.
	 */
	public synchronized boolean repairMdns() {
		if (!isRtspBound()) {
			LOG.warning("Cannot repair mDNS: RTSP listener is not bound");
			return false;
		}
		mdnsRegistrationCount = registerMdns();
		LOG.info("mDNS repair finished: " + mdnsRegistrationCount + " interface(s)");
		return isMdnsRegistered();
	}

	/**
	 * Registers the AirTunes service on every up, non-loopback interface.
	 *
	 * @return the number of interfaces on which registration succeeded (0
	 *         usually means Wi-Fi was not ready yet when this was called).
	 */
	private int registerMdns() {
		closeMdnsInstances();
		int registered = 0;

		try {
			final NetworkUtils networkUtils = NetworkUtils.getInstance();
			final String hardwareAddressString = networkUtils.getHardwareAddressString();
			final String serviceName = hardwareAddressString + "@" + deviceName;
			synchronized (jmDNSInstances) {
				for (final NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
					if (iface.isLoopback() || iface.isPointToPoint() || !iface.isUp()) {
						continue;
					}
					for (final InetAddress addr : Collections.list(iface.getInetAddresses())) {
						if (!(addr instanceof Inet4Address) && !(addr instanceof Inet6Address)) {
							continue;
						}
						try {
							final JmDNS jmDNS = JmDNS.create(addr, serviceName + "-jmdns");
							jmDNSInstances.add(jmDNS);
							final ServiceInfo airTunesServiceInfo = ServiceInfo.create(
								AIR_TUNES_SERVICE_TYPE,
								serviceName,
								rtspPort,
								0, 0,
								AIRTUNES_SERVICE_PROPERTIES);
							jmDNS.registerService(airTunesServiceInfo);
							LOG.info("Registered AirTunes service '" + airTunesServiceInfo.getName() + "' on " + addr);
							registered++;
						} catch (final Throwable e) {
							LOG.log(Level.SEVERE, "Failed to publish service on " + addr, e);
						}
					}
				}
			}
		} catch (final SocketException e) {
			LOG.log(Level.SEVERE, "Failed to register mDNS services", e);
		}
		LOG.info("mDNS registration completed on " + registered + " interface(s)");
		return registered;
	}

	/**
	 * Stops the RTSP server and unregisters mDNS services. Safe to call when
	 * not running.
	 */
	public synchronized void stopService() {
		if (!started) {
			return;
		}

		if (currentAudioHandler != null) {
			try {
				currentAudioHandler.stopSession();
			} catch (final Throwable t) {
				LOG.log(Level.WARNING, "Failed to stop active audio session", t);
			}
			currentAudioHandler = null;
		}

		ChannelGroupFuture allChannelsClosed = null;
		if (channelGroup != null) {
			allChannelsClosed = channelGroup.close();
		}

		closeMdnsInstances();

		if (allChannelsClosed != null) {
			allChannelsClosed.awaitUninterruptibly();
		}
		if (rtspBootstrap != null) {
			try {
				rtspBootstrap.releaseExternalResources();
			} catch (final Throwable t) {
				LOG.log(Level.WARNING, "Failed to release RTSP bootstrap resources", t);
			}
		}
		if (executorService != null) {
			executorService.shutdown();
		}
		if (channelExecutionHandler != null) {
			channelExecutionHandler.releaseExternalResources();
		}
		channelExecutionHandler = null;
		executorService = null;
		channelGroup = null;
		rtspChannel = null;
		rtspBootstrap = null;
		mdnsRegistrationCount = 0;
		started = false;
		LOG.info("AirPlay server stopped");
	}

	/** Closes all registered mDNS instances so the service can be re-published. */
	private void closeMdnsInstances() {
		synchronized (jmDNSInstances) {
			for (final JmDNS jmDNS : jmDNSInstances) {
				try {
					jmDNS.unregisterAllServices();
					jmDNS.close();
					LOG.info("Unregistered services on " + jmDNS.getInterface());
				} catch (final IOException e) {
					LOG.log(Level.WARNING, "Failed to unregister some services", e);
				}
			}
			jmDNSInstances.clear();
			auxiliaryRegistered.clear();
		}
	}

	/**
	 * Registers an auxiliary mDNS service (e.g. multi-room sync) on the same
	 * JmDNS instances used by the AirTunes service, so it is announced on
	 * every usable interface without port-5353 conflicts.
	 *
	 * @return the number of interfaces on which registration succeeded
	 */
	public int registerAuxiliaryService(final String serviceType, final String instanceName,
										final int port, final java.util.Map<String, String> txt) {
		int registered = 0;
		synchronized (jmDNSInstances) {
			for (final JmDNS jmDNS : jmDNSInstances) {
				final String key = serviceType + "|" + instanceName;
				java.util.Set<String> set = auxiliaryRegistered.get(jmDNS);
				if (set == null) {
					set = new java.util.HashSet<String>();
					auxiliaryRegistered.put(jmDNS, set);
				}
				if (set.contains(key)) {
					continue; // already registered on this instance -- no rename
				}
				try {
					final ServiceInfo info = ServiceInfo.create(serviceType, instanceName, port, 0, 0,
							txt != null ? txt : java.util.Collections.<String, String>emptyMap());
					jmDNS.registerService(info);
					set.add(key);
					registered++;
				} catch (final Throwable e) {
					LOG.log(Level.WARNING, "Failed to register auxiliary service", e);
				}
			}
		}
		return registered;
	}

	/** Returns one JmDNS instance (for browsing); null if not registered yet. */
	public JmDNS getPrimaryJmDNS() {
		synchronized (jmDNSInstances) {
			if (!jmDNSInstances.isEmpty()) {
				return jmDNSInstances.get(0);
			}
		}
		return null;
	}

	/** Snapshot of all active JmDNS instances (one per network interface). */
	public java.util.List<JmDNS> getJmDNSInstances() {
		synchronized (jmDNSInstances) {
			return new java.util.ArrayList<JmDNS>(jmDNSInstances);
		}
	}

	/** Registers the audio handler of the current RTSP connection. */
	public void attachAudioHandler(final RaopAudioHandler handler) {
		this.currentAudioHandler = handler;
		if (handler != null) {
			handler.setBalance(balance);
			handler.setVolumeGain(volumeGain);
		}
	}

	/**
	 * Sets the left/right balance for the receiver output and applies it to
	 * the active session.
	 */
	public void setBalance(final float balance) {
		this.balance = Math.max(-1.0f, Math.min(1.0f, balance));
		final RaopAudioHandler handler = currentAudioHandler;
		if (handler != null) {
			handler.setBalance(this.balance);
		}
	}

	public float getBalance() {
		return balance;
	}

	/**
	 * Returns the timestamp of the last received AirPlay audio packet, or 0
	 * if no audio has been received yet (watchdog for phone-side pause).
	 */
	public long getLastAudioPacketTime() {
		final RaopAudioHandler handler = currentAudioHandler;
		return handler == null ? 0L : handler.getLastAudioPacketTime();
	}

	/** Sets an extra output gain in [0, 1] (fade control). */
	public void setVolumeGain(final float gain) {
		this.volumeGain = Math.max(0.0f, Math.min(1.0f, gain));
		final RaopAudioHandler handler = currentAudioHandler;
		if (handler != null) {
			handler.setVolumeGain(this.volumeGain);
		}
	}

	/** Ends the active AirPlay session (closes the RTSP connection). */
	public void disconnectSession() {
		final RaopAudioHandler handler = currentAudioHandler;
		if (handler != null) {
			handler.disconnectSession();
		}
	}

	/**
	 * Pauses the receiver-side audio output (used by the app UI when the user
	 * pauses an AirPlay session locally).
	 */
	public void pauseReceiverOutput() {
		final RaopAudioHandler handler = currentAudioHandler;
		if (handler != null) {
			handler.pauseReceiverOutput();
		}
	}

	/** Resumes the receiver-side audio output. */
	public void resumeReceiverOutput() {
		final RaopAudioHandler handler = currentAudioHandler;
		if (handler != null) {
			handler.resumeReceiverOutput();
		}
	}

	@Override
	public void run() {
		startService();
	}

	public ChannelHandler getChannelExecutionHandler() {
		return channelExecutionHandler;
	}

	public ChannelGroup getChannelGroup() {
		return channelGroup;
	}

	public ExecutorService getExecutorService() {
		return executorService;
	}

	private static Map<String, String> map(final String... keysValues) {
		assert keysValues.length % 2 == 0;
		final Map<String, String> map = new java.util.HashMap<String, String>(keysValues.length / 2);
		for (int i = 0; i < keysValues.length; i += 2) {
			map.put(keysValues[i], keysValues[i + 1]);
		}
		return Collections.unmodifiableMap(map);
	}
}
