package com.liveStream.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.liveStream.app.core.TvDeviceHelper
import com.liveStream.app.ui.common.SplashIntroOverlay
import com.liveStream.app.ui.main.MainScreen
import com.liveStream.app.ui.main.MainViewModel
import com.liveStream.app.ui.player.PlayerActivity
import com.liveStream.app.ui.theme.LiveStreamTheme
import com.liveStream.app.ui.tv.TvMainScreen
import com.liveStream.contract.MediaItem

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTv = TvDeviceHelper.isTvDevice(this)
        val openPlayer: (MediaItem, Int, Long) -> Unit = { item, startEpisodeIndex, startPositionMs ->
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    putExtra("EXTRA_PROVIDER_ID", item.providerId)
                    putExtra("EXTRA_MEDIA_ID", item.mediaId)
                    putExtra("EXTRA_TITLE", item.title)
                    putExtra("EXTRA_COVER_URL", item.coverUrl)
                    putExtra("EXTRA_TOTAL_EPISODES", item.totalEpisodes)
                    putExtra("EXTRA_START_EPISODE_INDEX", startEpisodeIndex)
                    putExtra("EXTRA_START_POSITION_MS", startPositionMs)
                }
            )
        }
        setContent {
            LiveStreamTheme {
                var showSplash by rememberSaveable { mutableStateOf(true) }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isTv) {
                        TvMainScreen(
                            viewModel = viewModel,
                            onOpenPlayer = openPlayer
                        )
                    } else {
                        MainScreen(
                            viewModel = viewModel,
                            onOpenPlayer = openPlayer
                        )
                    }
                    AnimatedVisibility(
                        visible = showSplash,
                        exit = fadeOut()
                    ) {
                        SplashIntroOverlay(onFinished = { showSplash = false })
                    }
                }
            }
        }
    }
}
