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

    override suspend fun getForYouTabs(): List<TabSpec> = listOf(
        TabSpec("mini_drama", "Mini Drama", isDefault = true),
        TabSpec("anime", "Anime")
    )

    override suspend fun getForYouFeed(tabId: String, cursor: String): FeedPage =
        if (tabId == "anime") {
            getFilteredFeed(listOf(FilterSelection("ladder_0", "7628991192537158672", 2)), cursor)
        } else {
            fetchMiniDramaSwipeFeed(cursor)
        }

    private suspend fun fetchMiniDramaSwipeFeed(cursor: String): FeedPage = withContext(Dispatchers.IO) {
        val pullType = if (cursor.isBlank()) "0" else "2"
        val url = buildUrl(
            "/tiktok/md/mini_drama/feed/v1/",
            mapOf(
                "count" to "10",
                "pull_type" to pullType,
                "series_feed_type" to "501"
            )
        )
        val raw = execGetBytes(url)
        if (raw.isEmpty()) return@withContext FeedPage(emptyList(), "", false)
        parseMiniDramaFeedProtobuf(raw, cursor)
    }

    private fun parseMiniDramaFeedProtobuf(raw: ByteArray, cursor: String): FeedPage {
        var hasMore = false
        val items = mutableListOf<MediaItem>()
        val seenIds = HashSet<String>()

        forEachProtoField(raw, 0, raw.size) { fn, wt, vStart, vEnd, varintVal ->
            if (fn == 3 && wt == 0) {
                hasMore = varintVal != 0L
            } else if (fn == 4 && wt == 2) {
                var cid = ""
                var title = ""
                var totalEps = 0
                val tags = mutableListOf<String>()
                val coverUrls = mutableListOf<String>()

                forEachProtoField(raw, vStart, vEnd) { afn, awt, avStart, avEnd, _ ->
                    if (afn == 317 && awt == 2) {
                        forEachProtoField(raw, avStart, avEnd) { sfn, swt, svStart, svEnd, sVar ->
                            when {
                                sfn == 1 && swt == 0 -> cid = sVar.toString()
                                sfn == 13 && swt == 2 -> title = String(raw, svStart, svEnd - svStart, Charsets.UTF_8)
                                sfn == 26 && swt == 2 -> {
                                    val tag = String(raw, svStart, svEnd - svStart, Charsets.UTF_8).trim()
                                    if (tag.isNotEmpty()) tags.add(tag)
                                }
                                sfn == 27 && swt == 2 -> {
                                    forEachProtoField(raw, svStart, svEnd) { cfn, cwt, cvStart, cvEnd, _ ->
                                        if (cfn == 2 && cwt == 2) {
                                            coverUrls.add(String(raw, cvStart, cvEnd - cvStart, Charsets.UTF_8))
                                        }
                                    }
                                }
                                sfn == 31 && swt == 2 && totalEps <= 0 -> {
                                    val extraJson = String(raw, svStart, svEnd - svStart, Charsets.UTF_8)
                                    val n = runCatching { JSONObject(extraJson).optInt("video_total_nums", 0) }.getOrDefault(0)
                                    if (n > 0) totalEps = n
                                }
                                sfn == 30 && swt == 2 && totalEps <= 0 -> {
                                    val epText = String(raw, svStart, svEnd - svStart, Charsets.UTF_8)
                                    val num = Regex("""(\d+)""").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                                    if (num > 0) totalEps = num
                                }
                            }
                        }
                    }
                }

                if (cid.isNotBlank() && title.isNotBlank() && seenIds.add(cid)) {
                    if (totalEps > 0) totalEpisodeCache[cid] = totalEps
                    val cover = coverUrls.firstOrNull { it.contains(".jpeg") } ?: coverUrls.firstOrNull().orEmpty()
                    items.add(
                        MediaItem(
                            providerId = id,
                            mediaId = cid,
                            title = title,
                            coverUrl = cover,
                            tags = tags,
                            totalEpisodes = totalEps
                        )
                    )
                }
            }
        }

        val nextCursor = if (hasMore && items.isNotEmpty()) ((cursor.toIntOrNull() ?: 0) + 1).toString() else ""
        return FeedPage(items = items, nextCursor = nextCursor, hasMore = nextCursor.isNotEmpty())
    }

    private inline fun forEachProtoField(
        buf: ByteArray,
        start: Int,
        end: Int,
        onField: (fieldNumber: Int, wireType: Int, valStart: Int, valEnd: Int, varintVal: Long) -> Unit
    ) {
        var pos = start
        while (pos < end) {
            val (tag, nextPos) = readVarint(buf, pos, end)
            if (nextPos <= pos) break
            pos = nextPos
            val fn = (tag ushr 3).toInt()
            val wt = (tag and 7L).toInt()
            if (fn <= 0) break
            when (wt) {
                0 -> {
                    val (v, p2) = readVarint(buf, pos, end)
                    onField(fn, wt, pos, p2, v)
                    pos = p2
                }
                1 -> {
                    if (pos + 8 > end) return
                    pos += 8
                }
                2 -> {
                    val (len, p2) = readVarint(buf, pos, end)
                    val l = len.toInt()
                    if (l < 0 || p2 + l > end) return
                    onField(fn, wt, p2, p2 + l, 0L)
                    pos = p2 + l
                }
                5 -> {
                    if (pos + 4 > end) return
                    pos += 4
                }
                else -> return
            }
        }
    }

    private fun readVarint(buf: ByteArray, start: Int, end: Int): Pair<Long, Int> {
        var pos = start
        var res = 0L
        var shift = 0
        while (pos < end && shift < 64) {
            val b = buf[pos++].toInt() and 0xFF
            res = res or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return res to pos
            shift += 7
        }
        return res to pos
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
