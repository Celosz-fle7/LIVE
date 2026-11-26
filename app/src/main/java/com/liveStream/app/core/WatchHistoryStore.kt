package com.liveStream.app.core

import android.content.Context
import com.liveStream.contract.MediaItem
import org.json.JSONArray
import org.json.JSONObject

data class WatchHistoryEntry(
    val providerId: String,
    val mediaId: String,
    val title: String,
    val coverUrl: String,
    val totalEpisodes: Int,
    val episodeIndex: Int,
    val episodeNumber: Int,
    val positionMs: Long,
    val updatedAt: Long
) {
    fun toMediaItem(): MediaItem = MediaItem(
        providerId = providerId,
        mediaId = mediaId,
        title = title,
        coverUrl = coverUrl,
        totalEpisodes = totalEpisodes
    )
}

object WatchHistoryStore {
    private const val PREFS_NAME = "live_prefs"
    private const val KEY_HISTORY = "watch_history_json"
    private const val MAX_ITEMS = 50

    @Synchronized
    fun save(context: Context, entry: WatchHistoryEntry) {
        if (entry.providerId.isBlank() || entry.mediaId.isBlank()) return
        val list = loadAll(context).toMutableList()
        list.removeAll { it.providerId == entry.providerId && it.mediaId == entry.mediaId }
        list.add(0, entry)
        if (list.size > MAX_ITEMS) {
            list.subList(MAX_ITEMS, list.size).clear()
        }
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("providerId", e.providerId)
                    put("mediaId", e.mediaId)
                    put("title", e.title)
                    put("coverUrl", e.coverUrl)
                    put("totalEpisodes", e.totalEpisodes)
                    put("episodeIndex", e.episodeIndex)
                    put("episodeNumber", e.episodeNumber)
                    put("positionMs", e.positionMs)
                    put("updatedAt", e.updatedAt)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply()
    }

    @Synchronized
    fun loadAll(context: Context): List<WatchHistoryEntry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null)
            ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pid = o.optString("providerId")
                val mid = o.optString("mediaId")
                if (pid.isBlank() || mid.isBlank()) continue
                add(
                    WatchHistoryEntry(
                        providerId = pid,
                        mediaId = mid,
                        title = o.optString("title"),
                        coverUrl = o.optString("coverUrl"),
                        totalEpisodes = o.optInt("totalEpisodes", 0),
                        episodeIndex = o.optInt("episodeIndex", 0),
                        episodeNumber = o.optInt("episodeNumber", 1),
                        positionMs = o.optLong("positionMs", 0L),
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                )
            }
        }
    }

    fun find(context: Context, providerId: String, mediaId: String): WatchHistoryEntry? =
        loadAll(context).firstOrNull { it.providerId == providerId && it.mediaId == mediaId }
}
