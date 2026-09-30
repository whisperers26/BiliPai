package com.android.purebilibili.core.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

internal const val HI_RES_FLAC_MIN_CODEC_INPUT_SIZE_BYTES = 1024 * 1024

internal fun resolveHiResCodecMaxInputSize(
    defaultMaxInputSize: Int,
    sampleMimeTypes: List<String?>
): Int {
    val containsFlac = sampleMimeTypes.any { mimeType ->
        mimeType.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true)
    }
    return if (containsFlac) {
        maxOf(defaultMaxInputSize, HI_RES_FLAC_MIN_CODEC_INPUT_SIZE_BYTES)
    } else {
        defaultMaxInputSize
    }
}

/**
 * 为部分系统 FLAC 解码器预留更大的输入缓冲区。
 *
 * 某些设备会给 `c2.android.flac.decoder` 分配 32 KiB 输入缓冲，但 B 站 Hi-Res
 * 音轨的单帧可能超过该大小，最终触发 Media3 `InsufficientCapacityException`。
 */
@OptIn(UnstableApi::class)
internal open class HiResCompatibleRenderersFactory(
    context: Context
) : DefaultRenderersFactory(context) {

    init {
        // 默认 Sonic 变速只作用于之后写入的音频，AudioTrack 里已缓冲的几百毫秒仍按旧速度播放；
        // 视频渲染器却立即按新速度排帧，导致长按倍速开始/结束时画面卡顿、丢帧。
        // 交给 AudioTrack 的 PlaybackParams 变速，已缓冲音频也立即变速，音频时钟与视频同步切换。
        setEnableAudioOutputPlaybackParameters(true)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val firstAudioRendererIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        val mediaCodecRendererIndex = (firstAudioRendererIndex until out.size)
            .firstOrNull { index -> out[index].javaClass == MediaCodecAudioRenderer::class.java }
            ?: return
        out[mediaCodecRendererIndex] = HiResCompatibleMediaCodecAudioRenderer(
            context = context,
            codecAdapterFactory = codecAdapterFactory,
            mediaCodecSelector = mediaCodecSelector,
            enableDecoderFallback = enableDecoderFallback,
            eventHandler = eventHandler,
            eventListener = eventListener,
            audioSink = audioSink
        )
    }
}

@OptIn(UnstableApi::class)
private class HiResCompatibleMediaCodecAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink
) {

    override fun getCodecMaxInputSize(
        codecInfo: MediaCodecInfo,
        format: Format,
        streamFormats: Array<Format>
    ): Int {
        return resolveHiResCodecMaxInputSize(
            defaultMaxInputSize = super.getCodecMaxInputSize(
                codecInfo,
                format,
                streamFormats
            ),
            sampleMimeTypes = listOf(format.sampleMimeType) +
                streamFormats.map { streamFormat -> streamFormat.sampleMimeType }
        )
    }
}
