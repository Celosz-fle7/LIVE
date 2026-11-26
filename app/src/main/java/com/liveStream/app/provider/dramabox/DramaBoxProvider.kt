package com.liveStream.app.provider.dramabox

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
import com.liveStream.contract.SubtitleTrack
import com.liveStream.contract.TabSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Thin Protocol Mirror untuk DramaBox (com.storymatrix.drama v7.0.0).
 * Mendukung fallback host, auto-adopt `tn` token, dan dekripsi `.encrypt.mp4` via AliyunEcbDecryptor (mem://).
 */
class DramaBoxProvider : DramaProvider {
    override val id: String = "dramabox"
    override val displayName: String = "DramaBox"
    override val iconUrl: String =
        "https://play-lh.googleusercontent.com/GGi0g65ySR86svFuxny2J2FgfkTZuFlJcftmeAZ_bZ7sM86XfwUNL1h9omSyoRKha1c1-_BoFkUbyJz2VSXI=w240-h480"

    private val hosts = listOf(
        "https://sapi.dramaboxvideo.com/drama-box",
        "https://sapi.dramaboxdb.com/drama-box",
        "https://sapih3.dramaboxdb.com/drama-box"
    )
    private var activeHostIdx = 0
    private var lastServerError: String? = null

    private val deviceId: String = UUID.randomUUID().toString()
    private val androidId: String = UUID.randomUUID().toString().replace("-", "")
    private var token: String =
        "Bearer ZXlKMGVYQWlPaUpLVjFRaUxDSmhiR2NpT2lKSVV6STFOaUo5LmV5SnlaV2RwYzNSbGNsUjVjR1VpT2lKVVJVMVFJaXdpZFhObGNrbGtJam8xT1RnMU56YzRNamw5LktYZ2c2d0lST3g2dFBkWnNaMFFUcnZtWV9aMVlEam1oa3BfNUJCOVBMYTQ="

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val chapterIndexMap = ConcurrentHashMap<String, Int>()
    private val batchCache = ConcurrentHashMap<String, PlaybackSource>()

    override suspend fun getTabs(): List<TabSpec> = listOf(
        TabSpec("trending", "Sedang Tren", isDefault = true),
        TabSpec("popular", "Populer"),
        TabSpec("latest", "Terbaru"),
        TabSpec("vip", "VIP"),
        TabSpec("dubbing", "Sulih Suara")
    )

    override suspend fun getFeed(tabId: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        val page = cursor.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val raw = mutableListOf<JSONObject>()

        if (page == 1) {
            when (tabId) {
                "popular" -> {
                    collectArray(execPost("/he001/rank", JSONObject().put("rankType", 2)), "rankList", raw)
                    collectChannel(pageNo = 1, out = raw)
                }
                "latest" -> {
                    collectArray(execPost("/he001/rank", JSONObject().put("rankType", 3)), "rankList", raw)
                    collectChannel(pageNo = 1, out = raw)
                }
                "vip" -> {
                    collectRecommendBooks(pageNo = 1, out = raw)
                }
                "dubbing" -> {
                    collectArray(execPost("/search/suggest", JSONObject().put("keyword", "Sulih Suara")), "suggestList", raw)
                    collectArray(execPost("/search/suggest", JSONObject().put("keyword", "Sulih")), "suggestList", raw)
                }
                else -> {
                    collectArray(execPost("/he001/rank", JSONObject().put("rankType", 1)), "rankList", raw)
                    val th = execPost("/he001/theater", JSONObject())
                    val cols = th.optJSONObject("data")?.optJSONArray("columnVoList")
                    if (cols != null) {
                        for (i in 0 until cols.length()) {
                            val col = cols.optJSONObject(i) ?: continue
                            val bl = col.optJSONArray("bookList") ?: continue
                            for (j in 0 until bl.length()) bl.optJSONObject(j)?.let { raw.add(it) }
                        }
                    }
                    collectChannel(pageNo = 1, out = raw)
                }
            }
        } else {
            when (tabId) {
                "vip", "dubbing" -> collectRecommendBooks(pageNo = page, out = raw)
                else -> collectChannel(pageNo = page, out = raw)
            }
        }

        val items = raw.mapNotNull(::mapMediaItem).distinctBy { it.mediaId }
        if (items.isEmpty() && lastServerError != null) {
            throw IllegalStateException(lastServerError)
        }
        FeedPage(
            items = items,
            nextCursor = (page + 1).toString(),
            hasMore = raw.isNotEmpty()
        )
    }

    override suspend fun getForYouTabs(): List<TabSpec> = listOf(
        TabSpec("foryou", "Untukmu", isDefault = true),
        TabSpec("latest", "Terbaru")
    )

    override suspend fun getForYouFeed(tabId: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        val page = cursor.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val raw = mutableListOf<JSONObject>()
        if (tabId == "latest") {
            if (page == 1) collectArray(execPost("/he001/rank", JSONObject().put("rankType", 3)), "rankList", raw)
            collectChannel(pageNo = page, out = raw)
        } else {
            if (page == 1) collectRecommendBooks(pageNo = 1, out = raw)
            else collectChannel(pageNo = page, out = raw)
        }
        val items = raw.mapNotNull(::mapMediaItem).distinctBy { it.mediaId }
        if (items.isEmpty() && lastServerError != null) {
            throw IllegalStateException(lastServerError)
        }
        FeedPage(
            items = items,
            nextCursor = (page + 1).toString(),
            hasMore = raw.isNotEmpty()
        )
    }

    override suspend fun getFilterLadders(): List<FilterLadder> = listOf(
        FilterLadder(
            id = "kategori",
            title = "Kategori & Peringkat",
            options = listOf(
                FilterOption("trending", "Sedang Tren", 1),
                FilterOption("popular", "Populer", 2),
                FilterOption("latest", "Terbaru", 2),
                FilterOption("vip", "VIP Rekomendasi", 2),
                FilterOption("dubbing", "Sulih Suara", 2)
            )
        )
    )

    override suspend fun getFilteredFeed(filters: List<FilterSelection>, cursor: String): FeedPage {
        val selected = filters.firstOrNull()?.optionId?.takeIf { it.isNotBlank() } ?: "trending"
        return getFeed(tabId = selected, cursor = cursor)
    }

    override suspend fun search(query: String, cursor: String): FeedPage = withContext(Dispatchers.IO) {
        val raw = mutableListOf<JSONObject>()
        collectArray(execPost("/search/suggest", JSONObject().put("keyword", query)), "suggestList", raw)
        val items = raw.mapNotNull(::mapMediaItem).distinctBy { it.mediaId }
        if (items.isEmpty() && lastServerError != null) {
            throw IllegalStateException(lastServerError)
        }
        FeedPage(items = items, nextCursor = "", hasMore = false)
    }

    override suspend fun getEpisodes(mediaId: String, cursor: String): EpisodePage = withContext(Dispatchers.IO) {
        val json = execPost("/chapterv2/list", JSONObject().put("bookId", mediaId))
        val chList = json.optJSONObject("data")?.optJSONArray("chapterList") ?: JSONArray()
        val episodes = buildList {
            for (i in 0 until chList.length()) {
                val ch = chList.optJSONObject(i) ?: continue
                val idx = ch.optInt("chapterIndex", i)
                val chId = ch.optString("chapterId").ifBlank { (idx + 1).toString() }
                val name = ch.optString("chapterName").ifBlank { "Episode ${idx + 1}" }
                val locked = ch.optInt("isCharge", 0) == 1
                chapterIndexMap["$mediaId:$chId"] = idx
                add(
                    EpisodeItem(
                        episodeId = chId,
                        number = idx + 1,
                        title = name,
                        isLocked = locked
                    )
                )
            }
        }
        EpisodePage(episodes = episodes, nextCursor = "", hasMore = false)
    }

    override suspend fun resolvePlayback(mediaId: String, episodeId: String): PlaybackSource =
        withContext(Dispatchers.IO) {
            batchCache["$mediaId:$episodeId"]?.let { return@withContext it }
            val chIdx = chapterIndexMap["$mediaId:$episodeId"] ?: 0

            val batch = execPost(
                "/chapterv2/batch/load",
                JSONObject().apply {
                    put("bookId", mediaId)
                    put("index", chIdx)
                }
            )
            val bList = batch.optJSONObject("data")?.optJSONArray("chapterList")
            var matched: JSONObject? = null
            if (bList != null) {
                for (i in 0 until bList.length()) {
                    val ch = bList.optJSONObject(i) ?: continue
                    val cid = ch.optString("chapterId")
                    if (cid == episodeId || ch.optInt("chapterIndex", -1) == chIdx) {
                        matched = ch
                    }
                    val src = extractPlaybackFromChapter(mediaId, cid, ch)
                    if (src != null) batchCache["$mediaId:$cid"] = src
                }
            }

            if (matched != null) {
                extractPlaybackFromChapter(mediaId, episodeId, matched)?.let { return@withContext it }
            }

            val single = execPost(
                "/cr001/load",
                JSONObject().apply {
                    put("bookId", mediaId)
                    put("chapterId", episodeId)
                }
            )
            val chInfo = single.optJSONObject("data")?.optJSONObject("chapterInfo")
            if (chInfo != null) {
                extractPlaybackFromChapter(mediaId, episodeId, chInfo)?.let { return@withContext it }
            }

            PlaybackSource(streamUrl = "")
        }

    private fun extractPlaybackFromChapter(
        mediaId: String,
        episodeId: String,
        ch: JSONObject
    ): PlaybackSource? {
        var rawUrl = ""
        val cdns = ch.optJSONArray("cdnList")
        if (cdns != null && cdns.length() > 0) {
            for (i in 0 until cdns.length()) {
                val cdn = cdns.optJSONObject(i) ?: continue
                val vList = cdn.optJSONArray("videoPathList")
                if (vList != null && vList.length() > 0) {
                    var chosen = vList.optJSONObject(0)
                    for (j in 0 until vList.length()) {
                        val v = vList.optJSONObject(j) ?: continue
                        if (v.optInt("quality", 0) == 720) {
                            chosen = v
                            break
                        }
                    }
                    rawUrl = chosen?.optString("videoPath").orEmpty()
                }
                if (rawUrl.isBlank()) rawUrl = cdn.optString("videoPath")
                if (rawUrl.isNotBlank()) break
            }
        }
        if (rawUrl.isBlank()) rawUrl = ch.optString("videoPath")
        if (rawUrl.isBlank()) return null

        val playableUrl = if (rawUrl.contains(".encrypt")) {
            AliyunEcbDecryptor.preparePlayableUrl("${mediaId}_$episodeId", rawUrl, client)
        } else {
            rawUrl
        }

        val subs = buildList {
            val sArr = ch.optJSONArray("subTitleList") ?: JSONArray()
            for (i in 0 until sArr.length()) {
                val s = sArr.optJSONObject(i) ?: continue
                val u = s.optString("vttUrl")
                if (u.isNotBlank()) {
                    add(
                        SubtitleTrack(
                            url = u,
                            langCode = s.optString("language", "id-ID"),
                            label = s.optString("name", "Indonesia"),
                            mimeType = "text/vtt"
                        )
                    )
                }
            }
        }

        val audios = buildList {
            val aArr = ch.optJSONArray("audioTrackList") ?: JSONArray()
            for (i in 0 until aArr.length()) {
                val a = aArr.optJSONObject(i) ?: continue
                val lang = a.optString("language", "id-ID")
                add(
                    AudioTrack(
                        id = lang,
                        langCode = lang,
                        label = a.optString("name", lang),
                        isDefault = a.optInt("isDefault", 0) == 1
                    )
                )
            }
        }

        return PlaybackSource(
            streamUrl = playableUrl,
            headers = mapOf("User-Agent" to "DramaBox/7.0.0 (Android)"),
            subtitles = subs,
            audioTracks = audios
        )
    }

    private fun collectRecommendBooks(pageNo: Int, out: MutableList<JSONObject>) {
        val json = execPost(
            "/he001/recommendBook",
            JSONObject().apply {
                put("pageNo", pageNo)
                put("pageSize", 30)
            }
        )
        val recs = json.optJSONObject("data")
            ?.optJSONObject("recommendList")
            ?.optJSONArray("records") ?: return
        for (i in 0 until recs.length()) recs.optJSONObject(i)?.let { out.add(it) }
    }

    private fun collectChannel(pageNo: Int, out: MutableList<JSONObject>) {
        val json = execPost(
            "/he001/recommendChannel",
            JSONObject().apply {
                put("pageNo", pageNo)
                put("pageSize", 30)
                put("channelId", 0)
            }
        )
        val data = json.optJSONObject("data") ?: return
        val arr = data.optJSONArray("bookList") ?: data.optJSONArray("chapterList") ?: return
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
    }

    private fun collectArray(json: JSONObject, key: String, out: MutableList<JSONObject>) {
        val arr = json.optJSONObject("data")?.optJSONArray(key) ?: return
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
    }

    private fun mapMediaItem(obj: JSONObject): MediaItem? {
        val bookId = obj.optString("bookId")
        val name = obj.optString("bookName")
        if (bookId.isBlank() || name.isBlank()) return null
        val cover = obj.optString("cover").ifBlank { obj.optString("coverWap") }
        val tagsArr = obj.optJSONArray("tags")
        val tags = buildList {
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val t = tagsArr.optString(i)
                    if (t.isNotBlank()) add(t)
                }
            }
        }
        return MediaItem(
            providerId = id,
            mediaId = bookId,
            title = name,
            coverUrl = cover,
            tags = tags,
            totalEpisodes = obj.optInt("chapterCount", 0),
            description = obj.optString("introduction")
        )
    }

    private fun execPost(path: String, payload: JSONObject): JSONObject {
        val ts = System.currentTimeMillis().toString()
        val body = payload.toString()
        val sb = "timestamp=$ts$body$deviceId$androidId$token"
        val sn = DramaBoxSigner.signSn(sb)
        val st = DramaBoxSigner.signSt(sb)
        val mediaType = "application/json;charset=UTF-8".toMediaType()
        val base = hosts[activeHostIdx % hosts.size]
        val req = Request.Builder()
            .url("$base$path?timestamp=$ts")
            .post(body.toRequestBody(mediaType))
            .header("User-Agent", "DramaBox/7.0.0 (Android)")
            .header("package-name", "com.storymatrix.drama")
            .header("version", "700")
            .header("p", "73")
            .header("cid", "DRA1000042")
            .header("device-id", deviceId)
            .header("android-id", androidId)
            .header("language", "in")
            .header("current-language", "in")
            .header("time-zone", "Asia/Jakarta")
            .header("country-code", "ID")
            .header("tz", "25200")
            .header("active-time", "12345")
            .header("sn", sn)
            .header("st", st)
            .header("tn", token)
            .header("Authorization", token)
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                resp.header("tn")?.takeIf { it.isNotBlank() }?.let { token = it }
                val txt = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(txt) }.getOrElse { JSONObject() }
                val stCode = json.optInt("status", -1)
                if (resp.code != 200 || stCode != 0) {
                    val err = "DramaBox HTTP=${resp.code} status=$stCode ${json.optString("message")}"
                    lastServerError = err
                    Log.d("DramaBoxProvider", "POST $path $err")
                } else {
                    lastServerError = null
                }
                json
            }
        }.onFailure { e ->
            Log.e("DramaBoxProvider", "POST $path error=${e.message}")
            activeHostIdx = (activeHostIdx + 1) % hosts.size
        }.getOrDefault(JSONObject())
    }
}
