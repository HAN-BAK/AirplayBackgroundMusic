package com.airmusic.player.multicast;

import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates multi-room sync. Every device runs a {@link MultiRoomServer}
 * and advertises itself over mDNS. The device playing local audio becomes the
 * master: it connects a {@link MultiRoomClient} to each selected receiver and
 * pushes metadata / control (and later PCM audio). Receivers update their UI.
 */
public class MultiRoomManager {

    public interface Events {
        void onRemoteMeta(String title, String artist, String album, long durationMs);

        void onRemoteArt(byte[] imageData);

        void onRemotePlay(int positionMs);

        void onRemotePause();

        void onRemoteStop();

        void onRemoteFlush();

        void onTargetsChanged(int count);

        void onRemoteFormat(int sampleRate, int channels);

        void onRemoteClock(long masterPosMs, long masterWallMs, long offsetMs, long masterLatencyMs);

        void onRemoteAudio(byte[] pcm, long posMs);

        void onRemoteLatencyComp(int ms);

        /** Receiver pressed a transport control; master should execute it. */
        void onRemoteControl(String action, int positionMs);

        /** The master disconnected this receiver. */
        void onRemoteDisconnect();
    }

    private static final String TAG = "MultiRoomManager";

    private final MultiRoomServer server = new MultiRoomServer();
    private final MultiRoomDiscovery discovery = new MultiRoomDiscovery();
    private final CopyOnWriteArrayList<MultiRoomClient> targets = new CopyOnWriteArrayList<>();
    private final ExecutorService connectExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mr-connect");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mr-send");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mr-control");
        t.setDaemon(true);
        return t;
    });

    private volatile Events events;
    private volatile boolean started;
    /** Receivers connected when AirPlay pre-empted the session. */
    private volatile List<MultiRoomDiscovery.DeviceInfo> rememberedDevices = new ArrayList<>();

    public synchronized void start(String deviceName, Events events) {
        if (started) return;
        this.events = events;
        started = true;
        lastDeviceName = deviceName == null ? "" : deviceName;
        server.start(new MultiRoomServer.Listener() {
            @Override
            public void onMessage(String json) {
                handleJson(json);
            }

            @Override
            public void onArt(byte[] imageData) {
                Events e = MultiRoomManager.this.events;
                if (e != null) e.onRemoteArt(imageData);
            }

            @Override
            public void onAudio(byte[] pcm, long posMs) {
                Events e = MultiRoomManager.this.events;
                if (e != null) e.onRemoteAudio(pcm, posMs);
            }

            @Override
            public void onDisconnect() {
                Events e = MultiRoomManager.this.events;
                if (e != null) e.onRemoteDisconnect();
            }
        });
        discovery.register(deviceName);
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        clearTargets();
        server.stop();
        discovery.stop();
        sendExecutor.shutdownNow();
        controlExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Discovery (for the device dialog)
    // ------------------------------------------------------------------

    public void addDiscoveryListener(MultiRoomDiscovery.Listener l) {
        discovery.addListener(l);
    }

    public void removeDiscoveryListener(MultiRoomDiscovery.Listener l) {
        discovery.removeListener(l);
    }

    public List<MultiRoomDiscovery.DeviceInfo> getDevices() {
        return discovery.getDevices();
    }

    public void rescanDevices() {
        discovery.rescan();
    }

    public String getDeviceName() {
        return lastDeviceName;
    }

    private volatile String lastDeviceName = "";

    // ------------------------------------------------------------------
    // Master side: targets + pushing metadata/control
    // ------------------------------------------------------------------

    public boolean hasTargets() {
        return !targets.isEmpty();
    }

    public int targetCount() {
        return targets.size();
    }

    /** Connects to the selected receivers (async). */
    public void setTargets(List<MultiRoomDiscovery.DeviceInfo> devices) {
        clearTargets();
        if (devices == null || devices.isEmpty()) {
            notifyTargetsChanged();
            return;
        }
        for (MultiRoomDiscovery.DeviceInfo d : devices) {
            connectExecutor.execute(() -> {
                for (String addr : d.addresses) {
                    MultiRoomClient client = new MultiRoomClient(d.name, addr, d.port);
                    attachControlListener(client);
                    if (client.connect()) {
                        targets.add(client);
                        notifyTargetsChanged();
                        break;
                    }
                }
            });
        }
    }

    /** True when a receiver with this (normalized) device name is connected. */
    public boolean isTargetConnected(String name) {
        if (name == null) return false;
        for (MultiRoomClient c : targets) {
            if (name.equals(c.getName())) return true;
        }
        return false;
    }

    /**
     * Incremental target update: keeps existing connections, connects newly
     * selected receivers and disconnects deselected ones (no reconnect).
     */
    public void updateTargets(List<MultiRoomDiscovery.DeviceInfo> selected) {
        StringBuilder sb = new StringBuilder("updateTargets selected=[");
        if (selected != null) {
            for (MultiRoomDiscovery.DeviceInfo d : selected) {
                sb.append(d.name).append(",");
            }
        }
        sb.append("] targets=[");
        for (MultiRoomClient c : targets) {
            sb.append(c.getName()).append(",");
        }
        sb.append("]");
        Log.i(TAG, sb.toString());
        for (MultiRoomClient c : new ArrayList<>(targets)) {
            boolean keep = false;
            if (selected != null) {
                for (MultiRoomDiscovery.DeviceInfo d : selected) {
                    if (d.name.equals(c.getName())) {
                        keep = true;
                        break;
                    }
                }
            }
            if (!keep) {
                Log.i(TAG, "disconnecting receiver '" + c.getName() + "'");
                c.close();
                targets.remove(c);
            }
        }
        if (selected != null) {
            for (MultiRoomDiscovery.DeviceInfo d : selected) {
                if (isTargetConnected(d.name)) continue;
                connectExecutor.execute(() -> {
                    for (String addr : d.addresses) {
                        MultiRoomClient client = new MultiRoomClient(d.name, addr, d.port);
                        attachControlListener(client);
                        if (client.connect()) {
                            targets.add(client);
                            notifyTargetsChanged();
                            break;
                        }
                    }
                });
            }
        }
        notifyTargetsChanged();
    }

    private void attachControlListener(MultiRoomClient client) {
        client.setControlListener((action, pos) -> {
            if ("disconnectMe".equals(action)) {
                // Receiver asked to be removed: drop it so the checkbox
                // unchecks and the connection closes (receiver exits).
                client.close();
                targets.remove(client);
                notifyTargetsChanged();
                return;
            }
            Events e = MultiRoomManager.this.events;
            if (e != null) e.onRemoteControl(action, pos);
        });
    }

    public void clearTargets() {
        for (MultiRoomClient c : targets) {
            c.close();
        }
        targets.clear();
        notifyTargetsChanged();
    }

    /**
     * Disconnects every receiver but remembers them, so the master can
     * reconnect them after an AirPlay interruption ends.
     */
    public void disconnectAllAndRemember() {
        List<MultiRoomDiscovery.DeviceInfo> remembered = new ArrayList<>();
        for (MultiRoomClient c : targets) {
            remembered.add(new MultiRoomDiscovery.DeviceInfo(
                    c.getName(), new String[]{c.getHost()}, MultiRoomProtocol.PORT));
        }
        rememberedDevices = remembered;
        clearTargets();
    }

    /** Reconnects the receivers remembered before an AirPlay interruption. */
    public void reconnectRemembered() {
        List<MultiRoomDiscovery.DeviceInfo> list = rememberedDevices;
        if (list == null || list.isEmpty()) return;
        rememberedDevices = new ArrayList<>();
        updateTargets(list);
    }

    private void notifyTargetsChanged() {
        Events e = events;
        if (e != null) e.onTargetsChanged(targets.size());
    }

    public void sendMeta(String title, String artist, String album, long durationMs) {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", "meta");
            o.put("title", title == null ? "" : title);
            o.put("artist", artist == null ? "" : artist);
            o.put("album", album == null ? "" : album);
            o.put("durationMs", durationMs);
            broadcast(o.toString());
        } catch (Throwable t) {
            Log.w(TAG, "sendMeta failed", t);
        }
    }

    public void sendArt(byte[] imageData) {
        if (imageData == null || imageData.length == 0) return;
        final byte[] data = imageData;
        sendExecutor.execute(() -> {
            for (MultiRoomClient c : targets) {
                c.sendArt(data);
            }
        });
    }

    public void sendPlay(int positionMs) {
        broadcast("{\"cmd\":\"play\",\"positionMs\":" + positionMs + "}");
    }

    public void sendPause() {
        broadcast("{\"cmd\":\"pause\"}");
    }

    public void sendStop() {
        broadcast("{\"cmd\":\"stop\"}");
    }

    /** Tells receivers to drop buffered audio (after a seek / new stream). */
    public void sendFlush() {
        broadcast("{\"cmd\":\"flush\"}");
    }

    /** Tells receivers to apply the fixed output-latency compensation. */
    public void sendLatencyComp(int ms) {
        broadcast("{\"cmd\":\"latencyComp\",\"ms\":" + ms + "}");
    }

    /** Receiver -> master: transport command (play/pause/toggle/next/prev/seek). */
    public void sendControlToMaster(String action, int positionMs) {
        server.sendToMaster("{\"cmd\":\"control\",\"action\":\""
                + action + "\",\"pos\":" + positionMs + "}");
    }

    /** Sends the decoded PCM format once per track. */
    public void sendFormat(int sampleRate, int channels) {
        broadcast("{\"cmd\":\"format\",\"sr\":" + sampleRate + ",\"ch\":" + channels + "}");
    }

    /** Sends one PCM chunk (100 ms) with its track position. */
    public void sendAudio(byte[] pcm, long posMs) {
        if (pcm == null || pcm.length == 0) return;
        final byte[] payload = new byte[pcm.length + 4];
        payload[0] = (byte) (posMs >>> 24);
        payload[1] = (byte) (posMs >>> 16);
        payload[2] = (byte) (posMs >>> 8);
        payload[3] = (byte) posMs;
        System.arraycopy(pcm, 0, payload, 4, pcm.length);
        sendExecutor.execute(() -> {
            for (MultiRoomClient c : targets) {
                c.send(MultiRoomProtocol.TYPE_AUDIO, payload);
            }
        });
    }

    /**
     * Sends one clock sample to every receiver, using each receiver's own
     * NTP-measured clock offset.
     */
    public void sendClock(long streamPosMs, long masterLatencyMs) {
        final long pos = streamPosMs;
        final long lat = masterLatencyMs;
        controlExecutor.execute(() -> {
            for (MultiRoomClient c : targets) {
                c.sendClock(pos, lat);
            }
        });
    }

    /** Asks every receiver to participate in a time-sync round. */
    public void sendTsRequests() {
        controlExecutor.execute(() -> {
            for (MultiRoomClient c : targets) {
                c.sendTsRequest();
            }
        });
    }

    /**
     * Post-switch latency calibration: discard the cached offset and run a
     * few quick NTP rounds so receivers re-anchor to the new stream promptly.
     */
    public void forceTimeSync() {
        for (MultiRoomClient c : targets) {
            c.forceOffsetRefresh();
        }
        for (int i = 0; i < 3; i++) {
            controlExecutor.execute(() -> {
                for (MultiRoomClient c : targets) {
                    c.sendTsRequest();
                }
            });
        }
    }

    private void broadcast(String json) {
        final String message = json;
        sendExecutor.execute(() -> {
            for (MultiRoomClient c : targets) {
                c.sendJson(message);
            }
        });
    }

    // ------------------------------------------------------------------
    // Receiver side: incoming messages update the UI
    // ------------------------------------------------------------------

    private void handleJson(String json) {
        final Events e = events;
        if (e == null) return;
        try {
            JSONObject o = new JSONObject(json);
            String cmd = o.optString("cmd");
            Log.i(TAG, "received command: " + cmd);
            switch (cmd) {
                case "meta":
                    e.onRemoteMeta(
                            o.optString("title", null),
                            o.optString("artist", null),
                            o.optString("album", null),
                            o.optLong("durationMs", -1));
                    break;
                case "play":
                    e.onRemotePlay(o.optInt("positionMs", 0));
                    break;
                case "pause":
                    e.onRemotePause();
                    break;
                case "stop":
                    e.onRemoteStop();
                    break;
                case "flush":
                    e.onRemoteFlush();
                    break;
                case "format":
                    e.onRemoteFormat(o.optInt("sr", 44100), o.optInt("ch", 2));
                    break;
                case "clock":
                    long cPos = o.optLong("pos", 0);
                    long cT = o.optLong("t", 0);
                    long cOff = o.optLong("off", 0);
                    long cLat = o.optLong("lat", 0);
                    long cAge = System.currentTimeMillis() - (cT - cOff);
                    Log.d(TAG, "clock pos=" + cPos + " t=" + cT + " off=" + cOff
                            + " lat=" + cLat + " age=" + cAge + "ms");
                    e.onRemoteClock(cPos, cT, cOff, cLat);
                    break;
                case "latencyComp":
                    e.onRemoteLatencyComp(o.optInt("ms", 0));
                    break;
                case "disconnect":
                    e.onRemoteDisconnect();
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "bad json: " + json, t);
        }
    }
}
