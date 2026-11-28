package com.liveStream.app.ui.tv

import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.liveStream.app.core.InMemoryMediaStore
import com.liveStream.app.core.StreamCodecReport
import com.liveStream.app.core.VideoCodecSupport
import com.liveStream.app.core.WatchHistoryEntry
import com.liveStream.app.core.WatchHistoryStore
import com.liveStream.app.provider.ProviderRegistry
import com.liveStream.app.ui.theme.AccentGold
import com.liveStream.app.ui.theme.AccentRed
import com.liveStream.app.ui.theme.BgDark
import com.liveStream.app.ui.theme.SurfaceCard
import com.liveStream.app.ui.theme.SurfaceElevated
import com.liveStream.app.ui.theme.TextPrimary
import com.liveStream.app.ui.theme.TextSecondary
import com.liveStream.contract.EpisodeItem
import com.liveStream.contract.MediaItem
import com.liveStream.contract.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class TvPlayerHudState {
    HIDDEN,
    HUD_TIMELINE,
    HUD_ACTIONS,
    SHELF_EXPANDED
}

private enum class TvPlayerModal {
    NONE,
    EPISODES,
    SUBTITLE,
    AUDIO,
    QUALITY
}

private data class TvActionButtonSpec(
    val id: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Player khusus Android TV dengan logika D-pad & navbar persis YouTube TV:
 * - HIDDEN: OK -> Pause/Play, Kiri/Kanan -> Seek -10/+10s, Bawah -> Munculkan Navbar, Back -> Keluar
 * - HUD_TIMELINE: Atas -> Sembunyikan Navbar (HIDDEN), Bawah -> Baris Tombol Aksi, Kiri/Kanan -> Scrub, OK -> Pause/Play
 * - HUD_ACTIONS: Atas -> Timeline, Bawah -> Rak 3 Kartu Penuh (SHELF_EXPANDED), termasuk tombol List Episode
 * - SHELF_EXPANDED: 3 kartu penuh + 3 kartu baris bawah mengintip, Atas dari baris 0 -> kembali ke HUD_ACTIONS
 * - Auto-hide otomatis setelah 4.5 detik tanpa interaksi saat video sedang Play
 * - Probing codec via Rust (av01 / vp9 / hevc / avc1) + Media3 decoder fallback (HW/SW)
 */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(
    providerId: String,
    mediaId: String,
    title: String,
    coverUrl: String,
    totalEpisodes: Int,
    initialEpisodeIndex: Int,
    initialStartPosMs: Long,
    onSwitchDrama: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val provider = remember(providerId) { ProviderRegistry.get(providerId) }

    var episodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var relatedItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isLoadingList by remember { mutableStateOf(true) }
    var currentEpIndex by remember { mutableIntStateOf(initialEpisodeIndex.coerceAtLeast(0)) }
    var playback by remember { mutableStateOf<PlaybackSource?>(null) }
    var isPaused by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var codecReport by remember { mutableStateOf<StreamCodecReport?>(null) }

    var selectedSubLang by remember { mutableStateOf("id") }
    var selectedAudioLang by remember { mutableStateOf("id") }
    var selectedQuality by remember { mutableStateOf("Auto") }
    var isFullZoom by remember { mutableStateOf(false) }

    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var pendingStartPosMs by remember { mutableLongStateOf(initialStartPosMs.coerceAtLeast(0L)) }

    // State machine HUD YouTube TV
    var hudState by remember { mutableStateOf(TvPlayerHudState.HIDDEN) }
    var activeModal by remember { mutableStateOf(TvPlayerModal.NONE) }
    var selectedActionIdx by remember { mutableIntStateOf(0) }
    var shelfFocusedRow by remember { mutableIntStateOf(0) }
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var quickStatusBadge by remember { mutableStateOf<String?>(null) }

    val rootFocus = remember { FocusRequester() }
    val shelfFirstCardFocus = remember { FocusRequester() }
    val shelfListState = rememberLazyListState()

    val actionButtons = remember {
        listOf(
            TvActionButtonSpec("episodes", "Episode", Icons.Rounded.FormatListNumbered),
            TvActionButtonSpec("next", "Berikutnya", Icons.Rounded.SkipNext),
            TvActionButtonSpec("subtitle", "Subtitle", Icons.Rounded.Subtitles),
            TvActionButtonSpec("audio", "Audio", Icons.Rounded.GraphicEq),
            TvActionButtonSpec("quality", "Kualitas", Icons.Rounded.HighQuality),
            TvActionButtonSpec("ratio", "Layar", Icons.Rounded.AspectRatio),
            TvActionButtonSpec("reload", "Reload", Icons.Rounded.Refresh)
        )
    }

    fun markInteraction() {
        lastInteractionMs = System.currentTimeMillis()
    }

    fun showQuickStatus(text: String) {
        quickStatusBadge = text
        markInteraction()
    }

    // ExoPlayer dengan DefaultRenderersFactory fallback untuk av01 / vp9 / hevc / avc1
    val exoPlayer = remember {
        ExoPlayer.Builder(context, VideoCodecSupport.createTvRenderersFactory(context))
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage("id")
                    .setPreferredTextLanguage("id")
                    .build()
            }
    }

    fun applyTrackPreferences(subLang: String, audioLang: String) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        if (subLang == "off") {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setPreferredTextLanguages(subLang, "id-ID", "ind", "id")
            val textGroup = exoPlayer.currentTracks.groups.firstOrNull { g ->
                g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { idx ->
                    val lang = g.getTrackFormat(idx).language.orEmpty().lowercase()
                    lang == subLang.lowercase() || lang.startsWith(subLang.lowercase().take(2))
                }
            }
            if (textGroup != null) {
                val trackIdx = (0 until textGroup.length).firstOrNull { idx ->
                    val lang = textGroup.getTrackFormat(idx).language.orEmpty().lowercase()
                    lang == subLang.lowercase() || lang.startsWith(subLang.lowercase().take(2))
                } ?: 0
                builder.setOverrideForType(TrackSelectionOverride(textGroup.mediaTrackGroup, trackIdx))
            }
        }
        builder.setPreferredAudioLanguages(audioLang, "id-ID", "ind", "id")
        val audioGroup = exoPlayer.currentTracks.groups.firstOrNull { g ->
            g.type == C.TRACK_TYPE_AUDIO && (0 until g.length).any { idx ->
                val lang = g.getTrackFormat(idx).language.orEmpty().lowercase()
                lang == audioLang.lowercase() || lang.startsWith(audioLang.lowercase().take(2))
            }
        }
        if (audioGroup != null) {
            val trackIdx = (0 until audioGroup.length).firstOrNull { idx ->
                val lang = audioGroup.getTrackFormat(idx).language.orEmpty().lowercase()
                lang == audioLang.lowercase() || lang.startsWith(audioLang.lowercase().take(2))
            } ?: 0
            builder.setOverrideForType(TrackSelectionOverride(audioGroup.mediaTrackGroup, trackIdx))
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    fun applyQualityPreference(qualityLabel: String) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        when (qualityLabel) {
            "1080p" -> builder.setMaxVideoSize(1920, 1920)
            "720p" -> builder.setMaxVideoSize(1280, 1280)
            "540p" -> builder.setMaxVideoSize(960, 960)
            "480p" -> builder.setMaxVideoSize(854, 854)
            "360p" -> builder.setMaxVideoSize(640, 640)
            else -> builder.clearVideoSizeConstraints()
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    fun saveProgressNow() {
        val ep = episodes.getOrNull(currentEpIndex) ?: return
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        WatchHistoryStore.save(
            context,
            WatchHistoryEntry(
                providerId = providerId,
                mediaId = mediaId,
                title = title,
                coverUrl = coverUrl,
                totalEpisodes = if (totalEpisodes > 0) totalEpisodes else episodes.size,
                episodeIndex = currentEpIndex,
                episodeNumber = ep.number,
                positionMs = pos,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun togglePausePlay() {
        isPaused = !isPaused
        exoPlayer.playWhenReady = !isPaused
        showQuickStatus(if (isPaused) "Dijeda" else "Diputar")
    }

    fun seekBy(deltaMs: Long) {
        val dur = exoPlayer.duration.takeIf { it > 0L } ?: durationMs
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
        exoPlayer.seekTo(target)
        currentPosMs = target
        val sec = (deltaMs / 1000L)
        showQuickStatus(if (sec >= 0) "+${sec} dtk" else "${sec} dtk")
    }

    // Muat daftar episode + rekomendasi terkait di background
    LaunchedEffect(providerId, mediaId) {
        isLoadingList = true
        episodes = runCatching { provider.getEpisodes(mediaId).episodes }.getOrDefault(emptyList())
        currentEpIndex = initialEpisodeIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
        isLoadingList = false

        // Muat rekomendasi 3 kartu untuk rak bawah player di background (tidak ganggu startup video)
        launch(Dispatchers.IO) {
            delay(600L)
            val tabs = runCatching { provider.getTabs() }.getOrDefault(emptyList())
            val defTab = tabs.firstOrNull { it.isDefault }?.id ?: tabs.firstOrNull()?.id.orEmpty()
            val feed = runCatching { provider.getFeed(defTab, "") }.getOrNull()?.items.orEmpty()
            relatedItems = feed.filterNot { it.mediaId == mediaId }.take(12)
        }
    }

    // Fokus root agar D-pad selalu tertangkap saat HUD hidden / timeline / actions, dan fokus kartu pertama saat shelf terbuka
    LaunchedEffect(hudState, activeModal) {
        if (activeModal == TvPlayerModal.NONE) {
            delay(50L)
            if (hudState == TvPlayerHudState.SHELF_EXPANDED) {
                runCatching { shelfFirstCardFocus.requestFocus() }
            } else {
                runCatching { rootFocus.requestFocus() }
            }
        }
    }

    // Auto-hide HUD setelah 4.5 detik tanpa interaksi saat sedang Play
    LaunchedEffect(hudState, isPaused, lastInteractionMs, activeModal) {
        if ((hudState == TvPlayerHudState.HUD_TIMELINE || hudState == TvPlayerHudState.HUD_ACTIONS) &&
            !isPaused &&
            activeModal == TvPlayerModal.NONE
        ) {
            delay(4500L)
            if (System.currentTimeMillis() - lastInteractionMs >= 4400L && !isPaused) {
                hudState = TvPlayerHudState.HIDDEN
            }
        }
    }

    // Auto-clear indikator cepat (Pause/Play/+10s/-10s)
    LaunchedEffect(quickStatusBadge) {
        if (quickStatusBadge != null) {
            delay(1600L)
            quickStatusBadge = null
        }
    }

    // Update posisi progress bar & simpan riwayat berkala
    LaunchedEffect(currentEpIndex, episodes.size) {
        var tick = 0
        while (true) {
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.takeIf { it > 0L } ?: 0L
            tick++
            if (tick % 10 == 0 && episodes.isNotEmpty()) {
                saveProgressNow()
            }
            delay(300L)
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    saveProgressNow()
                    exoPlayer.playWhenReady = false
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!isPaused) exoPlayer.playWhenReady = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            saveProgressNow()
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    DisposableEffect(exoPlayer, episodes) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    val next = currentEpIndex + 1
                    if (next < episodes.size && !episodes[next].isLocked) {
                        currentEpIndex = next
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val activeVideoFmt = tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
                val activeLabel = VideoCodecSupport.labelFromExoFormat(activeVideoFmt)
                if (activeLabel.isNotBlank()) {
                    val mime = VideoCodecSupport.mimeForCodecKey(activeLabel)
                    val cap = mime?.let { VideoCodecSupport.queryDecoderCapability(it) }
                    val cur = codecReport
                    codecReport = if (cur != null) {
                        cur.copy(activeTrackCodec = activeLabel, capability = cap ?: cur.capability)
                    } else {
                        StreamCodecReport(
                            probe = com.liveStream.app.core.CodecProbeResult(videoCodec = activeLabel.lowercase()),
                            capability = cap,
                            activeTrackCodec = activeLabel
                        )
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                val codecNote = codecReport?.badgeText?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
                streamError = "Gagal decode/putar video$codecNote: ${error.errorCodeName}"
                hudState = TvPlayerHudState.HUD_ACTIONS
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Resolve stream episode aktif + probe codec pakai Rust
    LaunchedEffect(currentEpIndex, episodes, reloadTrigger) {
        val ep = episodes.getOrNull(currentEpIndex) ?: return@LaunchedEffect
        isBuffering = true
        isPaused = false
        streamError = null
        codecReport = null
        exoPlayer.stop()

        if (ep.isLocked) {
            isBuffering = false
            playback = PlaybackSource("")
            streamError = "Episode ${ep.number} terkunci di provider"
            hudState = TvPlayerHudState.HUD_ACTIONS
            return@LaunchedEffect
        }

        val res = runCatching { provider.resolvePlayback(mediaId, ep.episodeId) }
        val src = res.getOrNull()
        playback = src
        if (src == null || src.streamUrl.isBlank()) {
            isBuffering = false
            val err = res.exceptionOrNull()
            streamError = if (err != null) {
                "Error (${provider.displayName}): ${err.message ?: err.javaClass.simpleName}"
            } else {
                "Stream tidak tersedia (${provider.displayName})"
            }
            hudState = TvPlayerHudState.HUD_ACTIONS
            return@LaunchedEffect
        }

        // Probing codec stream di background pakai Rust (av01, vp9, hevc, avc1)
        launch(Dispatchers.IO) {
            val probed = VideoCodecSupport.probeRemoteStream(src.streamUrl, src.headers)
            codecReport = probed
        }

        val defaultSub = src.subtitles.firstOrNull {
            val l = it.langCode.lowercase()
            l == "id" || l == "id-id" || l == "ind" || it.label.contains("indonesia", ignoreCase = true)
        }?.langCode ?: src.subtitles.firstOrNull()?.langCode ?: "off"
        if (selectedSubLang != "off") selectedSubLang = defaultSub

        val defaultAudio = src.audioTracks.firstOrNull {
            val l = it.langCode.lowercase()
            l == "id" || l == "id-id" || l == "ind"
        }?.langCode ?: src.audioTracks.firstOrNull()?.langCode ?: "id"
        selectedAudioLang = defaultAudio

        val dsFactory = InMemoryMediaStore.createDataSourceFactory(src.headers)
        val subs = src.subtitles.map { sub ->
            val isDef = selectedSubLang != "off" && sub.langCode.equals(selectedSubLang, ignoreCase = true)
            ExoMediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(sub.mimeType)
                .setLanguage(sub.langCode)
                .setLabel(sub.label)
                .setSelectionFlags(if (isDef) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val exoItem = ExoMediaItem.Builder()
            .setUri(src.streamUrl)
            .setSubtitleConfigurations(subs)
            .build()

        exoPlayer.setMediaSource(DefaultMediaSourceFactory(dsFactory).createMediaSource(exoItem))
        exoPlayer.prepare()
        if (currentEpIndex == initialEpisodeIndex && pendingStartPosMs > 0L) {
            exoPlayer.seekTo(pendingStartPosMs)
            pendingStartPosMs = 0L
        }
        applyTrackPreferences(selectedSubLang, selectedAudioLang)
        applyQualityPreference(selectedQuality)
        exoPlayer.playWhenReady = true
    }

    // Tombol Back YouTube TV: Modal -> HUD -> Hidden -> Exit
    BackHandler(enabled = true) {
        when {
            activeModal != TvPlayerModal.NONE -> {
                activeModal = TvPlayerModal.NONE
                markInteraction()
            }
            hudState == TvPlayerHudState.SHELF_EXPANDED -> {
                hudState = TvPlayerHudState.HUD_TIMELINE
                markInteraction()
            }
            hudState == TvPlayerHudState.HUD_ACTIONS || hudState == TvPlayerHudState.HUD_TIMELINE -> {
                hudState = TvPlayerHudState.HIDDEN
            }
            else -> {
                saveProgressNow()
                onBack()
            }
        }
    }

    fun executeActionButton(actionId: String) {
        markInteraction()
        when (actionId) {
            "episodes" -> activeModal = TvPlayerModal.EPISODES
            "next" -> {
                val next = currentEpIndex + 1
                if (next < episodes.size) {
                    currentEpIndex = next
                    showQuickStatus("Episode ${episodes[next].number}")
                } else {
                    showQuickStatus("Episode terakhir")
                }
            }
            "subtitle" -> activeModal = TvPlayerModal.SUBTITLE
            "audio" -> activeModal = TvPlayerModal.AUDIO
            "quality" -> activeModal = TvPlayerModal.QUALITY
            "ratio" -> {
                isFullZoom = !isFullZoom
                showQuickStatus(if (isFullZoom) "Layar: Penuh" else "Layar: Asli (Fit)")
            }
            "reload" -> {
                reloadTrigger += 1
                showQuickStatus("Memuat ulang stream...")
            }
        }
    }

    val currentEp = episodes.getOrNull(currentEpIndex)
    val relatedRows = remember(relatedItems) { relatedItems.chunked(3) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (activeModal != TvPlayerModal.NONE || hudState == TvPlayerHudState.SHELF_EXPANDED) {
                    return@onPreviewKeyEvent false
                }
                val nativeEvent = keyEvent.nativeKeyEvent
                if (nativeEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                when (nativeEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        when (hudState) {
                            TvPlayerHudState.HIDDEN -> {
                                togglePausePlay()
                                hudState = TvPlayerHudState.HUD_TIMELINE
                            }
                            TvPlayerHudState.HUD_TIMELINE -> {
                                togglePausePlay()
                            }
                            TvPlayerHudState.HUD_ACTIONS -> {
                                executeActionButton(actionButtons[selectedActionIdx].id)
                            }
                            TvPlayerHudState.SHELF_EXPANDED -> Unit
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        when (hudState) {
                            TvPlayerHudState.HIDDEN -> {
                                seekBy(-10_000L)
                            }
                            TvPlayerHudState.HUD_TIMELINE -> {
                                seekBy(-10_000L)
                            }
                            TvPlayerHudState.HUD_ACTIONS -> {
                                selectedActionIdx = (selectedActionIdx - 1).coerceAtLeast(0)
                                markInteraction()
                            }
                            TvPlayerHudState.SHELF_EXPANDED -> Unit
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        when (hudState) {
                            TvPlayerHudState.HIDDEN -> {
                                seekBy(10_000L)
                            }
                            TvPlayerHudState.HUD_TIMELINE -> {
                                seekBy(10_000L)
                            }
                            TvPlayerHudState.HUD_ACTIONS -> {
                                selectedActionIdx = (selectedActionIdx + 1).coerceAtMost(actionButtons.lastIndex)
                                markInteraction()
                            }
                            TvPlayerHudState.SHELF_EXPANDED -> Unit
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        markInteraction()
                        when (hudState) {
                            TvPlayerHudState.HIDDEN -> {
                                hudState = TvPlayerHudState.HUD_TIMELINE
                            }
                            TvPlayerHudState.HUD_TIMELINE -> {
                                hudState = TvPlayerHudState.HUD_ACTIONS
                            }
                            TvPlayerHudState.HUD_ACTIONS -> {
                                if (relatedRows.isNotEmpty()) {
                                    shelfFocusedRow = 0
                                    hudState = TvPlayerHudState.SHELF_EXPANDED
                                } else {
                                    activeModal = TvPlayerModal.EPISODES
                                }
                            }
                            TvPlayerHudState.SHELF_EXPANDED -> Unit
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        markInteraction()
                        when (hudState) {
                            TvPlayerHudState.HIDDEN -> false
                            TvPlayerHudState.HUD_TIMELINE -> {
                                // Persis YouTube TV: tekan Atas di garis timeline langsung sembunyikan navbar!
                                hudState = TvPlayerHudState.HIDDEN
                                true
                            }
                            TvPlayerHudState.HUD_ACTIONS -> {
                                hudState = TvPlayerHudState.HUD_TIMELINE
                                true
                            }
                            TvPlayerHudState.SHELF_EXPANDED -> false
                        }
                    }
                    else -> false
                }
            }
    ) {
        // === VIDEO SURFACE ===
        if (!playback?.streamUrl.isNullOrBlank()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                        resizeMode = if (isFullZoom) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        player = exoPlayer
                    }
                },
                update = { pv ->
                    pv.player = exoPlayer
                    pv.resizeMode = if (isFullZoom) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Loading / Buffering / Error
        if (isLoadingList || (isBuffering && streamError == null)) {
            CircularProgressIndicator(
                color = AccentRed,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (streamError != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xDD12141C))
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                Text(streamError!!, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text("Tekan Bawah lalu pilih Reload atau Episode lain", color = TextSecondary, fontSize = 12.sp)
            }
        }

        // Indikator cepat saat HUD tersembunyi (Pause / Play / Seek -10/+10s)
        AnimatedVisibility(
            visible = quickStatusBadge != null && hudState == TvPlayerHudState.HIDDEN,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = quickStatusBadge.orEmpty(),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xCC0A0B0F))
                        .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }

        // === HUD NAVBAR PLAYER YOUTUBE TV (TIMELINE + ACTIONS + 3 KARTU NGINTIP / EXPANDED) ===
        AnimatedVisibility(
            visible = hudState != TvPlayerHudState.HIDDEN,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color(0xAA05060A),
                            0.42f to Color(0x4405060A),
                            0.72f to Color(0xDD08090F),
                            1.0f to Color(0xFA08090F)
                        )
                    )
            ) {
                // Header Info Atas
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp, vertical = 24.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        val epLabel = if (currentEp != null) "Episode ${currentEp.number} / ${episodes.size}" else ""
                        Text(
                            text = "${provider.displayName}  ·  $epLabel",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    codecReport?.badgeText?.takeIf { it.isNotBlank() }?.let { badge ->
                        Text(
                            text = badge,
                            color = AccentGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xCC161924))
                                .border(1.dp, Color(0x44FFC857), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // Bagian Bawah: Navbar YouTube TV
                if (hudState == TvPlayerHudState.SHELF_EXPANDED) {
                    // Navbar naik penuh -> 3 kartu penuh + 3 kartu mengintip di bawah
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.76f)
                            .padding(horizontal = 40.dp)
                    ) {
                        Text(
                            text = "Rekomendasi untukmu · Tekan Atas untuk kembali ke kontrol",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        LazyColumn(
                            state = shelfListState,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            contentPadding = PaddingValues(bottom = 44.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(relatedRows, key = { idx, _ -> "player_shelf_row_$idx" }) { rowIdx, row ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    row.forEachIndexed { cardIdx, item ->
                                        TvMediaCard16x9(
                                            item = item,
                                            priority = if (rowIdx <= shelfFocusedRow) TvImagePriority.VISIBLE_ROW else TvImagePriority.PEEK_ROW,
                                            allowImageLoad = true,
                                            isScrollingFast = shelfListState.isScrollInProgress,
                                            reloadEpoch = 0,
                                            onFocused = {
                                                shelfFocusedRow = rowIdx
                                                markInteraction()
                                            },
                                            onClick = {
                                                saveProgressNow()
                                                onSwitchDrama(item)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(if (rowIdx == 0 && cardIdx == 0) Modifier.focusRequester(shelfFirstCardFocus) else Modifier)
                                                .onPreviewKeyEvent { ev ->
                                                    if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                                                        rowIdx == 0
                                                    ) {
                                                        hudState = TvPlayerHudState.HUD_ACTIONS
                                                        markInteraction()
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                        )
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                } else {
                    // HUD_TIMELINE & HUD_ACTIONS: Timeline + Tombol Aksi + 3 Kartu Mengintip di tepi bawah
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp)
                    ) {
                        // Layer 1: Garis Timeline (Scrubber)
                        val timelineFocused = hudState == TvPlayerHudState.HUD_TIMELINE
                        val fraction = if (durationMs > 0L) (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${formatTvTime(currentPosMs)} / ${formatTvTime(durationMs)}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = if (timelineFocused) "▲ Sembunyikan · ◀▶ Geser · ▼ Kontrol" else "▲ Timeline · ▼ Rekomendasi",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        // Garis progress bar tebal saat fokus (YouTube TV style)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (timelineFocused) 10.dp else 5.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0x55FFFFFF))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (timelineFocused) AccentRed else Color.White)
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Layer 2: Baris Tombol Aksi (termasuk tombol List Episode)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            actionButtons.forEachIndexed { idx, btn ->
                                val focused = hudState == TvPlayerHudState.HUD_ACTIONS && selectedActionIdx == idx
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(if (focused) Color.White else Color(0xBB191C26))
                                        .border(
                                            width = if (focused) 2.dp else 1.dp,
                                            color = if (focused) AccentRed else Color(0x28FFFFFF),
                                            shape = RoundedCornerShape(22.dp)
                                        )
                                        .clickable { executeActionButton(btn.id) }
                                        .padding(horizontal = 14.dp, vertical = 9.dp)
                                ) {
                                    Icon(
                                        imageVector = btn.icon,
                                        contentDescription = btn.label,
                                        tint = if (focused) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = btn.label,
                                        color = if (focused) Color.Black else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Layer 3: 3 Kartu Mengintip di tepi bawah HUD (persis YouTube TV)
                        val peekItems = relatedItems.take(3)
                        if (peekItems.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(74.dp)
                            ) {
                                peekItems.forEach { item ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                            .background(Color(0xFF14161E))
                                            .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        TvPriorityImage(
                                            url = item.coverUrl,
                                            contentDescription = item.title,
                                            priority = TvImagePriority.PEEK_ROW,
                                            allowLoad = true,
                                            isScrollingFast = false,
                                            reloadEpoch = 0,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                            modifier = Modifier.fillMaxHeight().padding(top = 4.dp)
                                        )
                                        Text(
                                            text = item.title,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .fillMaxWidth()
                                                .background(Color(0xAA08090F))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(22.dp))
                        }
                    }
                }
            }
        }

        // === MODAL LIST EPISODE (TV GRID D-PAD) ===
        if (activeModal == TvPlayerModal.EPISODES) {
            TvEpisodeListModal(
                episodes = episodes,
                currentIndex = currentEpIndex,
                onSelect = { idx ->
                    currentEpIndex = idx
                    activeModal = TvPlayerModal.NONE
                    hudState = TvPlayerHudState.HIDDEN
                },
                onClose = { activeModal = TvPlayerModal.NONE }
            )
        }

        // === MODAL SUBTITLE ===
        if (activeModal == TvPlayerModal.SUBTITLE) {
            val options = buildList {
                add("off" to "Matikan Subtitle")
                playback?.subtitles.orEmpty().forEach { add(it.langCode to "${it.label} (${it.langCode})") }
            }
            TvOptionListModal(
                title = "Pilih Subtitle",
                options = options,
                selectedKey = selectedSubLang,
                onSelect = { code ->
                    selectedSubLang = code
                    applyTrackPreferences(selectedSubLang, selectedAudioLang)
                    activeModal = TvPlayerModal.NONE
                },
                onClose = { activeModal = TvPlayerModal.NONE }
            )
        }

        // === MODAL AUDIO ===
        if (activeModal == TvPlayerModal.AUDIO) {
            val options = playback?.audioTracks.orEmpty()
                .map { it.langCode to "${it.label} (${it.langCode})" }
                .ifEmpty { listOf("id" to "Default (Indonesia)") }
            TvOptionListModal(
                title = "Pilih Audio",
                options = options,
                selectedKey = selectedAudioLang,
                onSelect = { code ->
                    selectedAudioLang = code
                    applyTrackPreferences(selectedSubLang, selectedAudioLang)
                    activeModal = TvPlayerModal.NONE
                },
                onClose = { activeModal = TvPlayerModal.NONE }
            )
        }

        // === MODAL KUALITAS & INFO CODEC ===
        if (activeModal == TvPlayerModal.QUALITY) {
            val codecHeader = codecReport?.badgeText?.takeIf { it.isNotBlank() }?.let { " · Codec: $it" }.orEmpty()
            val options = listOf("Auto", "1080p", "720p", "540p", "480p", "360p").map { it to it }
            TvOptionListModal(
                title = "Kualitas Video$codecHeader",
                options = options,
                selectedKey = selectedQuality,
                onSelect = { q ->
                    selectedQuality = q
                    applyQualityPreference(q)
                    activeModal = TvPlayerModal.NONE
                },
                onClose = { activeModal = TvPlayerModal.NONE }
            )
        }
    }
}

@Composable
private fun TvEpisodeListModal(
    episodes: List<EpisodeItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit
) {
    val activeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { activeFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD05060A))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.76f)
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceCard)
                .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Daftar Episode (${episodes.size})", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SurfaceElevated)
                        .clickable(onClick = onClose)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Tutup", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(episodes, key = { idx, ep -> "${ep.episodeId}_$idx" }) { idx, ep ->
                    var focused by remember { mutableStateOf(false) }
                    val isCurrent = idx == currentIndex
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    focused -> Color.White
                                    isCurrent -> AccentRed
                                    ep.isLocked -> Color(0xFF1A1C24)
                                    else -> SurfaceElevated
                                }
                            )
                            .border(
                                width = if (focused) 2.dp else 1.dp,
                                color = if (focused) AccentRed else Color(0x22FFFFFF),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .then(if (isCurrent) Modifier.focusRequester(activeFocus) else Modifier)
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .clickable(enabled = !ep.isLocked) { onSelect(idx) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ep.isLocked) {
                                Icon(
                                    Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = if (focused) Color.Black else TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = "${ep.number}",
                                color = if (focused) Color.Black else if (ep.isLocked) TextSecondary else Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvOptionListModal(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onClose: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD05060A))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceCard)
                .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            options.forEachIndexed { idx, (key, label) ->
                var focused by remember { mutableStateOf(false) }
                val active = key.equals(selectedKey, ignoreCase = true)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (focused) Color.White else if (active) Color(0xFF282C3C) else SurfaceElevated)
                        .border(if (focused) 2.dp else 1.dp, if (focused) AccentRed else Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                        .then(if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .clickable { onSelect(key) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = label,
                        color = if (focused) Color.Black else Color.White,
                        fontSize = 14.sp,
                        fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Medium
                    )
                    if (active) {
                        Text("Aktif", color = if (focused) AccentRed else AccentGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatTvTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%02d:%02d".format(min, sec)
}
