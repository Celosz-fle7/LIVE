package com.liveStream.app.provider.freereels

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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FreeReelsProvider : DramaProvider {
    override val id: String = "freereels"
    override val displayName: String = "FreeReels"
    override val iconUrl: String =
        "https://play-lh.googleusercontent.com/1ybMEp7xw4kTY-K7ocih3vcbTEyCEqaxerVB6tDT8WcE3k5zCRR-jhKwS6Ct0UXIorczCIat3wTV0QLiuoLPdbY=w240-h480"

    private val baseUrl = "https://apiv2.free-reels.com/frv2-api"
    private val salt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv"
    private val deviceId = UUID.randomUUID().toString().replace("-", "")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val authMutex = Mutex()
    private var authKey: String? = null
    private var authSecret: String? = null
    private val episodeSourceCache = ConcurrentHashMap<String, PlaybackSource>()

    override suspend fun getTabs(): List<TabSpec> = listOf(
        TabSpec("503", "Populer", isDefault = true),
        TabSpec("516", "Dubbing"),
        TabSpec("505", "Baru"),
        TabSpec("504", "Perempuan"),
        TabSpec("506", "Laki-Laki"),
        TabSpec("547", "Anime"),
        TabSpec("597", "Teater")
    )

    override suspend fun getFeed(tabId: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        ensureAuth()
        val page = cursor.toIntOrNull()?.coerceAtLeast(1) ?: 1

        if (tabId == "516" && page > 1) {
            val offset = (page - 2) * 10
            val body = JSONObject().apply {
                put("module_key", "612")
                put("offset", offset)
            }
            val json = execPost("/homepage/v2/tab/feed", body)
            val arr = json.optJSONObject("data")?.optJSONArray("items")
                ?: json.optJSONObject("data")?.optJSONArray("list")
                ?: JSONArray()
            val items = parseDramaArray(arr)
            return@withContext FeedPage(
                items = items,
                nextCursor = (page + 1).toString(),
                hasMore = items.size >= 10
            )
        }

        val pos = when (tabId) {
            "597" -> (1 + (page - 1) * 30).toString()
            "547" -> (10001 + (page - 1) * 10).toString()
            else -> (10000 + (page - 1) * 10).toString()
        }
        val json = execGet("/homepage/v2/tab/index?tab_key=$tabId&position_index=$pos")
        val modules = json.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        val collected = mutableListOf<MediaItem>()
        for (i in 0 until modules.length()) {
            val mod = modules.optJSONObject(i) ?: continue
            val modItems = mod.optJSONArray("items") ?: continue
            collected.addAll(parseDramaArray(modItems))
        }
        val unique = collected.distinctBy { it.mediaId }
        FeedPage(
            items = unique,
            nextCursor = (page + 1).toString(),
            hasMore = unique.size >= 10
        )
    }

    override suspend fun getForYouFeed(cursor: String): FeedPage = withContext(Dispatchers.IO) {
        ensureAuth()
        val page = cursor.toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (page == 1) {
            val json = execGet("/foryou/feed")
            val arr = json.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
            val items = parseDramaArray(arr)
            if (items.isNotEmpty()) {
                return@withContext FeedPage(items = items, nextCursor = "2", hasMore = true)
            }
        }
        // Halaman 2+: lanjutkan dari Teater (597) agar scroll Untukmu tidak berhenti di 10 item
        val teaterPage = (page - 1).coerceAtLeast(1)
        val feed = getFeed(tabId = "597", cursor = teaterPage.toString())
        FeedPage(
            items = feed.items,
            nextCursor = (page + 1).toString(),
            hasMore = feed.items.isNotEmpty()
        )
    }

    override suspend fun getFilterLadders(): List<FilterLadder> = listOf(
        FilterLadder(
            id = "kategori",
            title = "Kategori & Genre",
            options = listOf(
                FilterOption("0", "Semua", 1),
                FilterOption("516", "Sulih Suara (Dubbing)", 2),
                FilterOption("504", "Romansa (Wanita)", 2),
                FilterOption("506", "Aksi & Urban (Pria)", 2),
                FilterOption("547", "Anime", 2),
                FilterOption("505", "Rilis Terbaru", 2),
                FilterOption("597", "Teater Lengkap", 2)
            )
        )
    )

    override suspend fun getFilteredFeed(filters: List<FilterSelection>, cursor: String): FeedPage {
        val selected = filters.firstOrNull()?.optionId?.takeIf { it != "0" } ?: "503"
        return getFeed(tabId = selected, cursor = cursor)
    }

    override suspend fun search(query: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        ensureAuth()
        val page = cursor.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val body = JSONObject().apply {
            put("keyword", query)
            put("page", page)
            put("page_size", 20)
        }
        val json = execPost("/search/drama", body)
        val data = json.optJSONObject("data")
        val arr = data?.optJSONArray("items") ?: data?.optJSONArray("list") ?: JSONArray()
        val items = parseDramaArray(arr)
        FeedPage(
            items = items,
            nextCursor = (page + 1).toString(),
            hasMore = items.size >= 20
        )
    }

    override suspend fun getEpisodes(mediaId: String, cursor: String): EpisodePage = withContext(Dispatchers.IO) {
        ensureAuth()
        val json = execGet("/drama/info_v2?series_id=$mediaId")
        val info = json.optJSONObject("data")?.optJSONObject("info") ?: JSONObject()
        val rawList = info.optJSONArray("episode_list") ?: JSONArray()

        val episodes = buildList {
            for (i in 0 until rawList.length()) {
                val ep = rawList.optJSONObject(i) ?: continue
                val num = ep.optInt("episode_number", ep.optInt("index", i + 1))
                val epId = ep.optString("episode_id").ifBlank { ep.optString("id", num.toString()) }
                val title = ep.optString("title").ifBlank { "Episode $num" }
                val extH264 = ep.optString("external_audio_h264_m3u8")
                val m3u8 = ep.optString("m3u8_url")
                val video = ep.optString("video_url")
                val streamUrl = extH264.ifBlank { m3u8.ifBlank { video } }

                val subs = buildList {
                    val subArr = ep.optJSONArray("subtitle_list") ?: JSONArray()
                    for (j in 0 until subArr.length()) {
                        val s = subArr.optJSONObject(j) ?: continue
                        val sUrl = s.optString("subtitle").ifBlank { s.optString("url") }
                        if (sUrl.isNotBlank()) {
                            val lang = s.optString("language", "id")
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
                    if (m3u8.isNotBlank()) add(AudioTrack("zh", "zh-CN", "Mandarin", isDefault = extH264.isBlank()))
                }

                val locked = streamUrl.isBlank() || ep.optBoolean("is_lock", false)
                if (streamUrl.isNotBlank()) {
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
                        isLocked = locked
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
            val epCount = obj.optInt("episode_count", obj.optInt("total_episodes", 0))
            val tag = if (title.contains("Sulih Suara", ignoreCase = true)) listOf("Dubbing") else emptyList()
            val badge = if (obj.optBoolean("free", false)) "GRATIS" else null
            add(
                MediaItem(
                    providerId = id,
                    mediaId = key,
                    title = title,
                    coverUrl = cover,
                    tags = tag,
                    totalEpisodes = epCount,
                    badge = badge
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
        .header("app-version", "1.2.0")
        .header("app-name", "com.freereels.app")
        .header("device", "android")
        .header("language", "id-ID")
        .header("country", "ID")
        .header("device-id", deviceId)
        .header("x-device-model", "Pixel 6")
        .header("screen-width", "1080")
        .header("screen-height", "2400")

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
