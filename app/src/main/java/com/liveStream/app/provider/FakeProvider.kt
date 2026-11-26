package com.liveStream.app.provider

import com.liveStream.contract.DramaProvider
import com.liveStream.contract.EpisodeItem
import com.liveStream.contract.EpisodePage
import com.liveStream.contract.FeedPage
import com.liveStream.contract.FilterLadder
import com.liveStream.contract.FilterOption
import com.liveStream.contract.FilterSelection
import com.liveStream.contract.MediaItem
import com.liveStream.contract.PlaybackSource
import com.liveStream.contract.TabSpec

/**
 * Provider kerangka untuk menguji Wadah UI + MVI + Media3 sebelum API asli dicolok.
 */
class FakeProvider : DramaProvider {
    override val id: String = "demo_shell"
    override val displayName: String = "Demo Shell"

    private val demoItems = listOf(
        MediaItem(id, "demo_1", "Contoh Drama 1 (Kerangka UI)", "", listOf("Romansa", "CEO"), 60, "HD"),
        MediaItem(id, "demo_2", "Contoh Drama 2 (Multi-Provider)", "", listOf("Fantasi", "Aksi"), 48, "BARU"),
        MediaItem(id, "demo_3", "Contoh Drama 3 (Siap Colok API)", "", listOf("Misteri"), 32, null),
        MediaItem(id, "demo_4", "Contoh Drama 4 (MVI + Media3)", "", listOf("Keluarga"), 80, "TREN"),
        MediaItem(id, "demo_5", "Contoh Drama 5 (Rust JNI Core)", "", listOf("Komedi"), 24, null),
        MediaItem(id, "demo_6", "Contoh Drama 6 (Filter 4 Tangga)", "", listOf("Aksi"), 50, "VIP")
    )

    override suspend fun getTabs(): List<TabSpec> = listOf(
        TabSpec("home", "Semua", isDefault = true),
        TabSpec("trending", "Sedang Tren"),
        TabSpec("latest", "Baru")
    )

    override suspend fun getFeed(tabId: String, cursor: String): FeedPage {
        val items = if (tabId == "trending") demoItems.reversed() else demoItems
        return FeedPage(items = items, nextCursor = "", hasMore = false)
    }

    override suspend fun getFilterLadders(): List<FilterLadder> = listOf(
        FilterLadder(
            id = "setting",
            title = "Pengaturan",
            options = listOf(
                FilterOption("0", "Semua", 1),
                FilterOption("fantasi", "Fantasi", 2),
                FilterOption("kota", "Perkotaan", 2)
            )
        ),
        FilterLadder(
            id = "theme",
            title = "Tema",
            options = listOf(
                FilterOption("0", "Semua", 1),
                FilterOption("ceo", "CEO", 2),
                FilterOption("aksi", "Aksi", 2)
            )
        )
    )

    override suspend fun getFilteredFeed(filters: List<FilterSelection>, cursor: String): FeedPage =
        FeedPage(items = demoItems.take(3), nextCursor = "", hasMore = false)

    override suspend fun search(query: String, cursor: String): FeedPage {
        val filtered = demoItems.filter { it.title.contains(query, ignoreCase = true) }
        return FeedPage(items = filtered, nextCursor = "", hasMore = false)
    }

    override suspend fun getEpisodes(mediaId: String, cursor: String): EpisodePage {
        val eps = (1..12).map { ep ->
            EpisodeItem(
                episodeId = "${mediaId}_ep_$ep",
                number = ep,
                title = "Episode $ep",
                isLocked = ep > 10
            )
        }
        return EpisodePage(episodes = eps, nextCursor = "", hasMore = false)
    }

    override suspend fun resolvePlayback(mediaId: String, episodeId: String): PlaybackSource =
        PlaybackSource(streamUrl = "")
}
