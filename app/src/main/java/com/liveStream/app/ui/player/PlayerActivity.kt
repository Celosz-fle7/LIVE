package com.liveStream.app.ui.player

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.liveStream.app.provider.ProviderRegistry
import com.liveStream.app.ui.theme.AccentRed
import com.liveStream.app.ui.theme.BgDark
import com.liveStream.app.ui.theme.LiveStreamTheme
import com.liveStream.app.ui.theme.SurfaceCard
import com.liveStream.app.ui.theme.TextPrimary
import com.liveStream.app.ui.theme.TextSecondary
import com.liveStream.contract.EpisodeItem
import com.liveStream.contract.PlaybackSource

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providerId = intent.getStringExtra("EXTRA_PROVIDER_ID").orEmpty()
        val mediaId = intent.getStringExtra("EXTRA_MEDIA_ID").orEmpty()
        val title = intent.getStringExtra("EXTRA_TITLE").orEmpty()

        setContent {
            LiveStreamTheme {
                PlayerScreen(
                    providerId = providerId,
                    mediaId = mediaId,
                    title = title,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(
    providerId: String,
    mediaId: String,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val provider = remember(providerId) { ProviderRegistry.get(providerId) }
    var episodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var activeEpIndex by remember { mutableIntStateOf(0) }
    var playback by remember { mutableStateOf<PlaybackSource?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(providerId, mediaId) {
        isLoading = true
        episodes = runCatching { provider.getEpisodes(mediaId).episodes }.getOrDefault(emptyList())
        isLoading = false
    }

    LaunchedEffect(episodes, activeEpIndex) {
        val ep = episodes.getOrNull(activeEpIndex) ?: return@LaunchedEffect
        if (ep.isLocked) {
            playback = PlaybackSource("")
            exoPlayer.stop()
            return@LaunchedEffect
        }
        val src = runCatching { provider.resolvePlayback(mediaId, ep.episodeId) }.getOrNull()
        playback = src
        if (src != null && src.streamUrl.isNotBlank()) {
            val dsFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(src.headers)
            val subs = src.subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(sub.mimeType)
                    .setLanguage(sub.langCode)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }
            val item = MediaItem.Builder()
                .setUri(src.streamUrl)
                .setSubtitleConfigurations(subs)
                .build()
            exoPlayer.setMediaSource(DefaultMediaSourceFactory(dsFactory).createMediaSource(item))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.stop()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val url = playback?.streamUrl.orEmpty()
            if (url.isNotBlank()) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
                    modifier = Modifier.fillMaxSize()
                )
            } else if (isLoading) {
                CircularProgressIndicator(color = AccentRed)
            } else {
                Text(
                    text = if (episodes.getOrNull(activeEpIndex)?.isLocked == true) {
                        "Episode terkunci di provider"
                    } else {
                        "Stream kosong (Demo Shell / belum ada API)"
                    },
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali", tint = Color.White)
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Provider: ${provider.displayName}", color = TextSecondary, fontSize = 12.sp)
                Text("• Ep ${episodes.getOrNull(activeEpIndex)?.number ?: 1}", color = AccentRed, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 62.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(episodes.indices.toList()) { idx ->
                    val ep = episodes[idx]
                    val selected = idx == activeEpIndex
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) AccentRed else SurfaceCard)
                            .clickable { activeEpIndex = idx }
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
