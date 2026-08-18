package com.airmusic.player.playback;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** AudioProcessor wrapper that runs the shared {@link FirEqualizer}. */
public final class EqAudioProcessor implements AudioProcessor {

    /** Debug hook: raw (post-EQ) 16-bit PCM capture, toggled via intents. */
    private static FileOutputStream captureStream;
    private static long captureWritten;
    private static final long CAPTURE_LIMIT = 200L * 1024 * 1024;

    public static synchronized void startCapture(File file) {
        stopCapture();
        try {
            captureStream = new FileOutputStream(file);
            captureWritten = 0;
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
        }
    }

    private final FirEqualizer equalizer;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private byte[] pcmBuffer = new byte[0];
    private boolean inputEnded;
    private AudioFormat inputFormat;

    public EqAudioProcessor(FirEqualizer equalizer) {
        this.equalizer = equalizer;
    }

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        inputFormat = inputAudioFormat;
        equalizer.setSampleRate(inputAudioFormat.sampleRate);
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;
        // Reuse the scratch buffer so the audio thread does not allocate a
        // new array on every block (GC pauses cause audible stutter).
        if (pcmBuffer.length < remaining) {
            pcmBuffer = new byte[remaining];
        }
        inputBuffer.get(pcmBuffer, 0, remaining);
        equalizer.process(pcmBuffer, remaining,
                inputFormat != null ? inputFormat.channelCount : 2);
        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
        }
        outputBuffer.clear();
        outputBuffer.put(pcmBuffer, 0, remaining);
        outputBuffer.flip();
        writeCapture(pcmBuffer, remaining);
    }

    private void writeCapture(byte[] data, int len) {
        FileOutputStream out;
        synchronized (EqAudioProcessor.class) {
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

    @Override
    public void queueEndOfStream() {
        inputEnded = true;
    }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return out;
    }

    @Override
    public boolean isEnded() {
        return inputEnded && outputBuffer == EMPTY_BUFFER;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
        inputEnded = false;
        equalizer.clearHistory();
    }

    @Override
    public void reset() {
        flush();
        inputFormat = null;
    }
}
