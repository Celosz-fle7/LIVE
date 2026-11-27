package com.liveStream.app.ui.player

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.liveStream.app.core.InMemoryMediaStore
import com.liveStream.app.core.WatchHistoryEntry
import com.liveStream.app.core.WatchHistoryStore
import com.liveStream.app.provider.ProviderRegistry
import com.liveStream.app.ui.theme.AccentGold
import com.liveStream.app.ui.theme.AccentRed
import com.liveStream.app.ui.theme.BgDark
import com.liveStream.app.ui.theme.LiveStreamTheme
import com.liveStream.app.ui.theme.SurfaceCard
import com.liveStream.app.ui.theme.SurfaceElevated
import com.liveStream.app.ui.theme.TextPrimary
import com.liveStream.app.ui.theme.TextSecondary
import com.liveStream.contract.EpisodeItem
import com.liveStream.contract.PlaybackSource
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val providerId = intent.getStringExtra("EXTRA_PROVIDER_ID").orEmpty()
        val mediaId = intent.getStringExtra("EXTRA_MEDIA_ID").orEmpty()
        val title = intent.getStringExtra("EXTRA_TITLE").orEmpty()
        val coverUrl = intent.getStringExtra("EXTRA_COVER_URL").orEmpty()
        val totalEpisodes = intent.getIntExtra("EXTRA_TOTAL_EPISODES", 0)
        val startEpisodeIndex = intent.getIntExtra("EXTRA_START_EPISODE_INDEX", 0).coerceAtLeast(0)
        val startPositionMs = intent.getLongExtra("EXTRA_START_POSITION_MS", 0L).coerceAtLeast(0L)

        setContent {
            LiveStreamTheme {
                FullScreenVerticalPlayer(
                    providerId = providerId,
                    mediaId = mediaId,
                    title = title,
                    coverUrl = coverUrl,
                    totalEpisodes = totalEpisodes,
                    initialEpisodeIndex = startEpisodeIndex,
                    initialStartPosMs = startPositionMs,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenVerticalPlayer(
    providerId: String,
    mediaId: String,
    title: String,
    coverUrl: String,
    totalEpisodes: Int,
    initialEpisodeIndex: Int,
    initialStartPosMs: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val provider = remember(providerId) { ProviderRegistry.get(providerId) }

    var episodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var isLoadingList by remember { mutableStateOf(true) }
    var playback by remember { mutableStateOf<PlaybackSource?>(null) }
    var activePlayingEpIndex by remember { mutableIntStateOf(-1) }
    var isPaused by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }

    // Default subtitle = Indonesia (atau "off" jika dimatikan)
    var selectedSubLang by remember { mutableStateOf("id") }
    var selectedAudioLang by remember { mutableStateOf("id") }
    var selectedQuality by remember { mutableStateOf("Auto") }
    var isFullZoom by remember { mutableStateOf(true) }

    var showEpisodeSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showQualitySheet by remember { mutableStateOf(false) }

    // Mode Bersih: default kontrol tersembunyi, tap layar untuk memunculkan/menyembunyikan
    var showControls by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var pendingStartPosMs by remember { mutableStateOf(initialStartPosMs) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).apply {
            // Default prioritas bahasa Indonesia untuk Audio & Subtitle
        }.build().apply {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage("id")
                .setPreferredTextLanguage("id")
                .build()
        }
    }

    LaunchedEffect(activePlayingEpIndex) {
        while (activePlayingEpIndex >= 0) {
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.takeIf { it > 0L } ?: 0L
            kotlinx.coroutines.delay(300L)
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(providerId, mediaId) {
        isLoadingList = true
        episodes = runCatching { provider.getEpisodes(mediaId).episodes }.getOrDefault(emptyList())
        isLoadingList = false
    }

    fun applyTrackPreferences(subLang: String, audioLang: String) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        if (subLang == "off") {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setPreferredTextLanguages(subLang, "id-ID", "ind", "id")
            // Terapkan override persis jika track tersedia
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

    if (isLoadingList) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentRed)
        }
        return
    }

    if (episodes.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali", tint = Color.White)
            }
            Text("Episode tidak tersedia", color = TextSecondary)
        }
        return
    }

    val safeInitialEp = initialEpisodeIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeInitialEp, pageCount = { episodes.size })
    val currentEpIndex = pagerState.currentPage
    val currentEp = episodes[currentEpIndex]

    fun saveProgressNow() {
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
                episodeNumber = currentEp.number,
                positionMs = pos,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    DisposableEffect(lifecycleOwner, exoPlayer, currentEpIndex) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    saveProgressNow()
                    exoPlayer.playWhenReady = false
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (activePlayingEpIndex == currentEpIndex && !isPaused) {
                        exoPlayer.playWhenReady = true
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            saveProgressNow()
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    LaunchedEffect(activePlayingEpIndex, currentEpIndex) {
        var ticks = 0
        while (activePlayingEpIndex == currentEpIndex) {
            ticks++
            if (ticks % 7 == 0) saveProgressNow()
            kotlinx.coroutines.delay(300L)
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val next = pagerState.currentPage + 1
                    if (next < episodes.size) {
                        scope.launch { pagerState.animateScrollToPage(next) }
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(currentEpIndex) {
        activePlayingEpIndex = -1
        isPaused = false
        streamError = null
        currentPosMs = 0L
        durationMs = 0L
        exoPlayer.stop()
        if (currentEp.isLocked) {
            playback = PlaybackSource("")
            return@LaunchedEffect
        }
        val res = runCatching { provider.resolvePlayback(mediaId, currentEp.episodeId) }
        val src = res.getOrNull()
        playback = src
        if (src == null || src.streamUrl.isBlank()) {
            val err = res.exceptionOrNull()
            streamError = if (err != null) {
                "Error (${provider.displayName}): ${err.javaClass.simpleName}: ${err.message}"
            } else {
                "Stream tidak tersedia dari server (${provider.displayName})"
            }
            return@LaunchedEffect
        }
        if (src.streamUrl.isNotBlank()) {
            // Pilih subtitle Indonesia default jika tersedia
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
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(sub.mimeType)
                    .setLanguage(sub.langCode)
                    .setLabel(sub.label)
                    .setSelectionFlags(if (isDef) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
            val item = MediaItem.Builder()
                .setUri(src.streamUrl)
                .setSubtitleConfigurations(subs)
                .build()
            exoPlayer.setMediaSource(DefaultMediaSourceFactory(dsFactory).createMediaSource(item))
            exoPlayer.setPlaybackSpeed(playbackSpeed)
            exoPlayer.prepare()
            if (currentEpIndex == safeInitialEp && pendingStartPosMs > 0L) {
                exoPlayer.seekTo(pendingStartPosMs)
                pendingStartPosMs = 0L
            }
            applyTrackPreferences(selectedSubLang, selectedAudioLang)
            exoPlayer.playWhenReady = true
            activePlayingEpIndex = currentEpIndex

            // Pre-fetch episode berikutnya di background agar swipe/auto-next instan
            val nextEp = episodes.getOrNull(currentEpIndex + 1)
            if (nextEp != null && !nextEp.isLocked) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { provider.resolvePlayback(mediaId, nextEp.episodeId) }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { idx ->
            val ep = episodes[idx]
            val isCurrent = idx == currentEpIndex && activePlayingEpIndex == idx
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        showControls = !showControls
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent && !playback?.streamUrl.isNullOrBlank()) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = if (isFullZoom) {
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                } else {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                                player = exoPlayer
                            }
                        },
                        update = {
                            it.player = exoPlayer
                            it.resizeMode = if (isFullZoom) {
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            } else {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (ep.isLocked) {
                    Text("Episode ${ep.number} terkunci di provider", color = TextSecondary, fontSize = 14.sp)
                } else if (idx == currentEpIndex && streamError != null) {
                    Text(streamError!!, color = TextSecondary, fontSize = 14.sp)
                } else {
                    CircularProgressIndicator(color = AccentRed)
                }
            }
        }

        if (showControls) {
            // Top Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .align(Alignment.TopStart)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali", tint = Color.White)
                }
                Column {
                    Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${provider.displayName} • Episode ${currentEp.number}/${episodes.size}", color = TextSecondary, fontSize = 12.sp)
                }
            }

            // Bottom gradient + info + garis detik + next episode
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0A0B0F), BgDark)))
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 86.dp, bottom = 16.dp)
            ) {
                Text(currentEp.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = {
                            isPaused = !isPaused
                            exoPlayer.playWhenReady = !isPaused
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = "Jeda/Putar",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = formatPlayerTime(currentPosMs),
                        color = TextPrimary,
                        fontSize = 11.sp
                    )
                    Slider(
                        value = if (durationMs > 0L) (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac ->
                            if (durationMs > 0L) {
                                val target = (frac * durationMs).toLong()
                                currentPosMs = target
                                exoPlayer.seekTo(target)
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentRed,
                            activeTrackColor = AccentRed,
                            inactiveTrackColor = Color(0x55FFFFFF)
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                    )
                    Text(
                        text = formatPlayerTime(durationMs),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "${playbackSpeed}x",
                        color = AccentGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x88000000))
                            .clickable {
                                val nextSpeed = when (playbackSpeed) {
                                    1.0f -> 1.25f
                                    1.25f -> 1.5f
                                    1.5f -> 2.0f
                                    else -> 1.0f
                                }
                                playbackSpeed = nextSpeed
                                exoPlayer.setPlaybackSpeed(nextSpeed)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    if (currentEpIndex + 1 < episodes.size) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentRed.copy(alpha = 0.85f))
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(currentEpIndex + 1) }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Next Ep", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Right Action Rail (Episode, Kualitas, Subtitle, Audio, Layar)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 14.dp, bottom = 24.dp)
            ) {
                ActionIcon(
                    icon = Icons.Rounded.FormatListNumbered,
                    label = "Ep ${currentEp.number}",
                    tint = Color.White,
                    onClick = { showEpisodeSheet = true }
                )
                ActionIcon(
                    icon = Icons.Rounded.HighQuality,
                    label = selectedQuality,
                    tint = Color.White,
                    onClick = { showQualitySheet = true }
                )
                ActionIcon(
                    icon = Icons.Rounded.Subtitles,
                    label = if (selectedSubLang == "off") "Sub Off" else selectedSubLang.uppercase(),
                    tint = if (selectedSubLang == "off") TextSecondary else AccentGold,
                    onClick = { showSubtitleSheet = true }
                )
                ActionIcon(
                    icon = Icons.Rounded.GraphicEq,
                    label = selectedAudioLang.uppercase(),
                    tint = AccentRed,
                    onClick = { showAudioSheet = true }
                )
                ActionIcon(
                    icon = Icons.Rounded.AspectRatio,
                    label = if (isFullZoom) "Full" else "Fit",
                    tint = Color.White,
                    onClick = { isFullZoom = !isFullZoom }
                )
            }
        }
    }

    if (showQualitySheet) {
        ModalBottomSheet(onDismissRequest = { showQualitySheet = false }, containerColor = SurfaceCard) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Pilih Kualitas Video", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                listOf("Auto", "1080p", "720p", "540p", "480p", "360p").forEach { q ->
                    val selected = selectedQuality == q
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedQuality = q
                            applyQualityPreference(q)
                            showQualitySheet = false
                        }.padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = AccentRed)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (q == "Auto") "Otomatis (Terbaik)" else q, color = TextPrimary, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showEpisodeSheet) {
        ModalBottomSheet(onDismissRequest = { showEpisodeSheet = false }, containerColor = SurfaceCard) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Pilih Episode (${episodes.size})", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 60.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(320.dp)
                ) {
                    items(episodes.indices.toList()) { idx ->
                        val ep = episodes[idx]
                        val selected = idx == currentEpIndex
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AccentRed else SurfaceElevated)
                                .clickable {
                                    showEpisodeSheet = false
                                    scope.launch { pagerState.scrollToPage(idx) }
                                }
                        ) {
                            Text(
                                text = if (ep.isLocked) "${ep.number}🔒" else "${ep.number}",
                                color = if (selected) Color.White else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSubtitleSheet) {
        ModalBottomSheet(onDismissRequest = { showSubtitleSheet = false }, containerColor = SurfaceCard) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Pilih Subtitle", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedSubLang = "off"
                        applyTrackPreferences("off", selectedAudioLang)
                        showSubtitleSheet = false
                    }.padding(vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = selectedSubLang == "off",
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = AccentRed)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Matikan Subtitle (Off)", color = TextPrimary, fontSize = 14.sp)
                }
                val subs = playback?.subtitles.orEmpty()
                if (subs.isEmpty()) {
                    Text("Provider ini memakai hardsub / tidak ada softsub terpisah", color = TextSecondary, fontSize = 12.sp)
                } else {
                    subs.forEach { sub ->
                        val selected = selectedSubLang.equals(sub.langCode, ignoreCase = true)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedSubLang = sub.langCode
                                applyTrackPreferences(sub.langCode, selectedAudioLang)
                                showSubtitleSheet = false
                            }.padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = AccentRed)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("${sub.label} (${sub.langCode})", color = TextPrimary, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showAudioSheet) {
        ModalBottomSheet(onDismissRequest = { showAudioSheet = false }, containerColor = SurfaceCard) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Pilih Track Audio", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val audios = playback?.audioTracks.orEmpty()
                if (audios.isEmpty()) {
                    Text("Hanya 1 track audio bawaan (Indonesia)", color = TextSecondary, fontSize = 13.sp)
                } else {
                    audios.forEach { aud ->
                        val selected = selectedAudioLang.equals(aud.langCode, ignoreCase = true)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedAudioLang = aud.langCode
                                applyTrackPreferences(selectedSubLang, aud.langCode)
                                showAudioSheet = false
                            }.padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = AccentRed)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("${aud.label} (${aud.langCode})", color = TextPrimary, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun formatPlayerTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%02d:%02d".format(min, sec)
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
