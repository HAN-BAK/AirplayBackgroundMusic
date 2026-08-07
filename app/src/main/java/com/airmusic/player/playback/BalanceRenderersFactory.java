package com.airmusic.player.playback;

import android.content.Context;

import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/**
 * Injects the {@link BalanceAudioProcessor} into ExoPlayer's audio pipeline.
 */
public final class BalanceRenderersFactory extends DefaultRenderersFactory {

    private final AudioProcessor processor;

    public BalanceRenderersFactory(Context context, AudioProcessor processor) {
        super(context);
        this.processor = processor;
    }

    @Override
    protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        return new DefaultAudioSink.Builder(context)
                .setAudioProcessors(new AudioProcessor[]{processor})
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build();
    }
}
