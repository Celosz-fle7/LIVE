package com.liveStream.app.core

data class CodecProbeResult(
    val videoCodec: String = "unknown",
    val audioCodec: String = "unknown",
    val container: String = "unknown",
    val probedByRust: Boolean = false
) {
    val displayVideoLabel: String
        get() = when (videoCodec.lowercase()) {
            "av01" -> "AV1"
            "vp9" -> "VP9"
            "hevc" -> "HEVC"
            "avc1" -> "AVC1"
            "vp8" -> "VP8"
            else -> "AUTO"
        }
}

object NativeCore {
    private var loaded = false

    init {
        loaded = try {
            System.loadLibrary("live_core")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun isAvailable(): Boolean = loaded

    fun ping(): String = if (loaded) nativePing() else "rust_core_not_loaded"

    fun extractCdnUrls(payload: ByteArray): List<String> =
        if (loaded) nativeExtractCdnUrls(payload).toList() else emptyList()

    fun probeCodec(payload: ByteArray): CodecProbeResult {
        if (payload.isEmpty()) return CodecProbeResult(probedByRust = loaded)
        val raw = if (loaded) {
            runCatching { nativeProbeCodec(payload) }.getOrNull()
        } else {
            null
        }
        return if (!raw.isNullOrBlank()) {
            parseSummary(raw, probedByRust = true)
        } else {
            fallbackProbe(payload)
        }
    }

    fun detectCodecFromText(text: String): CodecProbeResult {
        if (text.isBlank()) return CodecProbeResult(probedByRust = loaded)
        val raw = if (loaded) {
            runCatching { nativeDetectCodecFromText(text) }.getOrNull()
        } else {
            null
        }
        return if (!raw.isNullOrBlank()) {
            parseSummary(raw, probedByRust = true)
        } else {
            fallbackProbe(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun parseSummary(summary: String, probedByRust: Boolean): CodecProbeResult {
        val parts = summary.split("|")
        return CodecProbeResult(
            videoCodec = parts.getOrNull(0).orEmpty().ifBlank { "unknown" },
            audioCodec = parts.getOrNull(1).orEmpty().ifBlank { "unknown" },
            container = parts.getOrNull(2).orEmpty().ifBlank { "unknown" },
            probedByRust = probedByRust
        )
    }

    private fun fallbackProbe(bytes: ByteArray): CodecProbeResult {
        val text = bytes.decodeToString()
        val lower = text.lowercase()
        val video = when {
            lower.contains("av01") || text.contains("av1C") -> "av01"
            lower.contains("vp09") || lower.contains("vp9") -> "vp9"
            lower.contains("hvc1") || lower.contains("hev1") || lower.contains("hevc") -> "hevc"
            lower.contains("avc1") || lower.contains("avc3") || lower.contains("h264") -> "avc1"
            lower.contains("vp08") || lower.contains("vp8") -> "vp8"
            else -> "unknown"
        }
        val audio = when {
            lower.contains("opus") -> "opus"
            lower.contains("mp4a") || lower.contains("aac") -> "aac"
            lower.contains("ec-3") || lower.contains("ac-3") -> "ac3"
            else -> "unknown"
        }
        val container = when {
            lower.contains("#extm3u") -> "hls"
            lower.contains("<mpd") -> "dash"
            text.contains("ftyp") || text.contains("moov") -> "mp4"
            else -> "unknown"
        }
        return CodecProbeResult(video, audio, container, probedByRust = false)
    }

    private external fun nativePing(): String
    private external fun nativeExtractCdnUrls(payload: ByteArray): Array<String>
    private external fun nativeProbeCodec(payload: ByteArray): String
    private external fun nativeDetectCodecFromText(text: String): String
}
