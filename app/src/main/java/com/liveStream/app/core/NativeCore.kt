package com.liveStream.app.core

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

    private external fun nativePing(): String
    private external fun nativeExtractCdnUrls(payload: ByteArray): Array<String>
}
