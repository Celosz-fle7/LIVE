package com.liveStream.app.provider.pinedrama

import com.liveStream.app.core.NativeCore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PineDramaProvider : DramaProvider {
    override val id: String = "pinedrama"
    override val displayName: String = "PineDrama"
    override val iconUrl: String =
        "https://play-lh.googleusercontent.com/pfc2SBr-JiF9y5d47tVq2hreXASR0MOZV2SA0CHk0GGyPpmf2e47GtkOJi3_pL9PDYVh1dG6vxqRFzpBOHzN=w240-h480"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val totalEpisodeCache = ConcurrentHashMap<String, Int>()
    private var cachedLadders: List<FilterLadder> = emptyList()

    private val userAgent =
        "com.ss.android.ttmd.video/2024708040 (Linux; U; Android 16; id_ID; Infinix X6870; Build/UP1A.231005.007; Cronet/TTNetVersion:5303bf90)"

    private val videoUrlRegex = Regex(
        """https://v[0-9a-zA-Z\-\.]+\.(?:tiktokcdn|byteicdn)\.com/[a-zA-Z0-9_\-\./%?=&~+:;,@#]+"""
    )

    override suspend fun getTabs(): List<TabSpec> = listOf(
        TabSpec("1", "Semua", isDefault = true),
        TabSpec("2", "Sedang Tren"),
        TabSpec("5", "Picks"),
        TabSpec("6", "Rank Tren"),
        TabSpec("7", "Rank Baru"),
        TabSpec("8", "Terbaru")
    )

    override suspend fun getFeed(tabId: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        val scene = tabId.ifBlank { "1" }
        val url = buildUrl(
            "/tiktok/md/mini_drama_center/v1/",
            mapOf(
                "scene" to scene,
                "category_id" to if (scene == "2") "101" else "0",
                "cursor" to cursor,
                "count" to "15"
            )
        )
        val json = JSONObject(execGetText(url))
        parseCollectionPage(json)
    }

    override suspend fun getFilterLadders(): List<FilterLadder> = withContext(Dispatchers.IO) {
        if (cachedLadders.isNotEmpty()) return@withContext cachedLadders
        val url = buildUrl("/tiktok/md/mini_drama/category_filter/v1/")
        val body = JSONObject().apply {
            put("category_filters", JSONArray())
            put("cursor", "")
            put("count", 1)
        }
        val json = JSONObject(execPostJson(url, body))
        val ladders = parseFilterLadders(json.optJSONArray("category_filter_lists"))
        if (ladders.isNotEmpty()) cachedLadders = ladders
        ladders
    }

    override suspend fun getFilteredFeed(filters: List<FilterSelection>, cursor: String): FeedPage =
        withContext(Dispatchers.IO) {
            val url = buildUrl("/tiktok/md/mini_drama/category_filter/v1/")
            val arr = JSONArray()
            filters.forEach { f ->
                val obj = JSONObject().apply {
                    put("filter_type", f.filterType)
                    if (f.filterType == 3) {
                        put("recent_days", f.optionId.toIntOrNull() ?: 7)
                    } else {
                        put("category_id", f.optionId)
                    }
                }
                arr.put(obj)
            }
            val body = JSONObject().apply {
                put("category_filters", arr)
                put("cursor", cursor)
                put("count", 15)
            }
            val json = JSONObject(execPostJson(url, body))
            parseCollectionPage(json)
        }

    override suspend fun search(query: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        val url = buildUrl(
            "/tiktok/md/collection/search/v1/",
            mapOf("keyword" to query, "cursor" to cursor, "count" to "15")
        )
        val json = JSONObject(execGetText(url))
        parseCollectionPage(json)
    }

    override suspend fun getEpisodes(mediaId: String, cursor: String): EpisodePage = withContext(Dispatchers.IO) {
        val total = totalEpisodeCache[mediaId] ?: 60
        val eps = (1..total).map { num ->
            EpisodeItem(
                episodeId = num.toString(),
                number = num,
                title = "Episode $num",
                isLocked = false
            )
        }
        EpisodePage(episodes = eps, nextCursor = "", hasMore = false)
    }

    override suspend fun resolvePlayback(mediaId: String, episodeId: String): PlaybackSource =
        withContext(Dispatchers.IO) {
            val seq = episodeId.toIntOrNull() ?: 1
            val url = buildUrl(
                "/tiktok/md/collection/video/v1/",
                mapOf(
                    "collection_id" to mediaId,
                    "count" to "1",
                    "current_seq_id" to seq.toString(),
                    "direction" to "1",
                    "query_type" to "seq_id",
                    "query_scene" to "mini_drama_container",
                    "series_category" to "1",
                    "series_feed_type" to "501",
                    "enter_from" to "homepage_series",
                    "target_language" to "id"
                )
            )
            val raw = execGetBytes(url)
            val extracted = if (NativeCore.isAvailable()) {
                NativeCore.extractCdnUrls(raw)
                    .filter { it.contains("tiktokcdn.com/") || it.contains("byteicdn.com/") }
                    .filter { it.contains("/video/") }
            } else {
                val text = raw.toString(Charsets.ISO_8859_1)
                videoUrlRegex.findAll(text).map { it.value }.toList()
            }

            val bestUrl = extracted.firstOrNull { it.contains("v31-id-telin") }
                ?: extracted.firstOrNull { it.contains("v3.tiktokcdn.com") }
                ?: extracted.firstOrNull()
                .orEmpty()

            PlaybackSource(
                streamUrl = bestUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept-Language" to "id-ID,id;q=0.9"
                )
            )
        }

    private fun parseCollectionPage(json: JSONObject): FeedPage {
        val arr = json.optJSONArray("collections") ?: JSONArray()
        val items = buildList {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val cid = c.optString("collection_id_str").ifBlank { c.optLong("collection_id").toString() }
                val title = c.optString("title")
                if (cid.isBlank() || title.isBlank()) continue
                val numVideos = c.optInt("num_videos", 0)
                if (numVideos > 0) totalEpisodeCache[cid] = numVideos

                val urls = c.optJSONObject("cover")?.optJSONArray("url_list")
                var cover = ""
                if (urls != null) {
                    for (u in 0 until urls.length()) {
                        val candidate = urls.optString(u)
                        if (candidate.contains(".jpeg")) {
                            cover = candidate
                            break
                        }
                    }
                    if (cover.isEmpty() && urls.length() > 0) cover = urls.optString(0)
                }

                val tags = c.optString("categories")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val badge = when {
                    c.optBoolean("label_hot", false) -> "HOT"
                    c.optBoolean("label_new", false) -> "BARU"
                    c.optBoolean("is_limited_free", false) -> "GRATIS"
                    else -> null
                }

                add(
                    MediaItem(
                        providerId = id,
                        mediaId = cid,
                        title = title,
                        coverUrl = cover,
                        tags = tags,
                        totalEpisodes = numVideos,
                        badge = badge
                    )
                )
            }
        }
        return FeedPage(
            items = items,
            nextCursor = json.optString("cursor", ""),
            hasMore = json.optBoolean("has_more", false)
        )
    }

    private fun parseFilterLadders(arr: JSONArray?): List<FilterLadder> {
        if (arr == null) return emptyList()
        val titles = listOf("Pengaturan", "Karakter", "Alur Cerita", "Waktu Rilis")
        return buildList {
            for (i in 0 until arr.length()) {
                val group = arr.optJSONObject(i) ?: continue
                val opts = group.optJSONArray("category_filters") ?: continue
                val parsed = buildList {
                    for (j in 0 until opts.length()) {
                        val o = opts.optJSONObject(j) ?: continue
                        val name = o.optString("name")
                        val ft = o.optInt("filter_type", 2)
                        val oid = when {
                            ft == 3 && name.contains("7") -> "7"
                            ft == 3 && name.contains("30") -> "30"
                            else -> o.optString("category_id", "0")
                        }
                        add(FilterOption(id = oid, name = name, filterType = ft))
                    }
                }
                add(FilterLadder(id = "ladder_$i", title = titles.getOrElse(i) { "Filter ${i + 1}" }, options = parsed))
            }
        }
    }

    private fun buildUrl(path: String, extra: Map<String, String> = emptyMap()): String {
        val builder = "https://api.tiktokv.com$path".toHttpUrl().newBuilder()
            .addQueryParameter("aid", "845221")
            .addQueryParameter("app_name", "pinedrama")
            .addQueryParameter("version_code", "470804")
            .addQueryParameter("version_name", "47.8.4")
            .addQueryParameter("device_platform", "android")
            .addQueryParameter("os", "android")
            .addQueryParameter("os_version", "16")
            .addQueryParameter("device_type", "Infinix X6870")
            .addQueryParameter("language", "id")
            .addQueryParameter("app_language", "id")
            .addQueryParameter("locale", "id-ID")
            .addQueryParameter("region", "ID")
            .addQueryParameter("current_region", "ID")
            .addQueryParameter("sys_region", "ID")
            .addQueryParameter("carrier_region", "ID")
            .addQueryParameter("timezone_name", "Asia/Jakarta")
            .addQueryParameter("tz_name", "Asia/Jakarta")
        extra.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun execGetText(url: String): String = execGetBytes(url).toString(Charsets.UTF_8)

    private fun execGetBytes(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "id-ID,id;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun execPostJson(url: String, body: JSONObject): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(mediaType))
            .header("User-Agent", userAgent)
            .header("Accept-Language", "id-ID,id;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.string().orEmpty()
        }
    }
}
