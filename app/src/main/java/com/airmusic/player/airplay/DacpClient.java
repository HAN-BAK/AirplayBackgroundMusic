package com.airmusic.player.airplay;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * DACP (Digital Audio Control Protocol) client.
 *
 * When an iPhone/iPad/Mac streams audio via AirPlay 1, it advertises a
 * {@code _dacp._tcp.local.} mDNS service and sends its {@code DACP-ID} and
 * {@code Active-Remote} values in the RTSP session headers. This client
 * discovers that service and sends HTTP commands back to the sender so the
 * receiver can control playback (next / previous / play-pause) without
 * ending the AirPlay session.
 */
public class DacpClient {

    private static final String TAG = "DacpClient";
    private static final String SERVICE_TYPE = "_dacp._tcp.local.";
    private static final int DEFAULT_PORT = 3689;
    private static final long DISCOVERY_TIMEOUT_MS = 2000;
    private static final int SOCKET_TIMEOUT_MS = 2000;

    private final String dacpId;
    private final String activeRemote;
    private final String remoteIp;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<InetSocketAddress> address = new AtomicReference<>(null);
    private volatile boolean discoveryAttempted;
    private volatile boolean released;

    public DacpClient(String dacpId, String activeRemote, String remoteIp) {
        this.dacpId = dacpId == null ? "" : dacpId.trim();
        this.activeRemote = activeRemote == null ? "" : activeRemote.trim();
        this.remoteIp = remoteIp;
    }

    /** Starts asynchronous mDNS discovery of the sender's DACP service. */
    public void startDiscovery() {
        if (released) return;
        executor.execute(() -> {
            if (released) return;
            try {
                discover();
            } catch (Throwable t) {
                Log.w(TAG, "discovery error", t);
            }
        });
    }

    /** Sends "next track" to the AirPlay sender. */
    public void next() {
        command("nextitem");
    }

    /** Sends "previous track" to the AirPlay sender. */
    public void previous() {
        command("previtem");
    }

    /** Sends play/pause toggle to the AirPlay sender. */
    public void playPause() {
        command("playpause");
    }

    /** Shuts down the client; no further commands are sent. */
    public void release() {
        released = true;
        executor.shutdownNow();
    }

    private void command(final String path) {
        if (released) return;
        executor.execute(() -> {
            if (released) return;
            ensureDiscovered();
            InetSocketAddress addr = address.get();
            if (addr == null) {
                Log.w(TAG, "DACP address unavailable, command dropped: " + path);
                return;
            }
            send(addr, path);
        });
    }

    private void ensureDiscovered() {
        if (address.get() != null || discoveryAttempted) return;
        discoveryAttempted = true;
        try {
            discover();
        } catch (Throwable t) {
            Log.w(TAG, "discovery error", t);
        }
        if (address.get() == null && remoteIp != null && !remoteIp.isEmpty()) {
            Log.i(TAG, "DACP mDNS discovery failed, falling back to port " + DEFAULT_PORT);
            address.set(new InetSocketAddress(remoteIp, DEFAULT_PORT));
        }
    }

    /**
     * Browses {@code _dacp._tcp.local.} and picks the service matching the
     * DACP-ID (or, if no ID was sent, the sender's IP address).
     */
    private void discover() throws IOException {
        if (remoteIp == null || remoteIp.isEmpty()) {
            Log.w(TAG, "no remote IP, cannot discover DACP");
            return;
        }
        JmDNS jmdns = JmDNS.create();
        try {
            ServiceInfo[] infos = jmdns.list(SERVICE_TYPE, DISCOVERY_TIMEOUT_MS);
            String target = dacpId.toUpperCase(Locale.US);
            ServiceInfo idMatch = null;
            ServiceInfo ipMatch = null;
            for (ServiceInfo info : infos) {
                if (!dacpId.isEmpty() && target.length() > 0
                        && info.getQualifiedName().toUpperCase(Locale.US).contains(target)) {
                    idMatch = info;
                    break;
                }
                if (matchesIp(info, remoteIp) && ipMatch == null) {
                    ipMatch = info;
                }
            }
            ServiceInfo chosen = idMatch != null ? idMatch : ipMatch;
            if (chosen != null) {
                int port = chosen.getPort() > 0 ? chosen.getPort() : DEFAULT_PORT;
                Log.i(TAG, "DACP service found: " + chosen.getQualifiedName() + " port " + port);
                address.set(new InetSocketAddress(remoteIp, port));
            } else {
                Log.w(TAG, "no matching DACP service found");
            }
        } finally {
            try {
                jmdns.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean matchesIp(ServiceInfo info, String ip) {
        String[] addresses = info.getHostAddresses();
        if (addresses == null) return false;
        for (String a : addresses) {
            if (a != null && a.equals(ip)) return true;
        }
        return false;
    }

    private void send(InetSocketAddress addr, String path) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(addr, SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            String request = "GET /ctrl-int/1/" + path + " HTTP/1.1\r\n"
                    + "Active-Remote: " + activeRemote + "\r\n"
                    + "Host: " + addr.getAddress().getHostAddress() + ":" + addr.getPort() + "\r\n"
                    + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes("UTF-8"));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[1024];
            try {
                in.read(buf);
            } catch (IOException ignored) {
                // response is not parsed; a successful round trip is enough
            }
            Log.i(TAG, "DACP command sent: " + path + " -> " + addr);
        } catch (Throwable t) {
            Log.w(TAG, "DACP command failed: " + path, t);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

}
