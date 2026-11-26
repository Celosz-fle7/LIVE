package com.liveStream.app.provider.dramawave

import com.liveStream.contract.AudioTrack
import com.liveStream.contract.DramaProvider
import com.liveStream.contract.EpisodeItem
import com.liveStream.contract.EpisodePage
import com.liveStream.contract.FeedPage
import com.liveStream.contract.FilterLadder
import com.liveStream.contract.FilterOption
import com.liveStream.contract.FilterSelection
import com.liveStream.contract.MediaItem
import com.liveStream.contract.PlaybackSource
import com.liveStream.contract.SubtitleTrack
import com.liveStream.contract.TabSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Thin Protocol Mirror untuk DramaWave (com.dramawave.app v2.0.90).
 * Gateway: https://api.mydramawave.com/dm-api
 */
class DramaWaveProvider : DramaProvider {
    override val id: String = "dramawave"
    override val displayName: String = "DramaWave"
    override val iconUrl: String =
        "https://play-lh.googleusercontent.com/qdfS5KciqnG6lP2HCxKT-CoeWrGdXsxe2sQgRR_-X-G_MVG5WZljSIesMUPkAkic0cjxgsp7gA6WMNXaq-lA6iU=w240-h480"

    private val baseUrl = "https://api.mydramawave.com/dm-api"
    private val salt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv"
    private val deviceId = UUID.randomUUID().toString().replace("-", "").take(16)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val authMutex = Mutex()
    private var authKey: String? = null
    private var authSecret: String? = null
    private val episodeSourceCache = ConcurrentHashMap<String, PlaybackSource>()

    private val tabPositions = mapOf(
        "125" to "1",
        "126" to "20",
        "475" to "25",
        "123" to "40",
        "127" to "50",
        "129" to "60",
        "130" to "70",
        "131" to "80",
        "132" to "90"
    )

    override suspend fun getTabs(): List<TabSpec> = listOf(
        TabSpec("125", "Populer", isDefault = true),
        TabSpec("126", "Baru"),
        TabSpec("475", "Top 10"),
        TabSpec("123", "Anime"),
        TabSpec("127", "Eksklusif"),
        TabSpec("129", "Dubbing"),
        TabSpec("130", "VIP"),
        TabSpec("131", "Wanita"),
        TabSpec("132", "Pria")
    )

    override suspend fun getFeed(tabId: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        ensureAuth()

        if (cursor.contains("|")) {
            val parts = cursor.split("|", limit = 2)
            val moduleKey = parts[0]
            val nextVal = parts.getOrElse(1) { "" }
            val body = JSONObject().apply {
                put("module_key", moduleKey)
                put("next", nextVal)
            }
            val json = execPost("/homepage/v2/tab/feed", body)
            val data = json.optJSONObject("data") ?: JSONObject()
            val arr = data.optJSONArray("items") ?: JSONArray()
            val items = parseDramaArray(arr)
            val pageInfo = data.optJSONObject("page_info")
            val nextCur = pageInfo?.optString("next").orEmpty()
            val hasMore = pageInfo?.optBoolean("has_more", false) == true && nextCur.isNotBlank()
            return@withContext FeedPage(
                items = items,
                nextCursor = if (hasMore) "$moduleKey|$nextCur" else "",
                hasMore = hasMore
            )
        }

        val tk = tabId.ifBlank { "125" }
        val pos = tabPositions[tk] ?: "1"
        val json = execGet("/homepage/v2/tab/index?tab_key=$tk&position_index=$pos")
        val modules = json.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        val collected = mutableListOf<MediaItem>()
        var feedModuleKey = ""
        var feedNext = ""

        for (i in 0 until modules.length()) {
            val mod = modules.optJSONObject(i) ?: continue
            val modItems = mod.optJSONArray("items") ?: continue
            collected.addAll(parseDramaArray(modItems))

            if (feedModuleKey.isEmpty()) {
                val mk = mod.optString("module_key")
                val pageInfo = mod.optJSONObject("page_info")
                val nxt = pageInfo?.optString("next").orEmpty()
                val more = pageInfo?.optBoolean("has_more", false) == true
                if (mk.isNotBlank() && (more || nxt.isNotBlank())) {
                    feedModuleKey = mk
                    feedNext = nxt
                } else if (mk.isNotBlank() && tk == "125") {
                    feedModuleKey = mk
                    feedNext = ""
                }
            }
        }

        val unique = collected.distinctBy { it.mediaId }
        val nextCursor = if (feedModuleKey.isNotBlank()) "$feedModuleKey|$feedNext" else ""
        FeedPage(
            items = unique,
            nextCursor = nextCursor,
            hasMore = nextCursor.isNotBlank() && unique.isNotEmpty()
        )
    }

    override suspend fun getForYouTabs(): List<TabSpec> = listOf(
        TabSpec("foryou", "Untukmu", isDefault = true),
        TabSpec("anime", "Anime")
    )

    override suspend fun getForYouFeed(tabId: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        ensureAuth()
        val sub = if (tabId == "anime") "anime" else "foryou"
        val encoded = URLEncoder.encode(cursor, "UTF-8")
        val json = execGet("/foryou/feed?tab_key=$sub&next=$encoded")
        val data = json.optJSONObject("data") ?: JSONObject()
        val arr = data.optJSONArray("items") ?: JSONArray()
        val items = parseDramaArray(arr)
        val pageInfo = data.optJSONObject("page_info")
        val nextCur = pageInfo?.optString("next").orEmpty()
        val hasMore = pageInfo?.optBoolean("has_more", nextCur.isNotBlank()) == true && nextCur.isNotBlank()
        FeedPage(
            items = items,
            nextCursor = nextCur,
            hasMore = hasMore
        )
    }

    override suspend fun getFilterLadders(): List<FilterLadder> = listOf(
        FilterLadder(
            id = "kategori",
            title = "Kategori & Genre",
            options = listOf(
                FilterOption("125", "Populer (Semua)", 1),
                FilterOption("129", "Sulih Suara (Dubbing)", 2),
                FilterOption("131", "Romansa (Wanita)", 2),
                FilterOption("132", "Aksi & Urban (Pria)", 2),
                FilterOption("123", "Anime", 2),
                FilterOption("126", "Baru Rilis", 2),
                FilterOption("127", "Eksklusif", 2),
                FilterOption("130", "VIP", 2),
                FilterOption("475", "Top 10", 2)
            )
        )
    )

    override suspend fun getFilteredFeed(filters: List<FilterSelection>, cursor: String): FeedPage {
        val selected = filters.firstOrNull()?.optionId?.takeIf { it.isNotBlank() } ?: "125"
        return getFeed(tabId = selected, cursor = cursor)
    }

    override suspend fun search(query: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        ensureAuth()
        val body = JSONObject().apply {
            put("keyword", query)
            put("next", cursor)
        }
        val json = execPost("/search/drama", body)
        val data = json.optJSONObject("data") ?: JSONObject()
        val arr = data.optJSONArray("items") ?: data.optJSONArray("list") ?: JSONArray()
        val items = parseDramaArray(arr)
        val pageInfo = data.optJSONObject("page_info")
        val nextCur = pageInfo?.optString("next").orEmpty()
        val hasMore = pageInfo?.optBoolean("has_more", false) == true && nextCur.isNotBlank()
        FeedPage(
            items = items,
            nextCursor = nextCur,
            hasMore = hasMore
        )
    }

    override suspend fun getEpisodes(mediaId: String, cursor: String): EpisodePage = withContext(Dispatchers.IO) {
        ensureAuth()
        val json = execGet("/drama/info?series_id=$mediaId")
        val info = json.optJSONObject("data")?.optJSONObject("info") ?: JSONObject()
        val rawList = info.optJSONArray("episode_list") ?: JSONArray()

        val episodes = buildList {
            for (i in 0 until rawList.length()) {
                val ep = rawList.optJSONObject(i) ?: continue
                val num = ep.optInt("index", ep.optInt("episode_number", i + 1)).let {
                    if (it <= 0) i + 1 else it
                }
                val epId = ep.optString("id").ifBlank { ep.optString("episode_id", num.toString()) }
                val title = ep.optString("name").ifBlank { ep.optString("title", "Episode $num") }
                val extH264 = ep.optString("external_audio_h264_m3u8")
                val m3u8 = ep.optString("m3u8_url")
                val video = ep.optString("video_url")
                val rawStream = extH264.ifBlank { m3u8.ifBlank { video } }
                val unlocked = ep.optBoolean("unlock", rawStream.isNotBlank()) && rawStream.isNotBlank()
                val streamUrl = if (unlocked) rawStream else ""

                val subs = buildList {
                    val subArr = ep.optJSONArray("subtitle_list") ?: JSONArray()
                    for (j in 0 until subArr.length()) {
                        val s = subArr.optJSONObject(j) ?: continue
                        val sUrl = s.optString("vtt").ifBlank {
                            s.optString("subtitle").ifBlank { s.optString("url") }
                        }
                        if (sUrl.isNotBlank()) {
                            val lang = s.optString("language", "id-ID")
                            val label = s.optString("display_name", lang)
                            val mime = if (sUrl.contains(".vtt", ignoreCase = true)) {
                                "text/vtt"
                            } else {
                                "application/x-subrip"
                            }
                            add(SubtitleTrack(url = sUrl, langCode = lang, label = label, mimeType = mime))
                        }
                    }
                }

                val audios = buildList {
                    if (extH264.isNotBlank()) add(AudioTrack("id", "id-ID", "Indonesia", isDefault = true))
                    if (m3u8.isNotBlank()) add(AudioTrack("orig", ep.optString("original_audio_language", "zh-CN"), "Original", isDefault = extH264.isBlank()))
                }

                if (unlocked) {
                    episodeSourceCache["$mediaId:$epId"] = PlaybackSource(
                        streamUrl = streamUrl,
                        headers = mapOf("User-Agent" to "okhttp/4.12.0"),
                        subtitles = subs,
                        audioTracks = audios
                    )
                }

                add(
                    EpisodeItem(
                        episodeId = epId,
                        number = num,
                        title = title,
                        isLocked = !unlocked,
                        durationSec = ep.optInt("duration", 0)
                    )
                )
            }
        }
        EpisodePage(episodes = episodes, nextCursor = "", hasMore = false)
    }

    override suspend fun resolvePlayback(mediaId: String, episodeId: String): PlaybackSource =
        withContext(Dispatchers.IO) {
            episodeSourceCache["$mediaId:$episodeId"]?.let { return@withContext it }
            getEpisodes(mediaId)
            episodeSourceCache["$mediaId:$episodeId"] ?: PlaybackSource(streamUrl = "")
        }

    private fun parseDramaArray(arr: JSONArray): List<MediaItem> = buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val key = obj.optString("key").ifBlank { obj.optString("id") }
            val title = obj.optString("title").ifBlank { obj.optString("name") }
            if (key.isBlank() || title.isBlank()) continue
            val cover = obj.optString("cover")
            val epCount = obj.optInt("episode_count", obj.optInt("update_count", 0))
            val tagsArr = obj.optJSONArray("tag")
            val tags = buildList {
                if (tagsArr != null) {
                    for (t in 0 until tagsArr.length()) {
                        val tv = tagsArr.optString(t)
                        if (tv.isNotBlank()) add(tv)
                    }
                } else if (title.contains("Sulih Suara", ignoreCase = true)) {
                    add("Dubbing")
                }
            }
            add(
                MediaItem(
                    providerId = id,
                    mediaId = key,
                    title = title,
                    coverUrl = cover,
                    tags = tags,
                    totalEpisodes = epCount,
                    description = obj.optString("desc")
                )
            )
        }
    }

    private suspend fun ensureAuth() = authMutex.withLock {
        if (authKey != null && authSecret != null) return@withLock
        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val req = Request.Builder()
            .url("$baseUrl/anonymous/login")
            .post("""{"device_id":"$deviceId"}""".toRequestBody(mediaType))
            .applyBaseHeaders()
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(txt) }.getOrNull() ?: return@withLock
            if (json.optInt("code") == 200) {
                val data = json.optJSONObject("data") ?: return@withLock
                authKey = data.optString("auth_key").takeIf { it.isNotBlank() }
                authSecret = data.optString("auth_secret").takeIf { it.isNotBlank() }
            }
        }
    }

    private fun execGet(path: String): JSONObject {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .applyBaseHeaders()
            .applyAuthHeader()
            .build()
        client.newCall(req).execute().use { resp ->
            return runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrElse { JSONObject() }
        }
    }

    private fun execPost(path: String, body: JSONObject): JSONObject {
        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val req = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toString().toRequestBody(mediaType))
            .applyBaseHeaders()
            .applyAuthHeader()
            .build()
        client.newCall(req).execute().use { resp ->
            return runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrElse { JSONObject() }
        }
    }

    private fun Request.Builder.applyBaseHeaders(): Request.Builder = this
        .header("User-Agent", "okhttp/4.12.0")
        .header("Accept", "application/json")
        .header("app-version", "2.0.90")
        .header("app-name", "com.dramawave.app")
        .header("device", "android")
        .header("language", "id-ID")
        .header("country", "ID")
        .header("device-id", deviceId)

    private fun Request.Builder.applyAuthHeader(): Request.Builder {
        val key = authKey ?: return this
        val secret = authSecret ?: return this
        val ts = System.currentTimeMillis()
        val sig = md5Hex("$salt&$secret")
        return header("Authorization", "oauth_signature=$sig,oauth_token=$key,ts=$ts")
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
