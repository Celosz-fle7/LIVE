package com.liveStream.app.provider

import com.liveStream.app.provider.freereels.FreeReelsProvider
import com.liveStream.app.provider.melolo.MeloloProvider
import com.liveStream.app.provider.pinedrama.PineDramaProvider
import com.liveStream.contract.DramaProvider

/**
 * Satu-satunya titik registrasi provider.
 * Tambah provider baru cukup 1 baris di daftar `providers` tanpa menyentuh UI/Player.
 */
object ProviderRegistry {
    private val providers: List<DramaProvider> = listOf(
        PineDramaProvider(),
        FreeReelsProvider(),
        MeloloProvider(),
        FakeProvider()
    )

    fun all(): List<DramaProvider> = providers

    fun get(providerId: String): DramaProvider =
        providers.firstOrNull { it.id == providerId } ?: providers.first()
}
