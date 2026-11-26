package com.liveStream.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.liveStream.app.ui.main.MainScreen
import com.liveStream.app.ui.main.MainViewModel
import com.liveStream.app.ui.player.PlayerActivity
import com.liveStream.app.ui.theme.LiveStreamTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiveStreamTheme {
                MainScreen(
                    viewModel = viewModel,
                    onOpenPlayer = { item, startPositionMs ->
                        startActivity(
                            Intent(this, PlayerActivity::class.java).apply {
                                putExtra("EXTRA_PROVIDER_ID", item.providerId)
                                putExtra("EXTRA_MEDIA_ID", item.mediaId)
                                putExtra("EXTRA_TITLE", item.title)
                                putExtra("EXTRA_START_POSITION_MS", startPositionMs)
                            }
                        )
                    }
                )
            }
        }
    }
}
