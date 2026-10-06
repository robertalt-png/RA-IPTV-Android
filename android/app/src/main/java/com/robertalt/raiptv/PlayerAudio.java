package com.nenotv.player;

import android.content.Context;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.RenderersFactory;

/**
 * One audio setup for the Light and Pro players (VLC is no longer used).
 * Order per audio track: 1. pass the original Dolby/DTS/TrueHD stream to a TV or receiver that supports it,
 * 2. decode with the device's own (hardware) decoder, 3. decode with the bundled LGPL FFmpeg audio decoder
 * (AC3, E-AC3/Atmos core, DTS/DTS-HD, TrueHD, MLP, MP2/MP3, FLAC, ALAC, Opus, Vorbis, AAC).
 * Video keeps using the device's hardware decoders, as before.
 */
public final class PlayerAudio {
    private PlayerAudio() {}

    public static RenderersFactory renderers(Context context) {
        return new DefaultRenderersFactory(context.getApplicationContext())
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true);
    }

    /** True when the FFmpeg audio decoder is packaged and its native library loads on this device. */
    public static boolean ffmpegAvailable() {
        try { return androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable(); }
        catch (Throwable t) { return false; }
    }
}
