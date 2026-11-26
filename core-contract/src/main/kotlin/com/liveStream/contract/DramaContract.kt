package com.liveStream.contract

data class TabSpec(
    val id: String,
    val title: String,
    val isDefault: Boolean = false
)

data class MediaItem(
    val providerId: String,
    val mediaId: String,
    val title: String,
    val coverUrl: String,
    val tags: List<String> = emptyList(),
    val totalEpisodes: Int = 0,
    val badge: String? = null
)

data class FeedPage(
    val items: List<MediaItem>,
    val nextCursor: String = "",
    val hasMore: Boolean = false
)

data class FilterOption(
    val id: String,
    val name: String,
    val filterType: Int = 2
)

data class FilterLadder(
    val id: String,
    val title: String,
    val options: List<FilterOption>
)

data class FilterSelection(
    val ladderId: String,
    val optionId: String,
    val filterType: Int = 2
)

data class EpisodeItem(
    val episodeId: String,
    val number: Int,
    val title: String,
    val isLocked: Boolean = false,
    val durationSec: Int = 0
)

data class EpisodePage(
    val episodes: List<EpisodeItem>,
    val nextCursor: String = "",
    val hasMore: Boolean = false
)

data class SubtitleTrack(
    val url: String,
    val langCode: String,
    val label: String,
    val mimeType: String = "text/vtt"
)

data class AudioTrack(
    val id: String,
    val langCode: String,
    val label: String,
    val isDefault: Boolean = false
)

data class PlaybackSource(
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val isLocalProxy: Boolean = false
)

/**
 * Kontrak tunggal antara Wadah (UI + MVI + Media3) dan modul Provider.
 * Provider baru cukup mengimplementasikan interface ini dan mendaftar di ProviderRegistry.
 */
interface DramaProvider {
    val id: String
    val displayName: String
    val iconUrl: String get() = ""

    suspend fun getTabs(): List<TabSpec>
    suspend fun getFeed(tabId: String, cursor: String = ""): FeedPage
    suspend fun getFilterLadders(): List<FilterLadder> = emptyList()
    suspend fun getFilteredFeed(
        filters: List<FilterSelection>,
        cursor: String = ""
    ): FeedPage = getFeed("default", cursor)

    suspend fun search(query: String, cursor: String = ""): FeedPage
    suspend fun getEpisodes(mediaId: String, cursor: String = ""): EpisodePage
    suspend fun resolvePlayback(mediaId: String, episodeId: String): PlaybackSource
}
