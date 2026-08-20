package com.airmusic.player.playback;

import android.util.Log;

/**
 * 10-band graphic equalizer implemented as ten cascaded peaking biquads
 * (RBJ audio-EQ-cookbook).
 *
 * <p>This replaces the earlier band-summed FIR design whose normalisation
 * diluted each band's gain by roughly an order of magnitude, so the user's
 * curve was barely applied while the summed band-pass kernels added audible
 * artifacts on low-bitrate content. With biquads a +6.4 dB band setting
 * really produces about +6.4 dB at that frequency.</p>
 */
public final class FirEqualizer {

    /** Classic 10-band centre frequencies (Hz). */
    public static final int BANDS = 10;
    public static final double[] CENTER_FREQS = {
            31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    };

    /**
     * Lookahead limiter. EQ boosts routinely push the output beyond full
     * scale; the old per-sample tanh waveshaper smoothed those peaks but
     * also added harmonic distortion of its own. The limiter below instead
     * delays the signal by a few samples, watches the peak of the incoming
     * block and applies a stereo-linked gain reduction with a fast attack
     * and slow release, so loud peaks are tamed without reshaping the wave.
     */
    /**
     * Engage point at full scale. Mastered music already reaches ~-0.1 dBFS
     * (about 32700), so a lower threshold made the limiter act on the source
     * itself and pump on every loud passage even with a flat EQ. At full
     * scale the limiter only steps in when the EQ actually pushes peaks
     * beyond 0 dBFS.
     */
    private static final double LIMIT_THRESHOLD = 32767.0;
    /** ~5.8 ms of lookahead at 44.1 kHz: enough for the gain to settle
     *  before a transient reaches the output, so peaks never overshoot. */
    private static final int LOOKAHEAD = 256;
    /** Gain-reduction time constants (seconds). */
    private static final double ATTACK_TAU_S = 0.0003;
    private static final double RELEASE_TAU_S = 0.25;
    private static final double PI = Math.PI;

    /** Per-band biquad coefficients: b0, b1, b2, a1, a2 (already divided by a0). */
    private final double[][] coeff = new double[BANDS][5];
    /** Per-band filter state per channel: x1, x2, y1, y2. */
    private final double[][] stateL = new double[BANDS][4];
    private final double[][] stateR = new double[BANDS][4];
    /** Lookahead delay lines (post-EQ samples before the limiter). */
    private final double[] delayL = new double[LOOKAHEAD];
    private final double[] delayR = new double[LOOKAHEAD];
    /** Monotonic-deque peak tracker over the lookahead window (O(1) per
     *  sample instead of re-scanning all LOOKAHEAD slots). */
    private final int[] dqPos = new int[LOOKAHEAD + 1];
    private final double[] dqVal = new double[LOOKAHEAD + 1];
    private final boolean[] dqLive = new boolean[LOOKAHEAD];
    private int dqHead;
    private int dqTail;
    private int delayPos;
    private double limitGain = 1.0;
    /** Per-log-window limiter statistics (pumping diagnostics). */
    private double windowMinLimitGain = 1.0;
    private long windowLimitHits;
    private long windowSamples;
    private double attackCoef = 1.0;
    private double releaseCoef = 1.0;
    /**
     * Makeup gain (linear, &lt;= 1). A curve with several boosts pushes the
     * whole band up and forces the limiter to ride the gain on loud masters
     * (audible as pumping/dirt). We attenuate the output by the curve's
     * average boost so the EQ shapes the tone without constantly exceeding
     * full scale; the limiter then only catches genuine peaks.
     */
    private double makeupGain = 1.0;

    private int sampleRate = 44100;
    private boolean bypass;
    /** Hard bypass for a path whose output nobody hears (e.g. the muted
     *  ExoPlayer while multi-room streaming owns the output). */
    private volatile boolean forcedBypass;
    private double[] gains;
    // Lightweight processing-cost instrumentation (for debugging stutter).
    private long lastTimingLogMs;
    private long procNanos;
    private int procCalls;

    public FirEqualizer() {
        rebuildKernel();
    }

    /**
     * Rebuilds the filter coefficients from ten band gains (dB, -12..+12).
     */
    public synchronized void setBandGains(double[] gainsDb) {
        this.gains = gainsDb == null ? null : gainsDb.clone();
        rebuildKernel();
    }

    private static boolean isFlat(double[] gainsDb) {
        if (gainsDb == null) return true;
        for (double g : gainsDb) {
            if (Math.abs(g) > 0.05) return false;
        }
        return true;
    }

    public synchronized void setSampleRate(int sampleRate) {
        if (sampleRate > 0 && sampleRate != this.sampleRate) {
            this.sampleRate = sampleRate;
            rebuildKernel();
        }
    }

    public synchronized void clearHistory() {
        for (int b = 0; b < BANDS; b++) {
            stateL[b][0] = stateL[b][1] = stateL[b][2] = stateL[b][3] = 0.0;
            stateR[b][0] = stateR[b][1] = stateR[b][2] = stateR[b][3] = 0.0;
        }
        delayPos = 0;
        dqHead = 0;
        dqTail = 0;
        for (int i = 0; i < LOOKAHEAD; i++) {
            dqLive[i] = false;
        }
        limitGain = 1.0;
        for (int i = 0; i < LOOKAHEAD; i++) {
            delayL[i] = 0.0;
            delayR[i] = 0.0;
        }
    }

    /** Bypasses all processing (filters + limiter) until cleared. */
    public synchronized void setForcedBypass(boolean bypass) {
        if (forcedBypass == bypass) return;
        forcedBypass = bypass;
        if (bypass) {
            clearHistory();
        }
    }

    /** (Re)computes the biquad coefficients for the current gains and rate. */
    private void rebuildKernel() {
        Log.i("FirEqualizer", "rebuild gains=" + java.util.Arrays.toString(gains)
                + " rate=" + sampleRate);
        updateLimiterTimeConstants();
        for (int b = 0; b < BANDS; b++) {
            double f0 = CENTER_FREQS[b];
            if (f0 >= sampleRate / 2.0 * 0.98) {
                // Band centre above Nyquist: pure passthrough.
                coeff[b][0] = 1.0;
                coeff[b][1] = 0.0;
                coeff[b][2] = 0.0;
                coeff[b][3] = 0.0;
                coeff[b][4] = 0.0;
                continue;
            }
            double gDb = gains != null ? Math.max(-12.0, Math.min(12.0, gains[b])) : 0.0;
            double a = Math.pow(10.0, gDb / 40.0);
            double w0 = 2.0 * PI * f0 / sampleRate;
            // Q matches the (approx.) one-octave spacing of the preset centre
            // frequencies so adjacent bands stay reasonably separated.
            double q = 1.41;
            double alpha = Math.sin(w0) / (2.0 * q);
            double cosW0 = Math.cos(w0);
            double a0 = 1.0 + alpha / a;
            coeff[b][0] = (1.0 + alpha * a) / a0;
            coeff[b][1] = (-2.0 * cosW0) / a0;
            coeff[b][2] = (1.0 - alpha * a) / a0;
            coeff[b][3] = (-2.0 * cosW0) / a0;
            coeff[b][4] = (1.0 - alpha / a) / a0;
        }
        bypass = isFlat(gains);
        makeupGain = computeMakeupGain();
        Log.i("FirEqualizer", "makeup=" + String.format(java.util.Locale.US, "%.3f",
                makeupGain));
        clearHistory();
    }

    /**
     * Estimates the average magnitude of the cascade over 20 Hz..20 kHz and
     * returns the attenuation that keeps the boosted output near unity.
     */
    private double computeMakeupGain() {
        if (bypass) return 1.0;
        double maxF = Math.min(20000.0, sampleRate / 2.0 * 0.95);
        int points = 200;
        double sumDb = 0.0;
        for (int i = 0; i < points; i++) {
            double f = 20.0 * Math.pow(maxF / 20.0, i / (double) (points - 1));
            double w = 2.0 * PI * f / sampleRate;
            double re = 1.0;
            double im = 0.0;
            for (int b = 0; b < BANDS; b++) {
                double[] c = coeff[b];
                double cw = Math.cos(w);
                double sw = Math.sin(w);
                double c2w = Math.cos(2.0 * w);
                double s2w = Math.sin(2.0 * w);
                double br = c[0] + c[1] * cw + c[2] * c2w;
                double bi = c[1] * sw + c[2] * s2w;
                double ar = 1.0 + c[3] * cw + c[4] * c2w;
                double ai = c[3] * sw + c[4] * s2w;
                double den = ar * ar + ai * ai;
                double num = br * br + bi * bi;
                double rr = (br * ar + bi * ai) / den;
                double ri = (bi * ar - br * ai) / den;
                double nre = re * rr - im * ri;
                double nim = re * ri + im * rr;
                re = nre;
                im = nim;
            }
            sumDb += 20.0 * Math.log10(Math.hypot(re, im) + 1e-12);
        }
        double avgDb = sumDb / points;
        double makeupDb = -Math.max(0.0, avgDb);
        makeupDb = Math.max(-12.0, Math.min(0.0, makeupDb));
        return Math.pow(10.0, makeupDb / 20.0);
    }

    private void updateLimiterTimeConstants() {
        attackCoef = 1.0 - Math.exp(-1.0 / (sampleRate * ATTACK_TAU_S));
        releaseCoef = 1.0 - Math.exp(-1.0 / (sampleRate * RELEASE_TAU_S));
    }

    /** Applies the equalizer in place to interleaved 16-bit PCM. */
    public synchronized void process(byte[] pcm, int channels) {
        process(pcm, pcm == null ? 0 : pcm.length, channels);
    }

    /** Applies the equalizer to the first {@code length} bytes of PCM. */
    public synchronized void process(byte[] pcm, int length, int channels) {
        if (bypass || forcedBypass || pcm == null || length <= 0) return;
        if (channels < 1 || channels > 2) return;
        int frames = length / 2 / channels;
        if (frames <= 0) return;
        long t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            int base = f * 2 * channels;
            double l = (short) ((pcm[base] & 0xff) | (pcm[base + 1] << 8));
            double r = 0.0;
            if (channels == 2) {
                r = (short) ((pcm[base + 2] & 0xff) | (pcm[base + 3] << 8));
            }
            l = runBiquads(l, stateL);
            if (channels == 2) {
                r = runBiquads(r, stateR);
            } else {
                r = l;
            }
            l *= makeupGain;
            r *= makeupGain;
            // Push into the lookahead delay line. The window peak is kept
            // with a monotonic deque (positions are ring slots; a stale
            // entry for the slot being overwritten can only sit at the
            // front, because any smaller successor would have popped it).
            delayL[delayPos] = l;
            delayR[delayPos] = r;
            double pk = Math.max(Math.abs(l), Math.abs(r));
            if (dqLive[delayPos]) {
                // The old entry for this ring slot is now stale.
                dqLive[dqPos[dqHead % (LOOKAHEAD + 1)]] = false;
                dqHead++;
            }
            while (dqTail > dqHead) {
                int bi = (dqTail - 1) % (LOOKAHEAD + 1);
                if (dqVal[bi] <= pk) {
                    dqLive[dqPos[bi]] = false;
                    dqTail--;
                } else {
                    break;
                }
            }
            int ti = dqTail % (LOOKAHEAD + 1);
            dqPos[ti] = delayPos;
            dqVal[ti] = pk;
            dqLive[delayPos] = true;
            dqTail++;
            delayPos = (delayPos + 1) % LOOKAHEAD;
            double peak = dqVal[dqHead % (LOOKAHEAD + 1)];
            double target = peak > LIMIT_THRESHOLD ? LIMIT_THRESHOLD / peak : 1.0;
            windowSamples++;
            if (target < 1.0) windowLimitHits++;
            if (target < limitGain) {
                limitGain += (target - limitGain) * attackCoef;
            } else {
                limitGain += (target - limitGain) * releaseCoef;
            }
            if (limitGain < windowMinLimitGain) windowMinLimitGain = limitGain;
            double outL = delayL[delayPos] * limitGain;
            double outR = delayR[delayPos] * limitGain;
            short sl = (short) Math.max(-32768, Math.min(32767, Math.round(outL)));
            pcm[base] = (byte) (sl & 0xff);
            pcm[base + 1] = (byte) ((sl >> 8) & 0xff);
            if (channels == 2) {
                short sr = (short) Math.max(-32768, Math.min(32767, Math.round(outR)));
                pcm[base + 2] = (byte) (sr & 0xff);
                pcm[base + 3] = (byte) ((sr >> 8) & 0xff);
            }
        }
        logCost(frames, System.nanoTime() - t0);
    }

    /** Runs one sample through the ten cascaded biquads. */
    private double runBiquads(double x, double[][] state) {
        for (int b = 0; b < BANDS; b++) {
            double[] c = coeff[b];
            double[] s = state[b];
            double y = c[0] * x + c[1] * s[0] + c[2] * s[1]
                    - c[3] * s[2] - c[4] * s[3];
            s[1] = s[0];
            s[0] = x;
            s[3] = s[2];
            s[2] = y;
            x = y;
        }
        return x;
    }

    private void logCost(int frames, long nano) {
        procNanos += nano;
        procCalls++;
        long now = System.currentTimeMillis();
        if (now - lastTimingLogMs >= 3000) {
            Log.i("FirEqualizer", "avg_ms=" + String.format(java.util.Locale.US, "%.3f",
                    procNanos / 1e6 / Math.max(1, procCalls))
                    + " calls=" + procCalls + " frames=" + frames
                    + " rate=" + sampleRate + " bypass=" + bypass
                    + " min_gain=" + String.format(java.util.Locale.US, "%.3f",
                    windowMinLimitGain)
                    + " limit_hits=" + (windowSamples > 0
                    ? String.format(java.util.Locale.US, "%.1f",
                    100.0 * windowLimitHits / windowSamples) : "0")
                    + "%");
            procNanos = 0;
            procCalls = 0;
            windowMinLimitGain = 1.0;
            windowLimitHits = 0;
            windowSamples = 0;
            lastTimingLogMs = now;
        }
    }
}
