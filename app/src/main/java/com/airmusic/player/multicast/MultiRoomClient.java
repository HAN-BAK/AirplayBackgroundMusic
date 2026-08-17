package com.airmusic.player.multicast;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Master-side connection to one multi-room receiver. Sends JSON control /
 * metadata frames and PCM audio chunks, and runs an NTP-style time-sync
 * handshake so the master can compute this receiver's clock offset
 * (masterWall - receiverWall).
 */
public class MultiRoomClient {

    public interface ControlListener {
        void onControl(String action, int positionMs);
    }

    private static final String TAG = "MultiRoomClient";

    private final String name;
    private final String host;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private volatile long lastOffsetMs;
    private volatile long bestRttMs = Long.MAX_VALUE;
    private volatile long lastGoodTimeMs;
    private volatile boolean offsetValid;
    private volatile ControlListener controlListener;
    private Socket socket;
    private OutputStream out;
    private InputStream in;

    public MultiRoomClient(String name, String host, int port) {
        this.name = name == null ? host : name;
        this.host = host;
        this.port = port;
    }

    public String getName() {
        return name;
    }

    public String getHost() {
        return host;
    }

    public void setControlListener(ControlListener listener) {
        this.controlListener = listener;
    }

    /** Connects and sends the "hello" message; call from a background thread. */
    public boolean connect() {
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 3000);
            out = socket.getOutputStream();
            in = socket.getInputStream();
            sendJson("{\"cmd\":\"hello\",\"role\":\"master\"}");
            Thread reader = new Thread(this::readLoop, "mr-client-read");
            reader.setDaemon(true);
            reader.start();
            Log.i(TAG, "connected to " + host + ":" + port);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "connect failed to " + host + ":" + port + " - " + t.getMessage());
            close();
            return false;
        }
    }

    public void sendJson(String json) {
        send(MultiRoomProtocol.TYPE_JSON, json.getBytes(StandardCharsets.UTF_8));
    }

    public void sendArt(byte[] imageData) {
        send(MultiRoomProtocol.TYPE_ART, imageData);
    }

    /** NTP-style request; the receiver replies so we can measure the offset. */
    public void sendTsRequest() {
        long t1 = System.currentTimeMillis();
        send(MultiRoomProtocol.TYPE_JSON,
                ("{\"cmd\":\"ts_req\",\"t1\":" + t1 + "}").getBytes(StandardCharsets.UTF_8));
    }

    /** Forces the next time-sync reply to refresh the stored offset. */
    public void forceOffsetRefresh() {
        bestRttMs = Long.MAX_VALUE;
    }

    /** Clock sample: stream position + send time + this receiver's offset. */
    public void sendClock(long posMs, long masterLatencyMs) {
        if (!offsetValid) return; // wait for the first NTP measurement
        long t = System.currentTimeMillis();
        send(MultiRoomProtocol.TYPE_JSON,
                ("{\"cmd\":\"clock\",\"pos\":" + posMs + ",\"t\":" + t
                        + ",\"lat\":" + masterLatencyMs
                        + ",\"off\":" + lastOffsetMs + "}").getBytes(StandardCharsets.UTF_8));
        long after = System.currentTimeMillis();
        Log.d(TAG, host + " clock pos=" + posMs + " t=" + t
                + " off=" + lastOffsetMs + " lat=" + masterLatencyMs
                + " sendTook=" + (after - t) + "ms");
    }

    public long getLastOffsetMs() {
        return lastOffsetMs;
    }

    public void send(byte type, byte[] payload) {
        if (closed.get() || out == null) return;
        synchronized (writeLock) {
            try {
                MultiRoomProtocol.writeFrame(out, type, payload);
            } catch (IOException e) {
                Log.w(TAG, "send failed: " + e.getMessage());
                close();
            }
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                MultiRoomProtocol.Frame frame = MultiRoomProtocol.readFrame(in);
                if (frame == null) break;
                if (frame.type == MultiRoomProtocol.TYPE_JSON) {
                    handleJson(new String(frame.payload, StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable t) {
            if (!closed.get()) Log.d(TAG, "read loop closed: " + t.getMessage());
        } finally {
            close();
        }
    }

    private void handleJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if ("control".equals(o.optString("cmd"))) {
                ControlListener l = controlListener;
                if (l != null) {
                    l.onControl(o.optString("action", ""),
                            o.optInt("pos", 0));
                }
                return;
            }
            if (!"ts_resp".equals(o.optString("cmd"))) return;
            long t1 = o.getLong("t1");
            long t2 = o.getLong("t2");
            long t3 = o.getLong("t3");
            long t4 = System.currentTimeMillis();
            long rtt = t4 - t1;
            if (rtt < 0 || rtt > 500) return;
            // The NTP formula yields (tabletClock - masterClock); we treat
            // offset as (masterClock - tabletClock), so flip the sign.
            long offset = -((t2 - t1) + (t3 - t4)) / 2;
            long now = System.currentTimeMillis();
            if (rtt < bestRttMs || now - lastGoodTimeMs > 5000) {
                bestRttMs = rtt;
                lastOffsetMs = offset;
                lastGoodTimeMs = now;
                offsetValid = true;
                Log.d(TAG, host + " offset=" + offset + "ms rtt=" + rtt + "ms");
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isConnected() {
        return !closed.get() && socket != null && socket.isConnected();
    }

    public void close() {
        if (closed.getAndSet(true)) return;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}
