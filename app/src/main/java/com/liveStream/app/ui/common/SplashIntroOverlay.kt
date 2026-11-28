package com.liveStream.app.ui.common

import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.liveStream.app.R
import com.liveStream.app.ui.theme.BgDark
import kotlinx.coroutines.delay

/**
 * Animasi splash screen pembuka (video + suara sonic logo) saat masuk aplikasi.
 * Berjalan paralel sementara MainViewModel memuat katalog di background.
 * Bisa dilewati kapan saja dengan tap layar (Mobile) atau OK/Back (Remote TV).
 */
@OptIn(UnstableApi::class)
@Composable
fun SplashIntroOverlay(
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var firstFrameReady by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    fun finishOnce() {
        if (!done) {
            done = true
            onFinished()
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.splash_intro}")
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameReady = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    finishOnce()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                finishOnce()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Safety timeout agar splash tidak pernah nyangkut di perangkat apa pun
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        delay(4000L)
        finishOnce()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .focusRequester(focusRequester)
            .focusable()
            .clickable { finishOnce() }
            .onPreviewKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (ev.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_BACK -> {
                            finishOnce()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_texture_player, null) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Fallback visual instan sebelum frame pertama video siap
        if (!firstFrameReady) {
            Image(
                painter = painterResource(id = R.drawable.tv_banner),
                contentDescription = "LIVE",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
