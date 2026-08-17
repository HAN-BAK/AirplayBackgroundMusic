package com.airmusic.player.multicast;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Framing + message types for the multi-room sync protocol.
 *
 * Every device runs a {@link MultiRoomServer} on a fixed port. The device
 * that plays local audio acts as the master and opens a {@link MultiRoomClient}
 * to each selected receiver. Frames:
 *
 *   [1 byte type][4 byte big-endian length][payload]
 *
 * Types: 1 = JSON control, 2 = PCM audio chunk (reserved), 3 = cover art.
 */
public final class MultiRoomProtocol {

    public static final int PORT = 47100;
    public static final String SERVICE_TYPE = "_airmusic-sync._tcp.local.";

    public static final byte TYPE_JSON = 1;
    public static final byte TYPE_AUDIO = 2;
    public static final byte TYPE_ART = 3;

    private MultiRoomProtocol() {
    }

    public static void writeFrame(OutputStream out, byte type, byte[] payload) throws IOException {
        out.write(type);
        out.write((payload.length >>> 24) & 0xff);
        out.write((payload.length >>> 16) & 0xff);
        out.write((payload.length >>> 8) & 0xff);
        out.write(payload.length & 0xff);
        out.write(payload);
        out.flush();
    }

    /** Reads one frame; returns null when the stream ends cleanly. */
    public static Frame readFrame(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) return null;
        byte[] lenBytes = new byte[4];
        int got = 0;
        while (got < 4) {
            int n = in.read(lenBytes, got, 4 - got);
            if (n < 0) return null;
            got += n;
        }
        int len = ((lenBytes[0] & 0xff) << 24) | ((lenBytes[1] & 0xff) << 16)
                | ((lenBytes[2] & 0xff) << 8) | (lenBytes[3] & 0xff);
        if (len < 0 || len > 16 * 1024 * 1024) {
            throw new IOException("invalid frame length " + len);
        }
        byte[] payload = new byte[len];
        got = 0;
        while (got < len) {
            int n = in.read(payload, got, len - got);
            if (n < 0) return null;
            got += n;
        }
        return new Frame((byte) type, payload);
    }

    public static final class Frame {
        public final byte type;
        public final byte[] payload;

        Frame(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
