package com.airmusic.player.multicast;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.PriorityQueue;

import com.airmusic.player.playback.FirEqualizer;

/**
 * Multi-room audio player used on both ends of a sync session.
 *
 * <p>The master plays the exact stream it pushes through its own instance of
 * this player (loopback), so every device shares a single decode chain and a
 * single timeline. The master periodically sends clock samples
 * {@code {pos, masterWall, offset, masterLatency}} where {@code offset} is an
 * NTP-style estimate of (masterWall - localWall). A chunk with track position
 * P is scheduled to be heard at
 *
 * <pre>localWall(P) = anchorLocalWall + (P - anchorPos)</pre>
 *
 * with {@code anchorLocalWall = masterWall - offset}. The writer thread
 * resamples the PCM (linear interpolation with a ratio very close to 1.0) and
 * a small PI controller compares the AudioTrack playhead against this
 * schedule, correcting clock drift without dropping or starving the buffer
 * (Snapcast-style). Output latency measured via {@link AudioTrack#getTimestamp}
 * is compensated so devices with different hardware latency stay aligned.
 */
public class MultiRoomAudioPlayer {

    private static final String TAG = "MultiRoomAudioPlayer";

    /** Debug hook: raw post-EQ 16-bit PCM capture (master loopback or
     *  receiver output), toggled via the same adb capture intents used by
     *  the local/AirPlay capture hooks. */
    private static FileOutputStream captureStream;
    private static long captureWritten;
    private static final long CAPTURE_LIMIT = 400L * 1024 * 1024;

    public static synchronized void startCapture(File file) {
        stopCapture();
        try {
            captureStream = new FileOutputStream(file);
            captureWritten = 0;
            Log.i(TAG, "capture started: " + file);
        } catch (Exception ignored) {
        }
    }

    public static synchronized void stopCapture() {
        if (captureStream != null) {
            try {
                captureStream.flush();
                captureStream.close();
            } catch (Exception ignored) {
            }
            captureStream = null;
            Log.i(TAG, "capture stopped, bytes=" + captureWritten);
        }
    }

    private void writeCapture(byte[] data, int len) {
        FileOutputStream out;
        synchronized (MultiRoomAudioPlayer.class) {
            out = captureStream;
            if (out == null) return;
            if (captureWritten + len > CAPTURE_LIMIT) {
                captureStream = null;
                try {
                    out.close();
                } catch (Exception ignored) {
                }
                return;
            }
            captureWritten += len;
        }
        try {
            out.write(data, 0, len);
        } catch (Exception ignored) {
            stopCapture();
        }
    }

    private static final int MAX_BUFFER_MS = 2000;
    /** Initial software buffer before playback starts (absorbs jitter). */
    private static final int LEAD_MS = 250;
    /**
     * How far behind the arrival stream the writer targets. The paced stream
     * only arrives at real time, so the writer can never get ahead of it;
     * keeping the write position WRITE_LEAD_MS behind the newest arrival
     * leaves the resampler room to speed up or slow down without starving.
     */
    private static final int WRITE_LEAD_MS = 250;
    /**
     * Master-loopback target lead. The master's own streamer paces chunks at
     * real time, so the loopback player's written position can only sit a
     * small queue (about one chunk) behind the stream; the receiver-style
     * WRITE_LEAD_MS + latencyComp targets (~400 ms) are unreachable and made
     * the controller pin ratio at the floor and hard-resync in a loop.
     */
    private static final int MASTER_LEAD_MS = 100;
    /** P gain: ratio shift per ms of playhead error. */
    private static final double KP = 0.00006;
    /** I gain applied to the accumulated error. */
    private static final double KI = 0.000002;
    /** Resample ratio bounds (slightly wider for quicker initial lock-in). */
    private static final double RATIO_MIN = 0.99;
    private static final double RATIO_MAX = 1.01;
    /**
     * Low-pass for ratio changes. The controller computes a new target every
     * chunk (100 ms); applying it instantly makes the resample rate jump per
     * chunk, warping the audio (audible as distortion/wow). Smoothing spreads
     * the correction over a few seconds so the content rate stays stable.
     */
    private static final double RATIO_SMOOTH = 0.02;
    /** Ratios this close to 1.0 are a pure passthrough (no interpolation). */
    private static final double RATIO_PASSTHROUGH_EPS = 0.0002;
    private static final double INTEGRAL_CLAMP_MS = 400.0;
    /** If the playhead drifts beyond this from the schedule, hard re-sync. */
    private static final long HARD_RESYNC_MS = 350;
    /** If the queue was empty longer than this, re-anchor to the next chunk. */
    private static final long REANCHOR_GAP_MS = 600;

    private final Object lock = new Object();
    private final PriorityQueue<Chunk> queue =
            new PriorityQueue<>((a, b) -> Long.compare(a.posMs, b.posMs));

    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean started;
    private volatile int sampleRate = 44100;
    private volatile int channels = 2;
    /** True while this instance is the master's loopback (not a receiver). */
    private volatile boolean masterLoopback;

    // Clock anchor (written on the main thread, read by the writer thread).
    private volatile long anchorPosMs = -1;
    private volatile long anchorLocalWallMs = -1;
    private volatile long masterLatencyMs = 0;
    /** Fixed receiver-side output-latency compensation (no UI). */
    private volatile long latencyCompMs;

    private float balance;
    private volatile float outputGain = 1f;
    private volatile FirEqualizer firEqualizer;
    private AudioTrack track;
    private Thread writerThread;

    // Resampler + playback accounting (writer thread only).
    private short[] prevFrame;
    private double phaseRel;
    private double ratio = 1.0;
    private double integral;
    private long playStartPosMs = -1;
    private long inputFramesFed;
    private long outputFramesWritten;
    private long lastLatencyMs;
    private long lastLatencyProbeMs;
    private long lastEmptyWall = -1;
    private long writtenChunks;
    private long lastLogMs;

    public MultiRoomAudioPlayer() {
        Log.i(TAG, "version=negfb3 ratio=" + (1.0 - KP * 100 - KI * 0)
                + " range=" + RATIO_MIN + ".." + RATIO_MAX);
    }

    public void start() {
        // A freshly started session must be audible: the previous role may
        // have faded this player to 0 (e.g. receiver exit), and the master
        // loopback reuses the same instance without an explicit gain reset.
        outputGain = 1f;
        if (!running) {
            running = true;
            paused = false;
            synchronized (lock) {
                queue.clear();
                lock.notifyAll();
            }
            writerThread = new Thread(this::run, "mr-player");
            writerThread.setDaemon(true);
            writerThread.start();
        } else {
            paused = false;
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Left/right balance (-1 = full left, 0 = center, +1 = full right). */
    public void setBalance(float balance) {
        this.balance = Math.max(-1f, Math.min(1f, balance));
    }

    /** Output gain in [0,1] for fade transitions. */
    public void setOutputGain(float gain) {
        outputGain = Math.max(0f, Math.min(1f, gain));
    }

    public void setFirEqualizer(FirEqualizer equalizer) {
        this.firEqualizer = equalizer;
    }

    /** Applies the decoded PCM format; resets all stream state. */
    public void setFormat(int sampleRate, int channels) {
        this.sampleRate = sampleRate > 0 ? sampleRate : this.sampleRate;
        this.channels = channels > 0 ? channels : this.channels;
        synchronized (MultiRoomAudioPlayer.class) {
            if (captureStream != null) {
                Log.i(TAG, "capture format sr=" + this.sampleRate
                        + " ch=" + this.channels);
            }
        }
        resetForStream();
    }

    /** Master loopback: anchors this player directly to the local timeline. */
    public void setLocalTimeline(long startPosMs, long startWallMs, long masterLatencyMs) {
        anchorPosMs = startPosMs;
        anchorLocalWallMs = startWallMs;
        this.masterLatencyMs = masterLatencyMs;
        masterLoopback = true;
    }

    /** Receiver side: new clock sample from the master. */
    public void updateClock(long masterPosMs, long masterWallMs, long offsetMs, long masterLatencyMs) {
        anchorPosMs = masterPosMs;
        anchorLocalWallMs = masterWallMs - offsetMs;
        this.masterLatencyMs = masterLatencyMs;
        masterLoopback = false;
    }

    /** Drops all buffered audio (seek / pause on the master). */
    public void flush() {
        synchronized (lock) {
            queue.clear();
            lock.notifyAll();
        }
    }

    /** Full stream reset: queue, anchor, track and resampler. */
    public void resetForStream() {
        synchronized (lock) {
            queue.clear();
        }
        anchorPosMs = -1;
        anchorLocalWallMs = -1;
        masterLatencyMs = 0;
        masterLoopback = false;
        stopTrack();
        resetResampler();
        started = false;
        playStartPosMs = -1;
        inputFramesFed = 0;
        outputFramesWritten = 0;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        stopTrack();
        if (writerThread != null) {
            try {
                writerThread.join(500);
            } catch (InterruptedException ignored) {
            }
            writerThread = null;
        }
        synchronized (lock) {
            queue.clear();
        }
        resetResampler();
        started = false;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public long getOutputLatencyMs() {
        return probeLatencyMs();
    }

    /** Positive values make this device play earlier (compensates a slower
     *  output chain such as Bluetooth or DSP processing). */
    public void setLatencyCompensationMs(long ms) {
        latencyCompMs = Math.max(-500, Math.min(500, ms));
    }

    public void onChunk(byte[] pcm, long posMs) {
        if (!running || pcm == null || pcm.length == 0) return;
        synchronized (lock) {
            // Drop stale pre-roll that belongs to a previous anchor.
            if (anchorPosMs > 0 && posMs < anchorPosMs - 150) return;
            queue.add(new Chunk(pcm, posMs));
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------

    private void run() {
        while (running) {
            Chunk head;
            synchronized (lock) {
                while (queue.isEmpty() && running) {
                    if (lastEmptyWall < 0) lastEmptyWall = System.currentTimeMillis();
                    try {
                        lock.wait(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!running) break;
                if (!queue.isEmpty()) {
                    if (lastEmptyWall > 0 && started
                            && System.currentTimeMillis() - lastEmptyWall > REANCHOR_GAP_MS) {
                        head = queue.peek();
                        Log.w(TAG, "re-anchoring after long gap, head=" + head.posMs + "ms");
                        playStartPosMs = head.posMs;
                        inputFramesFed = 0;
                        outputFramesWritten = 0;
                        resetResampler();
                        started = false;
                    }
                    lastEmptyWall = -1;
                }
                head = queue.peek();
                if (anchorPosMs < 0 || anchorLocalWallMs < 0) {
                    try {
                        lock.wait(30);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                if (!started) {
                    Chunk last = lastInQueue();
                    if (last == null || last.posMs - head.posMs < LEAD_MS) {
                        try {
                            lock.wait(20);
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                    started = true;
                    playStartPosMs = head.posMs;
                    inputFramesFed = 0;
                    outputFramesWritten = 0;
                    resetResampler();
                }
            }

            if (paused) {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            ensureTrack();
            if (track == null) {
                synchronized (lock) {
                    queue.poll();
                }
                continue;
            }

            updateController();
            if (track == null) continue; // hard resync recreated the track

            try {
                // Play the received stream untouched. The AudioTrack's
                // ~200 ms buffer absorbs the tiny hardware-clock skew over a
                // whole song, and the hard re-sync handles the rare cases
                // where it accumulates. Software resampling with a varying
                // ratio warps the waveform (audible as vocal flutter), so
                // it is intentionally not used for normal playback.
                byte[] out = head.pcm;
                applyGain(out);
                FirEqualizer eq = firEqualizer;
                if (eq != null) {
                    // The shared equalizer is also used by local/AirPlay, so
                    // re-assert this stream's sample rate (no-op if unchanged).
                    eq.setSampleRate(sampleRate);
                    eq.process(out, channels);
                }
                writeCapture(out, out.length);
                track.write(out, 0, out.length);
                synchronized (lock) {
                    queue.poll();
                }
                int frames = head.pcm.length / 2 / channels;
                inputFramesFed += frames;
                outputFramesWritten += out.length / 2 / channels;
                writtenChunks++;
                maybeLog(head.posMs);
            } catch (Throwable t) {
                Log.w(TAG, "write failed", t);
                synchronized (lock) {
                    queue.poll();
                }
            }
        }
    }

    private void ensureTrack() {
        if (track != null) return;
        int channelMask = channels == 1
                ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 2, sampleRate * 2 * channels / 10); // ~100 ms
        try {
            AudioTrack t = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build(),
                    bufSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            t.play();
            track = t;
            lastLatencyMs = 0;
            lastLatencyProbeMs = 0;
        } catch (Throwable t) {
            Log.w(TAG, "AudioTrack create failed", t);
        }
    }

    private void stopTrack() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.stop();
            } catch (Throwable ignored) {
            }
            try {
                t.release();
            } catch (Throwable ignored) {
            }
        }
        lastLatencyProbeMs = 0;
    }

    /**
     * Samples the playhead against the master schedule and adjusts the
     * resample ratio (Snapcast-style PI controller).
     */
    private void updateController() {
        if (track == null) return;
        if (anchorPosMs < 0 || anchorLocalWallMs < 0) return; // waiting for clock
        long now = System.currentTimeMillis();
        // Compare against the *written* position (not the AudioTrack head):
        // the write must happen at "schedule - outputLatency", and the
        // AudioTrack buffer absorbs scheduling jitter.
        long expected;
        if (masterLoopback) {
            // The master's own streamer paces at real time, so the loopback
            // written position only lags the stream by the queue span.
            expected = anchorPosMs + (now - anchorLocalWallMs) - MASTER_LEAD_MS;
        } else {
            expected = anchorPosMs + (now - anchorLocalWallMs)
                    - WRITE_LEAD_MS - masterLatencyMs + probeLatencyMs() - latencyCompMs;
        }
        long actual = playStartPosMs + inputFramesFed * 1000L / sampleRate;
        long err = actual - expected;

        if (err > HARD_RESYNC_MS || err < -HARD_RESYNC_MS) {
            Log.w(TAG, "hard resync err=" + err + "ms actual=" + actual + " expected=" + expected);
            hardResync(expected);
            return;
        }
        integral = Math.max(-INTEGRAL_CLAMP_MS,
                Math.min(INTEGRAL_CLAMP_MS, integral + err));
        // Negative feedback: a large positive err (we are ahead) slows the
        // content down; a negative err speeds it up. Keeps the error small
        // continuously instead of waiting for a hard resync.
        double target = 1.0 - KP * err - KI * integral;
        target = Math.max(RATIO_MIN, Math.min(RATIO_MAX, target));
        ratio += (target - ratio) * RATIO_SMOOTH;
    }

    private void hardResync(long expectedPosMs) {
        synchronized (lock) {
            while (!queue.isEmpty() && queue.peek().posMs < expectedPosMs - 100) {
                queue.poll();
            }
        }
        // Re-anchor to the remaining head chunk and rebuild the write lead;
        // the !started branch re-establishes playStartPos/inputFramesFed so
        // the accounting stays correct after a big clock jump.
        started = false;
        resetResampler();
        stopTrack();
    }

    private void resetResampler() {
        prevFrame = null;
        phaseRel = 0.0;
        ratio = 1.0;
        integral = 0.0;
    }

    /**
     * Linear-interpolation resampler. Keeps one frame of context across chunk
     * boundaries and a fractional phase so the output rate stays exact even
     * though input arrives in arbitrary-size chunks.
     */
    private byte[] resample(byte[] pcm) {
        int frames = pcm.length / 2 / channels;
        if (frames <= 0) return pcm;
        if (Math.abs(ratio - 1.0) < RATIO_PASSTHROUGH_EPS) {
            // Clock is locked: copy the chunk through untouched instead of
            // running every sample through the interpolator.
            return pcm;
        }
        short[] in = new short[frames * channels];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(in);

        // Worst-case output size: the lowest resample ratio (0.97) produces
        // the most output frames per input frame.
        int maxOutFrames = (int) (frames / 0.97) + 8;
        short[] out = new short[maxOutFrames * channels];
        int outFrames = 0;

        short[] pf = prevFrame;
        double phaseAbs = phaseRel + 1.0;
        while (phaseAbs < frames) {
            int i0 = (int) phaseAbs;
            double frac = phaseAbs - i0;
            int inBase = i0 * channels;
            for (int c = 0; c < channels; c++) {
                double a;
                if (i0 == 0) {
                    a = pf != null ? pf[c] : in[inBase + c];
                } else {
                    a = in[inBase - channels + c];
                }
                double b = in[inBase + c];
                out[outFrames * channels + c] = (short) Math.round(a + (b - a) * frac);
            }
            outFrames++;
            phaseAbs += ratio;
            if (outFrames >= maxOutFrames) break;
        }

        phaseRel = phaseAbs - frames - 1.0;
        prevFrame = new short[channels];
        System.arraycopy(in, (frames - 1) * channels, prevFrame, 0, channels);

        byte[] result = new byte[outFrames * 2 * channels];
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out, 0, outFrames * channels);
        return result;
    }

    /**
     * Scales every sample by the fade gain (and the left/right balance).
     * Software gain is applied per frame, so fade transitions are exact and
     * never leave buffered audio at an old (louder) level.
     */
    private void applyGain(byte[] pcm) {
        float gain = outputGain;
        boolean hasBalance = channels == 2 && Math.abs(balance) > 0.001f;
        if (!hasBalance && Math.abs(gain - 1f) < 0.001f) return;
        float lg = hasBalance && balance <= 0 ? 1f : hasBalance ? 1f - balance : 1f;
        float rg = hasBalance && balance >= 0 ? 1f : hasBalance ? 1f + balance : 1f;
        short[] s = new short[pcm.length / 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
        for (int i = 0; i < s.length; i++) {
            float g = (channels == 2 && (i & 1) == 0) ? lg * gain : (channels == 2 ? rg * gain : gain);
            s[i] = (short) Math.round(s[i] * g);
        }
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(s);
    }

    private long probeLatencyMs() {
        if (track == null) return lastLatencyMs;
        long now = System.currentTimeMillis();
        if (lastLatencyProbeMs > 0 && now - lastLatencyProbeMs < 300) {
            return lastLatencyMs;
        }
        lastLatencyProbeMs = now;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                AudioTimestamp ts = new AudioTimestamp();
                if (track.getTimestamp(ts)) {
                    long head = track.getPlaybackHeadPosition();
                    long latFrames = head - ts.framePosition;
                    long latMs = latFrames * 1000L / Math.max(1, sampleRate);
                    if (latMs >= 0 && latMs < 500
                            && (lastLatencyMs == 0 || latMs < lastLatencyMs)) {
                        lastLatencyMs = latMs;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return lastLatencyMs;
    }

    private void maybeLog(long posMs) {
        long now = System.currentTimeMillis();
        if (now - lastLogMs < 2000) return;
        lastLogMs = now;
        long expected;
        if (masterLoopback) {
            expected = anchorPosMs + (now - anchorLocalWallMs) - MASTER_LEAD_MS;
        } else {
            expected = anchorPosMs + (now - anchorLocalWallMs)
                    - WRITE_LEAD_MS - masterLatencyMs + probeLatencyMs() - latencyCompMs;
        }
        long actual = playStartPosMs + inputFramesFed * 1000L / sampleRate;
        long buffered = 0;
        synchronized (lock) {
            if (!queue.isEmpty()) buffered = lastInQueue().posMs - queue.peek().posMs;
        }
        Log.i(TAG, "pos=" + posMs + "ms actual=" + actual + " expected=" + expected
                + " err=" + (actual - expected) + " ratio="
                + String.format(Locale.US, "%.5f", ratio)
                + " buf=" + buffered + "ms lat=" + lastLatencyMs + "ms");
    }

    private Chunk lastInQueue() {
        Chunk last = null;
        for (Chunk c : queue) last = c;
        return last;
    }

    private static final class Chunk {
        final byte[] pcm;
        final long posMs;

        Chunk(byte[] pcm, long posMs) {
            this.pcm = pcm;
            this.posMs = posMs;
        }
    }
}
