package com.airmusic.player.playback;

import android.content.Context;

import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/**
 * Injects audio processors (equalizer, balance) into ExoPlayer's pipeline.
 */
public final class BalanceRenderersFactory extends DefaultRenderersFactory {

    private final AudioProcessor[] processors;

    public BalanceRenderersFactory(Context context, AudioProcessor[] processors) {
        super(context);
        this.processors = processors;
    }

    @Override
    protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        return new DefaultAudioSink.Builder(context)
                .setAudioProcessors(processors)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // A larger AudioTrack buffer absorbs the extra latency of the
                // equalizer chain. Without it, low-bitrate streams on some
                // tablets under-run constantly (audible stutter).
                .setAudioTrackBufferSizeProvider(
                        (minBufferSizeInBytes, encoding, outputMode, pcmFrameSize,
                         sampleRate, bitrate, maxAudioTrackPlaybackSpeed) -> {
                            // media3 1.4.1 calls this as
                            // getBufferSizeInBytes(min, encoding, outputMode,
                            // pcmFrameSize, sampleRate, bitrate, maxPlaybackSpeed).
                            // For PCM, pcmFrameSize already includes both channels,
                            // so bytesPerSecond is sampleRate * pcmFrameSize.
                            long bytesPerSecond = (long) sampleRate * Math.max(1, pcmFrameSize);
                            double speed = Math.max(0.25, maxAudioTrackPlaybackSpeed);
                            long wanted = (long) (bytesPerSecond * 1.0 / speed);
                            long size = Math.max(minBufferSizeInBytes * 2L,
                                    Math.min(wanted, 8L * 1024 * 1024));
                            // The size must hold a whole number of PCM frames.
                            if (pcmFrameSize > 1) {
                                size = (size / pcmFrameSize) * pcmFrameSize;
                            }
                            return (int) size;
                        })
                .build();
    }
}
