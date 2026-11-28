package com.liveStream.app.core

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DecoderCapability(
    val mimeType: String,
    val hasHardwareDecoder: Boolean,
    val hasSoftwareDecoder: Boolean,
    val preferredDecoderName: String = ""
) {
    val isSupported: Boolean get() = hasHardwareDecoder || hasSoftwareDecoder
    val modeBadge: String
        get() = when {
            hasHardwareDecoder -> "HW"
            hasSoftwareDecoder -> "SW"
            else -> "NONE"
        }
}

data class StreamCodecReport(
    val probe: CodecProbeResult,
    val capability: DecoderCapability?,
    val activeTrackCodec: String = ""
) {
    val badgeText: String
        get() {
            val codecLabel = when {
                activeTrackCodec.isNotBlank() -> activeTrackCodec
                probe.videoCodec != "unknown" -> probe.displayVideoLabel
                else -> "AUTO"
            }
            val mode = capability?.modeBadge?.takeIf { it != "NONE" }
            val rustTag = if (probe.probedByRust) "RS" else ""
            return listOfNotNull(codecLabel, mode, rustTag.takeIf { it.isNotBlank() }).joinToString("·")
        }
}

object VideoCodecSupport {
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @OptIn(UnstableApi::class)
    fun createTvRenderersFactory(context: Context): DefaultRenderersFactory {
        return DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    }

    fun mimeForCodecKey(codecKey: String): String? = when (codecKey.lowercase()) {
        "av01", "av1" -> MimeTypes.VIDEO_AV1
        "vp9", "vp09" -> MimeTypes.VIDEO_VP9
        "hevc", "hvc1", "hev1", "h265" -> MimeTypes.VIDEO_H265
        "avc1", "avc3", "h264" -> MimeTypes.VIDEO_H264
        "vp8", "vp08" -> MimeTypes.VIDEO_VP8
        else -> null
    }

    fun labelFromExoFormat(format: Format?): String {
        if (format == null) return ""
        val codecs = format.codecs.orEmpty().lowercase()
        val mime = format.sampleMimeType.orEmpty().lowercase()
        return when {
            codecs.contains("av01") || mime == MimeTypes.VIDEO_AV1 -> "AV1"
            codecs.contains("vp09") || codecs.contains("vp9") || mime == MimeTypes.VIDEO_VP9 -> "VP9"
            codecs.contains("hvc1") || codecs.contains("hev1") || mime == MimeTypes.VIDEO_H265 -> "HEVC"
            codecs.contains("avc1") || codecs.contains("avc3") || mime == MimeTypes.VIDEO_H264 -> "AVC1"
            codecs.contains("vp08") || mime == MimeTypes.VIDEO_VP8 -> "VP8"
            else -> ""
        }
    }

    fun queryDecoderCapability(mimeType: String): DecoderCapability {
        var hasHw = false
        var hasSw = false
        var preferredName = ""
        runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) continue
                val isSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isSoftwareOnly
                } else {
                    val name = info.name.lowercase()
                    name.startsWith("omx.google.") ||
                        name.startsWith("c2.android.") ||
                        name.contains("sw")
                }
                if (isSoftware) {
                    hasSw = true
                    if (preferredName.isEmpty()) preferredName = info.name
                } else {
                    hasHw = true
                    preferredName = info.name
                }
            }
        }
        return DecoderCapability(
            mimeType = mimeType,
            hasHardwareDecoder = hasHw,
            hasSoftwareDecoder = hasSw,
            preferredDecoderName = preferredName
        )
    }

    fun supportedCodecSummary(): String {
        val keys = listOf("avc1" to "AVC1", "hevc" to "HEVC", "vp9" to "VP9", "av01" to "AV1")
        return keys.mapNotNull { (k, label) ->
            val mime = mimeForCodecKey(k) ?: return@mapNotNull null
            val cap = queryDecoderCapability(mime)
            if (cap.isSupported) "$label(${cap.modeBadge})" else null
        }.joinToString(" · ").ifBlank { "AVC1" }
    }

    suspend fun probeRemoteStream(
        streamUrl: String,
        headers: Map<String, String> = emptyMap()
    ): StreamCodecReport = withContext(Dispatchers.IO) {
        if (streamUrl.isBlank()) {
            return@withContext StreamCodecReport(CodecProbeResult(), null)
        }
        val urlHint = NativeCore.detectCodecFromText(streamUrl)
        if (urlHint.videoCodec != "unknown") {
            val cap = mimeForCodecKey(urlHint.videoCodec)?.let { queryDecoderCapability(it) }
            return@withContext StreamCodecReport(urlHint, cap)
        }
        val probedBytes = runCatching {
            val reqBuilder = Request.Builder()
                .url(streamUrl)
                .header("Range", "bytes=0-32767")
            headers.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return@use ByteArray(0)
                val body = resp.body ?: return@use ByteArray(0)
                val stream = body.byteStream()
                val buf = ByteArray(32768)
                var total = 0
                while (total < buf.size) {
                    val read = stream.read(buf, total, buf.size - total)
                    if (read <= 0) break
                    total += read
                }
                buf.copyOf(total)
            }
        }.getOrDefault(ByteArray(0))

        val probe = NativeCore.probeCodec(probedBytes)
        val cap = mimeForCodecKey(probe.videoCodec)?.let { queryDecoderCapability(it) }
        StreamCodecReport(probe, cap)
    }
}
