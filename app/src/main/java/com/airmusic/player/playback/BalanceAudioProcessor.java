package com.airmusic.player.playback;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Applies the left/right balance to 16-bit PCM audio inside ExoPlayer.
 * Always reports active so balance changes apply immediately without
 * reconfiguring the playback pipeline.
 */
public final class BalanceAudioProcessor implements AudioProcessor {

    private volatile float balance;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded;
    private AudioFormat inputFormat;

    /** @param balance -1 = full left, 0 = center, +1 = full right */
    public void setBalance(float balance) {
        this.balance = Math.max(-1f, Math.min(1f, balance));
    }

    private float panLeft() {
        return balance <= 0f ? 1f : 1f - balance;
    }

    private float panRight() {
        return balance >= 0f ? 1f : 1f + balance;
    }

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        inputFormat = inputAudioFormat;
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;

        float left = panLeft();
        float right = panRight();

        // Only stereo needs per-channel processing; mono passes through.
        if (inputFormat == null || inputFormat.channelCount != 2) {
            if (outputBuffer.capacity() < remaining) {
                outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
            }
            outputBuffer.clear();
            outputBuffer.put(inputBuffer);
            outputBuffer.flip();
            return;
        }

        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
        }
        outputBuffer.clear();
        while (inputBuffer.remaining() >= 4) {
            short l = inputBuffer.getShort();
            short r = inputBuffer.getShort();
            outputBuffer.putShort((short) (l * left));
            outputBuffer.putShort((short) (r * right));
        }
        outputBuffer.flip();
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
    }

    @Override
    public void reset() {
        flush();
        inputFormat = null;
    }

}
