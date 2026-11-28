package com.liveStream.app.ui.tv

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.liveStream.app.ui.theme.AccentGold
import com.liveStream.app.ui.theme.AccentRed
import com.liveStream.app.ui.theme.SurfaceCard
import com.liveStream.app.ui.theme.SurfaceElevated
import com.liveStream.app.ui.theme.TextPrimary
import com.liveStream.app.ui.theme.TextSecondary
import com.liveStream.contract.MediaItem
import kotlinx.coroutines.delay
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ImageLoader khusus Android TV dengan batas concurrency rendah (maks 3 request)
 * agar decode & network gambar tidak berebut resource dengan rendering D-pad 60fps.
 */
object TvImageLoaderFactory {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: buildLoader(context.applicationContext).also { instance = it }
        }
    }

    private fun buildLoader(appContext: Context): ImageLoader {
        val dispatcher = Dispatcher().apply {
            maxRequests = 3
            maxRequestsPerHost = 2
        }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return ImageLoader.Builder(appContext)
            .okHttpClient(client)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(120)
            .respectCacheHeaders(false)
            .build()
    }
}

/**
 * Prioritas pemuatan gambar bertahap agar tidak tabrakan dengan layout/rendering awal:
 * - HERO (0): muat duluan setelah frame pertama stabil
 * - VISIBLE_ROW (1): muat setelah Hero siap / jeda ringan, ditahan saat scroll cepat
 * - PEEK_ROW (2): muat terakhir setelah scroll berhenti
 */
enum class TvImagePriority(val baseDelayMs: Long, val targetW: Int, val targetH: Int) {
    HERO(50L, 960, 540),
    VISIBLE_ROW(150L, 420, 600),
    PEEK_ROW(320L, 340, 480)
}

@Composable
fun tvShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "TvSkeletonShimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerShift"
    )
    return Brush.linearGradient(
        colors = listOf(
            Color(0xFF171A24),
            Color(0xFF252A3A),
            Color(0xFF171A24)
        ),
        start = Offset(shift - 300f, 0f),
        end = Offset(shift, 260f)
    )
}

@Composable
fun TvPriorityImage(
    url: String,
    contentDescription: String?,
    priority: TvImagePriority,
    allowLoad: Boolean,
    isScrollingFast: Boolean,
    reloadEpoch: Int,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    onLoaded: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { TvImageLoaderFactory.get(context) }
    var readyToRequest by remember(url, reloadEpoch) { mutableStateOf(false) }
    var localRetry by remember(url, reloadEpoch) { mutableIntStateOf(0) }
    var autoRetried by remember(url, reloadEpoch) { mutableStateOf(false) }

    LaunchedEffect(url, allowLoad, isScrollingFast, reloadEpoch, localRetry) {
        if (url.isBlank() || !allowLoad) {
            readyToRequest = false
            return@LaunchedEffect
        }
        if (isScrollingFast && priority != TvImagePriority.HERO) {
            return@LaunchedEffect
        }
        if (!readyToRequest) {
            delay(priority.baseDelayMs)
            readyToRequest = true
        }
    }

    val shimmer = tvShimmerBrush()

    if (url.isBlank() || !readyToRequest) {
        Box(modifier = modifier.background(shimmer))
        return
    }

    val request = remember(url, reloadEpoch, localRetry, priority) {
        ImageRequest.Builder(context)
            .data(url)
            .size(Size(priority.targetW, priority.targetH))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .setParameter("retry", "$reloadEpoch-$localRetry", memoryCacheKey = null)
            .build()
    }

    SubcomposeAsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Success -> {
                LaunchedEffect(url, reloadEpoch) { onLoaded?.invoke() }
                SubcomposeAsyncImageContent()
            }
            is AsyncImagePainter.State.Error -> {
                LaunchedEffect(url, reloadEpoch, autoRetried) {
                    if (!autoRetried) {
                        autoRetried = true
                        delay(650L)
                        localRetry += 1
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF151822))
                        .clickable { localRetry += 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Muat ulang gambar",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Muat ulang", color = TextSecondary, fontSize = 10.sp)
                    }
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize().background(shimmer))
            }
        }
    }
}

/**
 * Kartu konten TV 16:9 ala YouTube TV:
 * - Poster vertikal ditaruh utuh di tengah (ContentScale.Fit) tanpa terpotong.
 * - Sisi kiri-kanan menggunakan gradasi gelap ringan agar mempertajam fokus tanpa blur GPU berat.
 * - Border fokus terang + scale halus saat tersorot D-pad.
 */
@Composable
fun TvMediaCard16x9(
    item: MediaItem,
    priority: TvImagePriority,
    allowImageLoad: Boolean,
    isScrollingFast: Boolean,
    reloadEpoch: Int,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.045f else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "TvCardScale"
    )
    val borderColor = if (isFocused) Color.White else Color(0x22FFFFFF)
    val borderWidth = if (isFocused) 2.5.dp else 1.dp

    Column(
        modifier = modifier
            .scale(scale)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = if (isFocused) {
                            listOf(Color(0xFF171A26), Color(0xFF0B0D14), Color(0xFF171A26))
                        } else {
                            listOf(Color(0xFF0F1118), Color(0xFF090A0F), Color(0xFF0F1118))
                        }
                    )
                )
                .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Poster vertikal tetap utuh di tengah frame 16:9
            TvPriorityImage(
                url = item.coverUrl,
                contentDescription = item.title,
                priority = priority,
                allowLoad = allowImageLoad,
                isScrollingFast = isScrollingFast,
                reloadEpoch = reloadEpoch,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
            )

            // Vignette halus kiri-kanan untuk mempertajam fokus ke poster tengah
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.0f to Color(0x8808090D),
                            0.22f to Color.Transparent,
                            0.78f to Color.Transparent,
                            1.0f to Color(0x8808090D)
                        )
                    )
            )

            // Badge episode di kanan bawah
            if (item.totalEpisodes > 0) {
                Text(
                    text = "${item.totalEpisodes} Ep",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC0A0B0F))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }

            // Badge opsional di kiri atas
            item.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentRed)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = item.title,
            color = if (isFocused) Color.White else TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        val subtitle = buildString {
            if (item.tags.isNotEmpty()) {
                append(item.tags.take(2).joinToString(" · "))
            } else if (item.totalEpisodes > 0) {
                append("${item.totalEpisodes} Episode")
            }
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = if (isFocused) AccentGold else TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

/**
 * Kerangka UI (Skeleton) kartu 16:9 dengan siluet poster vertikal di tengah,
 * tampil langsung saat data sedang dimuat (bukan layar kosong polos).
 */
@Composable
fun TvSkeletonCard16x9(modifier: Modifier = Modifier) {
    val shimmer = tvShimmerBrush()
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceCard)
                .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.92f)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmer)
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmer)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmer)
        )
    }
}
