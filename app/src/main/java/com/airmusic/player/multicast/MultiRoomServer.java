package com.airmusic.player.multicast;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Passive receiving side of multi-room sync. Every device runs one of these;
 * the master's {@link MultiRoomClient}s connect to it and push control /
 * metadata / (later) audio frames.
 */
public class MultiRoomServer {

    public interface Listener {
        void onMessage(String json);

        void onArt(byte[] imageData);

        void onAudio(byte[] pcm, long posMs);

        /** The master closed the connection (device deselected / went away). */
        void onDisconnect();
    }

    private static final String TAG = "MultiRoomServer";

    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mr-accept");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mr-server-control");
        t.setDaemon(true);
        return t;
    });
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile Listener listener;
    private long audioFramesSeen;

    public void start(Listener listener) {
        this.listener = listener;
        if (running) return;
        running = true;
        acceptExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(MultiRoomProtocol.PORT));
                Log.i(TAG, "listening on port " + MultiRoomProtocol.PORT);
                while (running) {
                    Socket socket = serverSocket.accept();
                    try {
                        socket.setTcpNoDelay(true);
                    } catch (IOException ignored) {
                    }
                    Log.i(TAG, "accepted connection from " + socket.getInetAddress());
                    Connection conn = new Connection(socket);
                    connections.add(conn);
                    conn.start();
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "server stopped: " + e);
                }
            }
        });
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Connection c : connections) {
            c.close();
        }
        connections.clear();
        acceptExecutor.shutdownNow();
        controlExecutor.shutdownNow();
    }

    /**
     * Receiver -> master control path: writes a JSON frame back over the
     * connection(s) opened by the master (a device normally has one). Runs
     * on a background thread because this is called from the UI thread.
     */
    public void sendToMaster(String json) {
        final String msg = json;
        controlExecutor.execute(() -> {
            for (Connection c : connections) {
                c.sendJson(msg);
            }
        });
    }

    private class Connection {
        private final Socket socket;
        private final Thread thread;
        private final Object writeLock = new Object();
        private OutputStream out;

        Connection(Socket socket) {
            this.socket = socket;
            try {
                out = socket.getOutputStream();
            } catch (IOException e) {
                Log.w(TAG, "getOutputStream failed", e);
            }
            this.thread = new Thread(this::run, "mr-conn");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        void sendJson(String json) {
            if (out == null) return;
            synchronized (writeLock) {
                try {
                    MultiRoomProtocol.writeFrame(out, MultiRoomProtocol.TYPE_JSON,
                            json.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Log.w(TAG, "reply failed: " + e.getMessage());
                    close();
                }
            }
        }

        private void run() {
            try (InputStream in = socket.getInputStream()) {
                while (running) {
                    MultiRoomProtocol.Frame frame = MultiRoomProtocol.readFrame(in);
                    if (frame == null) break;
                    if (frame.type != MultiRoomProtocol.TYPE_AUDIO
                            || audioFramesSeen++ % 200 == 0) {
                        Log.i(TAG, "frame type=" + frame.type + " len=" + frame.payload.length
                                + " from " + socket.getInetAddress());
                    }
                    handleFrame(frame);
                }
            } catch (Throwable t) {
                if (running) Log.d(TAG, "connection closed: " + t.getMessage());
            } finally {
                connections.remove(this);
                close();
                Listener l = listener;
                if (l != null) l.onDisconnect();
            }
        }

        private void handleFrame(MultiRoomProtocol.Frame frame) {
            try {
                if (frame.type == MultiRoomProtocol.TYPE_JSON) {
                    String json = new String(frame.payload, StandardCharsets.UTF_8);
                    // NTP-style time-sync request: reply immediately so the
                    // master can measure this device's clock offset.
                    try {
                        JSONObject o = new JSONObject(json);
                        if ("ts_req".equals(o.optString("cmd"))) {
                            long t1 = o.getLong("t1");
                            long t2 = System.currentTimeMillis();
                            sendJson("{\"cmd\":\"ts_resp\",\"t1\":" + t1
                                    + ",\"t2\":" + t2
                                    + ",\"t3\":" + System.currentTimeMillis() + "}");
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (listener != null) listener.onMessage(json);
                } else if (frame.type == MultiRoomProtocol.TYPE_ART) {
                    if (listener != null) listener.onArt(frame.payload);
                } else if (frame.type == MultiRoomProtocol.TYPE_AUDIO && frame.payload.length >= 4) {
                    long posMs = ((long) (frame.payload[0] & 0xff) << 24)
                            | ((long) (frame.payload[1] & 0xff) << 16)
                            | ((long) (frame.payload[2] & 0xff) << 8)
                            | (frame.payload[3] & 0xff);
                    byte[] pcm = new byte[frame.payload.length - 4];
                    System.arraycopy(frame.payload, 4, pcm, 0, pcm.length);
                    if (listener != null) listener.onAudio(pcm, posMs);
                }
            } catch (Throwable t) {
                Log.w(TAG, "frame handling failed", t);
            }
        }
    }
}
