package com.liveStream.app.provider.melolo

import android.util.Base64
import com.liveStream.contract.AudioTrack
import com.liveStream.contract.DramaProvider
import com.liveStream.contract.EpisodeItem
import com.liveStream.contract.EpisodePage
import com.liveStream.contract.FeedPage
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
    override val iconUrl: String = ""

    private val baseUrl = "https://api22-normal-mya.tmtreader.com"
    private val userAgent =
        "com.worldance.drama/55520 (Linux; U; Android 16; id_ID; Infinix X6870; Build/UP1A.231005.007; Cronet/TTNetVersion:5303bf90)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val commonParams = mapOf(
        "aid" to "645713",
        "device_id" to "7514640337227908615",
        "iid" to "7712508844723128069",
        "app_name" to "Melolo",
        "version_code" to "55520",
        "version_name" to "5.5.5",
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to "Infinix X6870",
        "device_brand" to "Infinix",
        "language" to "id",
        "os_api" to "36",
        "os_version" to "16",
        "openudid" to "83a70344deb08242",
        "manifest_version_code" to "55520",
        "resolution" to "1080*2260",
        "dpi" to "351",
        "update_version_code" to "55520",
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
                "video_platform" to "3"
            )
            val bodyStr = payloadMap.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val json = execPostForm("/novel/player/video_model/v1/", commonParams, bodyStr)
            if (json.optInt("code", -1) != 0) {
                return@withContext PlaybackSource(streamUrl = "")
            }

            val data = json.optJSONObject("data") ?: return@withContext PlaybackSource(streamUrl = "")
            var rawVm = data.opt("video_model") ?: data.opt("videoModel") ?: data.opt("vodVideoRef")
            if (rawVm is String && rawVm.isNotEmpty()) {
                rawVm = runCatching { JSONObject(rawVm) }.getOrNull()
            }
            val vm = rawVm as? JSONObject ?: return@withContext PlaybackSource(streamUrl = "")
            val vod = vm.optJSONObject("vodVideoRef") ?: vm

            var streamUrl = ""
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
                }
            }

            PlaybackSource(
                streamUrl = streamUrl,
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

    private fun execPostForm(path: String, params: Map<String, String>, formBody: String): JSONObject {
        val url = buildUrl(path, params)
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

    private fun buildUrl(path: String, params: Map<String, String>): String {
        val b = "$baseUrl$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }
}
