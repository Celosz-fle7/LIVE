package com.liveStream.app.provider

import com.liveStream.contract.DramaProvider

/**
 * Satu-satunya titik registrasi provider.
 * Tambah provider baru cukup 1 baris di daftar `providers` tanpa menyentuh UI/Player.
 */
object ProviderRegistry {
    private val providers: List<DramaProvider> = listOf(
        FakeProvider()
        // Colok provider baru di sini, misal: PineDramaProvider()
    )

    fun all(): List<DramaProvider> = providers

    fun get(providerId: String): DramaProvider =
        providers.firstOrNull { it.id == providerId } ?: providers.first()
}
