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
    private static final double LIMIT_THRESHOLD = 30000.0;
    /** ~1.45 ms of lookahead at 44.1 kHz. */
    private static final int LOOKAHEAD = 64;
    /** Gain-reduction time constants (seconds). */
    private static final double ATTACK_TAU_S = 0.0006;
    private static final double RELEASE_TAU_S = 0.18;
    private static final double PI = Math.PI;

    /** Per-band biquad coefficients: b0, b1, b2, a1, a2 (already divided by a0). */
    private final double[][] coeff = new double[BANDS][5];
    /** Per-band filter state per channel: x1, x2, y1, y2. */
    private final double[][] stateL = new double[BANDS][4];
    private final double[][] stateR = new double[BANDS][4];
    /** Lookahead delay lines (post-EQ samples before the limiter). */
    private final double[] delayL = new double[LOOKAHEAD];
    private final double[] delayR = new double[LOOKAHEAD];
    private int delayPos;
    private double limitGain = 1.0;
    private double attackCoef = 1.0;
    private double releaseCoef = 1.0;

    private int sampleRate = 44100;
    private boolean bypass;
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
        limitGain = 1.0;
        for (int i = 0; i < LOOKAHEAD; i++) {
            delayL[i] = 0.0;
            delayR[i] = 0.0;
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
        clearHistory();
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
        if (bypass || pcm == null || length <= 0) return;
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
            // Push into the lookahead delay line, then read the oldest
            // sample and apply the gain computed from the incoming peak.
            delayL[delayPos] = l;
            delayR[delayPos] = r;
            delayPos = (delayPos + 1) % LOOKAHEAD;
            double peak = 0.0;
            for (int i = 0; i < LOOKAHEAD; i++) {
                double p = Math.abs(delayL[i]);
                if (p > peak) peak = p;
                p = Math.abs(delayR[i]);
                if (p > peak) peak = p;
            }
            double target = peak > LIMIT_THRESHOLD ? LIMIT_THRESHOLD / peak : 1.0;
            if (target < limitGain) {
                limitGain += (target - limitGain) * attackCoef;
            } else {
                limitGain += (target - limitGain) * releaseCoef;
            }
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
                    + " rate=" + sampleRate + " bypass=" + bypass);
            procNanos = 0;
            procCalls = 0;
            lastTimingLogMs = now;
        }
    }
}
