package com.airmusic.player.multicast;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Master-side PCM streamer. Decodes a local audio file with MediaCodec and
 * sends fixed-size PCM chunks stamped with the track position (ms) over the
 * multi-room connections. Decoding is paced so it never runs more than about
 * one second ahead of the local player's position.
 */
public class MultiRoomStreamer {

    public interface PositionProvider {
        long getPositionMs();
    }

    /** Receives the decoded PCM locally (master loopback) as it is sent. */
    public interface Sink {
        void onFormat(int sampleRate, int channels);

        void onChunk(byte[] pcm, long posMs);
    }

    private static final String TAG = "MultiRoomStreamer";
    private static final int CHUNK_MS = 100;
    private static final int LEAD_MS = 1200;

    private final Context context;
    private final Uri uri;
    private final MultiRoomManager manager;
    private final PositionProvider positionProvider;
    private final long startPosMs;
    private final long startWallMs;

    private volatile boolean running;
    private Thread thread;
    private volatile Sink localSink;

    public MultiRoomStreamer(Context context, Uri uri, MultiRoomManager manager,
                             long startPosMs, PositionProvider positionProvider,
                             long startWallMs) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.manager = manager;
        this.startPosMs = startPosMs;
        this.positionProvider = positionProvider;
        this.startWallMs = startWallMs > 0
                ? startWallMs : System.currentTimeMillis() - startPosMs;
    }

    public Uri getUri() {
        return uri;
    }

    public long getStartWallMs() {
        return startWallMs;
    }

    public long getStartPosMs() {
        return startPosMs;
    }

    /**
     * Master's virtual stream position right now. Chunk P is produced at
     * wall time startWallMs + P, so the produced position at wall time t is
     * t - startWallMs (startWallMs already encodes startPos).
     */
    public long getStreamPositionMs() {
        return Math.max(0, System.currentTimeMillis() - startWallMs);
    }

    public void setLocalSink(Sink sink) {
        this.localSink = sink;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "mr-streamer");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void run() {
        Log.i(TAG, "streamer started for " + uri + " startPos=" + startPosMs
                + "ms startWall=" + startWallMs + "ms");
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        long chunksSent = 0;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(context, uri, null);
            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                Log.w(TAG, "no audio track");
                return;
            }
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            extractor.selectTrack(trackIndex);
            if (startPosMs > 0) {
                extractor.seekTo(startPosMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }

            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            try {
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING,
                            android.media.AudioFormat.ENCODING_PCM_16BIT);
                }
            } catch (Throwable ignored) {
            }
            codec.configure(format, null, null, 0);
            codec.start();
            try {
                MediaFormat outFormat = codec.getOutputFormat();
                if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        && outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    int sr = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int ch = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    Log.i(TAG, "codec output format: sr=" + sr + " ch=" + ch);
                    manager.sendFormat(sr, ch);
                    Sink sink = localSink;
                    if (sink != null) sink.onFormat(sr, ch);
                }
            } catch (Throwable ignored) {
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long presentationUs = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            long iterations = 0;

            while (running) {
                iterations++;
                if (iterations % 100 == 1) {
                    Log.i(TAG, "loop presUs=" + presentationUs + " inputDone=" + inputDone);
                }

                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int size = extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                        codec.releaseOutputBuffer(outIdx, false);
                        break;
                    }
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        byte[] pcm = new byte[info.size];
                        outBuf.get(pcm);
                        long posMs = info.presentationTimeUs / 1000L;
                        if (posMs < startPosMs - 5) {
                            // Pre-roll audio before the seek target: skip so
                            // receivers don't play a short burst of old audio.
                            codec.releaseOutputBuffer(outIdx, false);
                            continue;
                        }
                        try {
                            sendChunk(pcm, posMs);
                            chunksSent++;
                            if (chunksSent % 50 == 1) {
                                Log.i(TAG, "sent " + chunksSent + " chunks, pos=" + posMs + "ms");
                            }
                            presentationUs = info.presentationTimeUs;
                            // Pace at real-time using the decode clock: chunk
                            // with position P leaves at wall time startWall + P
                            // so every device shares one timeline.
                            long expectedWall = startWallMs + posMs;
                            long sleepMs = expectedWall - System.currentTimeMillis();
                            if (sleepMs > 10) {
                                Thread.sleep(Math.min(sleepMs, 250));
                            }
                        } finally {
                            codec.releaseOutputBuffer(outIdx, false);
                        }
                    } else {
                        codec.releaseOutputBuffer(outIdx, false);
                    }
                }
                if (outputDone && inputDone) break;
            }
        } catch (Throwable t) {
            if (running) Log.w(TAG, "stream error", t);
        } finally {
            Log.i(TAG, "streamer done, chunks sent: " + chunksSent);
            if (codec != null) {
                try {
                    codec.stop();
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void sendChunk(byte[] pcm, long posMs) {
        if (!running) return;
        Sink sink = localSink;
        if (sink != null) sink.onChunk(pcm, posMs);
        if (manager.hasTargets()) manager.sendAudio(pcm, posMs);
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }
}
