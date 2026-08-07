package com.airmusic.player.airplay;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern PLAY_STATUS_PATTERN = Pattern.compile(
            "<key>dacp\\.playstatus</key>\\s*<integer>(\\d+)</integer>",
            Pattern.CASE_INSENSITIVE);

    public interface PlayStatusCallback {
        /** @param status 4 = playing, 3 = paused, 2 = stopped, -1 = unknown */
        void onPlayStatus(int status);
    }

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

    /**
     * Queries the sender's current play state via DACP
     * {@code playstatusupdate?revision-number=0}. A revision of 0 makes the
     * sender answer immediately with the current state. The reply is a binary
     * DMAP structure: {@code cmst} container with a {@code caps} uint field
     * (4 = playing, 3 = paused, 2 = stopped).
     */
    public void playStatus(final PlayStatusCallback callback) {
        if (released) return;
        executor.execute(() -> {
            if (released) return;
            ensureDiscovered();
            InetSocketAddress addr = address.get();
            if (addr == null) {
                if (callback != null) callback.onPlayStatus(-1);
                return;
            }
            byte[] response = requestBytes(addr, "playstatusupdate?revision-number=0");
            int status = parsePlayStatus(response);
            if (callback != null) callback.onPlayStatus(status);
        });
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
        requestBytes(addr, path);
    }

    /**
     * Sends a DACP GET and returns the raw response (headers + body), or
     * {@code null} on failure. Reads up to Content-Length so it returns
     * promptly instead of waiting for the socket to close.
     */
    private byte[] requestBytes(InetSocketAddress addr, String path) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(addr, SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(1500);
            String request = "GET /ctrl-int/1/" + path + " HTTP/1.1\r\n"
                    + "Active-Remote: " + activeRemote + "\r\n"
                    + "Viewer-Only-Client: 1\r\n"
                    + "Host: " + addr.getAddress().getHostAddress() + ":" + addr.getPort() + "\r\n"
                    + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes("UTF-8"));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int headerEnd = -1;
            int contentLength = -1;
            long deadline = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < deadline && total < 65536) {
                int n = in.read(buf);
                if (n < 0) break;
                response.write(buf, 0, n);
                total += n;
                if (contentLength < 0 && total >= 4) {
                    String head = new String(response.toByteArray(), 0, Math.min(total, 8192), "ISO-8859-1");
                    int idx = head.indexOf("\r\n\r\n");
                    if (idx >= 0) {
                        headerEnd = idx + 4;
                        contentLength = parseContentLength(head.substring(0, idx));
                    }
                }
                if (contentLength >= 0 && headerEnd >= 0 && total >= headerEnd + contentLength) {
                    break;
                }
            }
            Log.i(TAG, "DACP request " + path + " -> " + addr + " (" + total + " bytes)");
            return response.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "DACP request failed: " + path, t);
            return null;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static int parseContentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    return Integer.parseInt(line.substring(idx + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    /**
     * Parses the DMAP reply. Returns the play status (4 = playing, 3 = paused,
     * 2 = stopped) or -1 when it cannot be determined.
     */
    private static int parsePlayStatus(byte[] data) {
        if (data == null) return -1;
        logHex(data);

        // Binary DMAP: cmst (playstatus container) -> caps (uint play state).
        Integer raw = findDmapUIntInContainer(data, "cmst", "caps");
        if (raw != null) return raw;

        // Fallback for senders that reply with a text/plist body.
        String body = new String(data, 0, data.length, StandardCharsets.UTF_8);
        Matcher matcher = PLAY_STATUS_PATTERN.matcher(body);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static Integer findDmapUIntInContainer(byte[] data, String containerTag, String uintTag) {
        int p = 0;
        while (p + 8 <= data.length) {
            String tag = new String(data, p, 4, StandardCharsets.US_ASCII);
            int len = readBigEndianInt(data, p + 4);
            if (len < 0) return null;
            int payloadStart = p + 8;
            int payloadEnd = Math.min(payloadStart + len, data.length);
            if (tag.equals(containerTag)) {
                Integer value = findDmapUInt(data, payloadStart, payloadEnd, uintTag, 0);
                if (value != null) return value;
            }
            int next = payloadStart + len;
            if (next <= p) return null;
            p = next;
        }
        return null;
    }

    private static Integer findDmapUInt(byte[] data, int start, int end, String tag, int depth) {
        if (depth > 6 || start < 0 || end > data.length || end - start < 8) return null;
        int p = start;
        while (p + 8 <= end) {
            String itemTag = new String(data, p, 4, StandardCharsets.US_ASCII);
            int len = readBigEndianInt(data, p + 4);
            if (len < 0) return null;
            int payloadStart = p + 8;
            int payloadEnd = Math.min(payloadStart + len, end);
            if (itemTag.equals(tag) && payloadEnd - payloadStart >= 4) {
                return readBigEndianInt(data, payloadStart);
            }
            if (payloadEnd > payloadStart) {
                Integer nested = findDmapUInt(data, payloadStart, payloadEnd, tag, depth + 1);
                if (nested != null) return nested;
            }
            int next = payloadStart + len;
            if (next <= p) return null;
            p = next;
        }
        return null;
    }

    private static int readBigEndianInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    /** Logs the raw reply in hex so the DMAP structure is visible in logcat. */
    private static void logHex(byte[] data) {
        try {
            int logLen = Math.min(data.length, 256);
            StringBuilder hex = new StringBuilder(logLen * 2);
            for (int i = 0; i < logLen; i++) {
                hex.append(String.format("%02x", data[i] & 0xff));
            }
            Log.i(TAG, "DACP playstatusupdate (" + data.length + " bytes): " + hex);
        } catch (Throwable ignored) {
        }
    }

}
