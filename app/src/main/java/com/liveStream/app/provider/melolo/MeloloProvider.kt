package com.liveStream.app.provider.melolo

import android.util.Base64
import android.util.Log
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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MeloloProvider : DramaProvider {
    override val id: String = "melolo"
    override val displayName: String = "Melolo"
    override val iconUrl: String =
        "https://play-lh.googleusercontent.com/HEkeCbNIuDdTlBr_2vuey1BeBTc-0O_RwemiQ80eWKGJFyJrq83Kk-34wOK_jqyXJ_XBP7eh3eoGg2nBbZd0eQ=w240-h480"

    private val baseUrl = "https://api22-normal-mya.tmtreader.com"
    private val fallbackBaseUrl = "https://api.tmtreader.com"
    private val userAgent =
        "com.worldance.drama/55219 (Linux; U; Android 16; id; Infinix X6870; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:57545f6e 2025-08-04 QuicVersion:ccae1727 2025-07-24)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val commonParams = mapOf(
        "aid" to "645713",
        "device_id" to "7514640337227908615",
        "iid" to "7706992491207231285",
        "app_name" to "Melolo",
        "version_code" to "55219",
        "version_name" to "5.5.2",
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to "Infinix X6870",
        "device_brand" to "Infinix",
        "language" to "id",
        "os_api" to "36",
        "os_version" to "16",
        "openudid" to "83a70344deb08242",
        "manifest_version_code" to "55219",
        "resolution" to "1080*2260",
        "dpi" to "351",
        "update_version_code" to "55219",
        "current_region" to "ID",
        "carrier_region" to "id",
        "app_language" to "id",
        "sys_language" to "id",
        "app_region" to "ID",
        "sys_region" to "ID",
        "mcc_mnc" to "51010",
        "carrier_region_v2" to "510",
        "user_language" to "id",
        "time_zone" to "Asia/Jakarta",
        "ui_language" to "id",
        "cdid" to "bddd0ca1-df26-4f6b-a376-35deff350397",
        "clientudid" to "50873462-4fbd-4e38-9ae3-6e9dea879bf9",
        "channel" to "gp",
        "ac" to "wifi"
    )

    override suspend fun getTabs(): List<TabSpec> = listOf(
        TabSpec("Populer", "Populer", isDefault = true),
        TabSpec("Dubbing", "Dubbing"),
        TabSpec("Anime", "Anime"),
        TabSpec("Terbaru", "Baru"),
        TabSpec("Drama", "Drama")
    )

    override suspend fun getFeed(tabId: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        val offset = cursor.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val query = tabId.ifBlank { "Populer" }
        val params = commonParams + mapOf(
            "query" to query,
            "offset" to offset.toString(),
            "limit" to "20"
        )
        val json = execGet("/i18n_novel/search/page/v1/", params)
        parseSearchPage(json, offset)
    }

    override suspend fun getForYouTabs(): List<TabSpec> = listOf(
        TabSpec("Populer", "Untukmu", isDefault = true),
        TabSpec("Anime", "Anime")
    )

    override suspend fun getForYouFeed(tabId: String, cursor: String): FeedPage =
        getFeed(tabId = tabId.ifBlank { "Populer" }, cursor = cursor)

    override suspend fun getFilterLadders(): List<FilterLadder> = listOf(
        FilterLadder(
            id = "genre",
            title = "Genre & Tema",
            options = listOf(
                FilterOption("0", "Semua", 1),
                FilterOption("Dubbing", "Sulih Suara (Dubbing)", 2),
                FilterOption("Romansa", "Romansa", 2),
                FilterOption("Balas Dendam", "Balas Dendam", 2),
                FilterOption("Fantasi", "Fantasi", 2),
                FilterOption("CEO", "CEO", 2),
                FilterOption("Aksi", "Aksi", 2),
                FilterOption("Anime", "Anime", 2)
            )
        )
    )

    override suspend fun getFilteredFeed(filters: List<FilterSelection>, cursor: String): FeedPage {
        val selected = filters.firstOrNull()?.optionId?.takeIf { it != "0" } ?: "Populer"
        return getFeed(tabId = selected, cursor = cursor)
    }

    override suspend fun search(query: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        val offset = cursor.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val params = commonParams + mapOf(
            "query" to query,
            "offset" to offset.toString(),
            "limit" to "30"
        )
        val json = execGet("/i18n_novel/search/page/v1/", params)
        parseSearchPage(json, offset)
    }

    override suspend fun getEpisodes(mediaId: String, cursor: String): EpisodePage = withContext(Dispatchers.IO) {
        val params = commonParams + mapOf("series_id" to mediaId)
        val bodyJson = JSONObject().apply { put("series_id", mediaId) }.toString()
        val json = execPostJson("/novel/player/video_detail/v1/", params, bodyJson)
        val vList = json.optJSONObject("data")
            ?.optJSONObject("video_data")
            ?.optJSONArray("video_list")
            ?: JSONArray()

        val episodes = buildList {
            for (i in 0 until vList.length()) {
                val ep = vList.optJSONObject(i) ?: continue
                val vid = ep.optString("vid")
                val num = ep.optInt("vid_index", i + 1)
                val title = "Episode $num"
                val locked = ep.optBoolean("disable_play", false) || vid.isBlank()
                add(
                    EpisodeItem(
                        episodeId = vid,
                        number = num,
                        title = title,
                        isLocked = locked,
                        durationSec = ep.optInt("duration", 0)
                    )
                )
            }
        }
        EpisodePage(episodes = episodes, nextCursor = "", hasMore = false)
    }

    override suspend fun resolvePlayback(mediaId: String, episodeId: String): PlaybackSource =
        withContext(Dispatchers.IO) {
            val payloadMap = mapOf(
                "series_id" to mediaId,
                "video_id" to episodeId,
                "item_id" to episodeId,
                "video_platform" to "3"
            )
            val bodyStr = payloadMap.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            var json = execPostForm("/novel/player/video_model/v1/", commonParams, bodyStr)
            var code = json.optInt("code", json.optJSONObject("BaseResp")?.optInt("StatusCode", -1) ?: -1)
            Log.d("MeloloProvider", "video_model(primary) media=$mediaId ep=$episodeId code=$code msg=${json.optString("message")}")
            if (code != 0) {
                json = execPostForm("/novel/player/video_model/v1/", commonParams, bodyStr, hostOverride = fallbackBaseUrl)
                code = json.optInt("code", json.optJSONObject("BaseResp")?.optInt("StatusCode", -1) ?: -1)
                Log.d("MeloloProvider", "video_model(fallback) media=$mediaId ep=$episodeId code=$code msg=${json.optString("message")}")
            }
            if (code != 0) {
                throw IllegalStateException("video_model code=$code msg=${json.optString("message")}")
            }

            val data = json.optJSONObject("data") ?: return@withContext PlaybackSource(streamUrl = "")
            var rawVm = data.opt("video_model") ?: data.opt("videoModel") ?: data.opt("vodVideoRef")
            if (rawVm is String) {
                val vmStr = rawVm
                if (vmStr.isNotEmpty()) {
                    rawVm = runCatching { JSONObject(vmStr) }.getOrNull()
                }
            }
            val vm = rawVm as? JSONObject ?: return@withContext PlaybackSource(streamUrl = "")
            val vod = vm.optJSONObject("vodVideoRef") ?: vm

            var streamUrl = ""
            var spadeA = ""
            // Format 1: vodVideoRef.mVideoList (Array)
            val mList = vod.optJSONArray("mVideoList")
            if (mList != null && mList.length() > 0) {
                var chosen: JSONObject? = null
                for (i in 0 until mList.length()) {
                    val e = mList.optJSONObject(i) ?: continue
                    val codec = "${e.optString("mCodecType")} ${e.optString("codec_type")} ${e.optString("mVType")}".lowercase()
                    if (codec.contains("h264") || codec.contains("264") || codec.contains("avc")) {
                        chosen = e
                        break
                    }
                }
                chosen = chosen ?: mList.optJSONObject(0)
                streamUrl = chosen?.optString("mMainUrl").orEmpty()
                spadeA = chosen?.optString("mSpadea").orEmpty().ifBlank { chosen?.optString("mSpadeA").orEmpty() }
            }

            // Format 2: video_list (Map)
            if (streamUrl.isBlank()) {
                val vMap = vm.optJSONObject("video_list")
                if (vMap != null) {
                    var chosen: JSONObject? = null
                    val keys = vMap.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val entry = vMap.optJSONObject(k) ?: continue
                        val codec = "${entry.optString("codec_type")} ${entry.optString("vtype")} ${entry.optString("definition")}".lowercase()
                        if (codec.contains("h264") || codec.contains("264") || codec.contains("avc")) {
                            chosen = entry
                            break
                        }
                    }
                    chosen = chosen ?: vMap.optJSONObject("video_1") ?: vMap.optJSONObject("video_2")
                    var rawUrl = chosen?.optString("main_url").orEmpty()
                    if (rawUrl.startsWith("aHR0c")) {
                        rawUrl = runCatching {
                            String(Base64.decode(rawUrl, Base64.DEFAULT), Charsets.UTF_8)
                        }.getOrDefault(rawUrl)
                    }
                    streamUrl = rawUrl
                    spadeA = chosen?.optString("spade_a").orEmpty()
                }
            }

            val finalUrl = if (streamUrl.isNotBlank() && spadeA.isNotBlank()) {
                MeloloCencProxy.preparePlayableUrl(
                    cacheKey = "${mediaId}_$episodeId",
                    rawUrl = streamUrl,
                    spadeA = spadeA,
                    client = client
                )
            } else {
                streamUrl
            }

            PlaybackSource(
                streamUrl = finalUrl,
                headers = mapOf("User-Agent" to "okhttp/3.10.0.1", "Accept" to "*/*"),
                audioTracks = listOf(AudioTrack("id", "id", "Indonesia", isDefault = true))
            )
        }

    private fun parseSearchPage(json: JSONObject, offset: Int): FeedPage {
        val data = json.optJSONObject("data") ?: return FeedPage(emptyList())
        val blocks = data.optJSONArray("search_data") ?: JSONArray()
        val items = mutableListOf<MediaItem>()
        for (i in 0 until blocks.length()) {
            val blk = blocks.optJSONObject(i) ?: continue
            val books = blk.optJSONArray("books") ?: continue
            for (j in 0 until books.length()) {
                val b = books.optJSONObject(j) ?: continue
                val bid = b.optString("book_id")
                val name = b.optString("book_name")
                if (bid.isBlank() || name.isBlank()) continue
                val cover = b.optString("thumb_url")
                val total = b.optInt("last_chapter_index", b.optInt("serial_count", 0))
                items.add(
                    MediaItem(
                        providerId = id,
                        mediaId = bid,
                        title = name,
                        coverUrl = cover,
                        totalEpisodes = total
                    )
                )
            }
        }
        val unique = items.distinctBy { it.mediaId }
        val hasMore = data.optBoolean("has_more", false)
        val nextOffset = data.optInt("next_offset", offset + unique.size)
        return FeedPage(
            items = unique,
            nextCursor = nextOffset.toString(),
            hasMore = hasMore && unique.isNotEmpty()
        )
    }

    private fun execGet(path: String, params: Map<String, String>): JSONObject {
        val url = buildUrl(path, params)
        val sig = MeloloSigner.signFromRaw(params = params)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json; charset=utf-8")
            .header("X-Xs-From-Web", "false")
        sig.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            return runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrElse { JSONObject() }
        }
    }

    private fun execPostJson(path: String, params: Map<String, String>, jsonBody: String): JSONObject {
        val url = buildUrl(path, params)
        val sig = MeloloSigner.signFromRaw(params = params, postBody = jsonBody)
        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val reqBuilder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(mediaType))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json; charset=utf-8")
            .header("X-Xs-From-Web", "false")
        sig.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            return runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrElse { JSONObject() }
        }
    }

    private fun execPostForm(
        path: String,
        params: Map<String, String>,
        formBody: String,
        hostOverride: String? = null
    ): JSONObject {
        val url = buildUrl(path, params, hostOverride)
        val sig = MeloloSigner.signFromRaw(params = params, postBody = formBody)
        val mediaType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        val reqBuilder = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody(mediaType))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json; charset=utf-8")
            .header("X-Xs-From-Web", "false")
        sig.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            return runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrElse { JSONObject() }
        }
    }

    private fun buildUrl(path: String, params: Map<String, String>, hostOverride: String? = null): String {
        val host = hostOverride ?: baseUrl
        val b = "$host$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }
}
