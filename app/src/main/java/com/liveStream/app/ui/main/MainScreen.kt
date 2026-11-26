package com.liveStream.app.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.app.Activity
import coil.compose.AsyncImage
import com.liveStream.app.BuildConfig
import com.liveStream.app.core.NativeCore
import com.liveStream.app.core.updater.AppUpdater
import com.liveStream.app.core.updater.UpdateInfo
import com.liveStream.app.provider.ProviderRegistry
import com.liveStream.app.ui.theme.AccentGold
import com.liveStream.app.ui.theme.AccentRed
import com.liveStream.app.ui.theme.BgDark
import com.liveStream.app.ui.theme.BorderSubtle
import com.liveStream.app.ui.theme.SurfaceCard
import com.liveStream.app.ui.theme.SurfaceElevated
import com.liveStream.app.ui.theme.TextPrimary
import com.liveStream.app.ui.theme.TextSecondary
import com.liveStream.contract.FilterSelection
import com.liveStream.contract.MediaItem
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenPlayer: (MediaItem) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadProgress by remember { mutableIntStateOf(-1) }
    var updateStatusText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        AppUpdater.checkForUpdate { info ->
            if (info != null) updateInfo = info
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = SurfaceCard,
                modifier = Modifier.width(280.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                    Text("Pilih Provider", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Kategori & katalog otomatis menyesuaikan", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    state.providers.forEach { p ->
                        val active = p.id == state.activeProviderId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) SurfaceElevated else Color.Transparent)
                                .border(
                                    width = if (active) 1.dp else 0.dp,
                                    color = if (active) AccentRed else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    viewModel.onIntent(MainIntent.SelectProvider(p.id))
                                    scope.launch { drawerState.close() }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            ProviderLogo(iconUrl = p.iconUrl, name = p.displayName, sizeDp = 34)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = p.displayName,
                                    color = if (active) TextPrimary else TextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                )
                                if (active) {
                                    Text("Aktif", color = AccentRed, fontSize = 11.sp)
                                }
                            }
                            if (active) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = AccentRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = BgDark,
            bottomBar = {
                DockedBottomBar(
                    activeTab = state.bottomTab,
                    onSelect = { viewModel.onIntent(MainIntent.SelectBottomTab(it)) }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                Crossfade(targetState = state.bottomTab, label = "MainTab") { tab ->
                    when (tab) {
                        BottomNavTab.DISCOVER -> DiscoverPage(
                            state = state,
                            onIntent = viewModel::onIntent,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenPlayer = onOpenPlayer
                        )
                        BottomNavTab.FOR_YOU -> ForYouPagerPage(
                            tabs = state.forYouTabs,
                            activeTabId = state.activeForYouTabId,
                            items = state.forYouItems,
                            favorites = state.favorites,
                            initialPage = state.forYouPageIndex,
                            isVisible = state.bottomTab == BottomNavTab.FOR_YOU,
                            isLoading = state.forYouLoading,
                            onIntent = viewModel::onIntent,
                            onOpenPlayer = onOpenPlayer
                        )
                        BottomNavTab.MY_LIST -> FavoritesPage(state.favorites, onOpenPlayer)
                        BottomNavTab.PROFILE -> ProfilePage(
                            state = state,
                            statusText = updateStatusText,
                            onCheckUpdate = {
                                updateStatusText = "Memeriksa pembaruan..."
                                AppUpdater.checkForUpdate { info ->
                                    if (info != null) {
                                        updateInfo = info
                                        updateStatusText = "Versi v${info.versionName} tersedia"
                                    } else {
                                        updateStatusText = "Sudah versi terbaru (v${BuildConfig.VERSION_NAME})"
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (state.showFilterSheet) {
        MultiLadderFilterSheet(
            state = state,
            onIntent = viewModel::onIntent
        )
    }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!info.forceUpdate && downloadProgress < 0) updateInfo = null },
            containerColor = SurfaceCard,
            title = { Text("Update Tersedia v${info.versionName}", color = TextPrimary) },
            text = {
                Column {
                    Text(info.changelog, color = TextSecondary, fontSize = 13.sp)
                    if (downloadProgress in 0..100) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = AccentRed
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Mengunduh $downloadProgress%", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = downloadProgress < 0,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    onClick = {
                        val act = context as? Activity ?: return@Button
                        downloadProgress = 0
                        AppUpdater.downloadAndInstall(
                            activity = act,
                            info = info,
                            onProgress = { downloadProgress = it },
                            onError = { err ->
                                downloadProgress = -1
                                updateStatusText = err
                                updateInfo = null
                            }
                        )
                    }
                ) { Text(if (downloadProgress >= 0) "Mengunduh..." else "Update Sekarang") }
            },
            dismissButton = {
                if (!info.forceUpdate && downloadProgress < 0) {
                    TextButton(onClick = { updateInfo = null }) {
                        Text("Nanti", color = TextSecondary)
                    }
                }
            }
        )
    }
}

@Composable
private fun DockedBottomBar(
    activeTab: BottomNavTab,
    onSelect: (BottomNavTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x660A0B0F), Color(0xE60A0B0F), BgDark)
                )
            )
            .border(0.5.dp, BorderSubtle)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        BottomNavTab.entries.forEach { tab ->
            val selected = tab == activeTab
            val icon = when (tab) {
                BottomNavTab.DISCOVER -> Icons.Rounded.Explore
                BottomNavTab.FOR_YOU -> Icons.Rounded.PlayCircle
                BottomNavTab.MY_LIST -> Icons.Rounded.Bookmark
                BottomNavTab.PROFILE -> Icons.Rounded.Person
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = tab.label,
                    tint = if (selected) AccentRed else TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tab.label,
                    color = if (selected) AccentRed else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ProviderLogo(iconUrl: String, name: String, sizeDp: Int) {
    if (iconUrl.isNotBlank()) {
        AsyncImage(
            model = iconUrl,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceElevated)
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AccentRed.copy(alpha = 0.2f))
        ) {
            Text(
                text = name.take(1).uppercase(),
                color = AccentRed,
                fontWeight = FontWeight.Bold,
                fontSize = (sizeDp / 2).sp
            )
        }
    }
}

@Composable
private fun DiscoverPage(
    state: MainUiState,
    onIntent: (MainIntent) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenPlayer: (MediaItem) -> Unit
) {
    val activeProvider = state.providers.firstOrNull { it.id == state.activeProviderId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(6.dp))
        // Header: Tombol Garis Tiga + Logo & Nama Provider Aktif
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Rounded.Menu, contentDescription = "Menu Provider", tint = TextPrimary)
            }
            if (activeProvider != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenDrawer() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    ProviderLogo(iconUrl = activeProvider.iconUrl, name = activeProvider.displayName, sizeDp = 26)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = activeProvider.displayName,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Search bar + Tombol Filter Bertingkat
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp)
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = state.searchQuery,
                    onValueChange = { onIntent(MainIntent.UpdateSearchQuery(it)) },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(AccentRed),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onIntent(MainIntent.SubmitSearch) }),
                    decorationBox = { inner ->
                        if (state.searchQuery.isEmpty()) {
                            Text("Cari judul drama...", color = TextSecondary, fontSize = 14.sp)
                        }
                        inner()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (state.filterLadders.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { onIntent(MainIntent.ToggleFilterSheet(true)) },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (state.selectedFilters.isNotEmpty()) AccentRed else SurfaceCard)
                ) {
                    Icon(Icons.Rounded.FilterList, contentDescription = "Filter", tint = TextPrimary)
                }
            }
        }

        if (state.tabs.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.tabs, key = { it.id }) { t ->
                    val active = t.id == state.activeTabId
                    Text(
                        text = t.title,
                        color = if (active) TextPrimary else TextSecondary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) SurfaceElevated else Color.Transparent)
                            .clickable { onIntent(MainIntent.SelectCatalogTab(t.id)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (state.isLoading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentRed)
            }
        } else {
            val gridState = rememberLazyGridState()
            val shouldLoadMore by remember(state.items.size, state.hasMore, state.isLoadingMore) {
                derivedStateOf {
                    val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    state.hasMore && !state.isLoadingMore && state.items.isNotEmpty() && lastVisible >= (state.items.size - 6).coerceAtLeast(0)
                }
            }
            LaunchedEffect(shouldLoadMore) {
                if (shouldLoadMore) onIntent(MainIntent.LoadNextPage)
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 112.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.items, key = { "${it.providerId}:${it.mediaId}" }) { item ->
                    PosterCard(item = item, onClick = { onOpenPlayer(item) })
                }
                if (state.isLoadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AccentRed, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(item: MediaItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceCard)
        ) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(34.dp))
                }
            }
            if (!item.badge.isNullOrBlank()) {
                Text(
                    text = item.badge!!,
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AccentGold)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            if (item.totalEpisodes > 0) {
                Text(
                    text = "${item.totalEpisodes} Ep",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ForYouPagerPage(
    tabs: List<com.liveStream.contract.TabSpec>,
    activeTabId: String,
    items: List<MediaItem>,
    favorites: List<MediaItem>,
    initialPage: Int,
    isVisible: Boolean,
    isLoading: Boolean,
    onIntent: (MainIntent) -> Unit,
    onOpenPlayer: (MediaItem) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isLoading) {
                    CircularProgressIndicator(color = AccentRed)
                } else {
                    Text("Belum ada rekomendasi", color = TextSecondary)
                }
            }
        } else {
            val context = LocalContext.current
            val safeInitial = initialPage.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { items.size })
            val exoPlayer = remember {
                ExoPlayer.Builder(context).build().apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                }
            }
            var activePlayingKey by remember { mutableStateOf("") }
            var isPaused by remember { mutableStateOf(false) }

            DisposableEffect(Unit) {
                onDispose { exoPlayer.release() }
            }

            LaunchedEffect(pagerState.currentPage) {
                onIntent(MainIntent.SetForYouPage(pagerState.currentPage))
            }

            LaunchedEffect(isVisible) {
                if (!isVisible) {
                    exoPlayer.playWhenReady = false
                    exoPlayer.pause()
                } else if (activePlayingKey.isNotEmpty()) {
                    exoPlayer.playWhenReady = !isPaused
                }
            }

            val currentItem = items.getOrNull(pagerState.currentPage)
            LaunchedEffect(currentItem?.providerId, currentItem?.mediaId, isVisible, activeTabId) {
                val item = currentItem ?: return@LaunchedEffect
                if (!isVisible) return@LaunchedEffect
                val key = "${item.providerId}:${item.mediaId}"
                if (pagerState.currentPage >= items.size - 4) {
                    onIntent(MainIntent.LoadMoreForYou)
                }
                activePlayingKey = ""
                isPaused = false
                exoPlayer.stop()

                val provider = ProviderRegistry.get(item.providerId)
                val eps = runCatching { provider.getEpisodes(item.mediaId).episodes }.getOrDefault(emptyList())
                val firstEp = eps.firstOrNull { !it.isLocked } ?: return@LaunchedEffect
                val src = runCatching { provider.resolvePlayback(item.mediaId, firstEp.episodeId) }.getOrNull()
                    ?: return@LaunchedEffect
                if (src.streamUrl.isNotBlank() && isVisible) {
                    val dsFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(src.headers)
                    val mediaSource = DefaultMediaSourceFactory(dsFactory)
                        .createMediaSource(ExoMediaItem.fromUri(src.streamUrl))
                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    activePlayingKey = key
                }
            }

            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { idx ->
                val item = items[idx]
                val isCurrent = idx == pagerState.currentPage && activePlayingKey == "${item.providerId}:${item.mediaId}"
                val isSaved = favorites.any { it.providerId == item.providerId && it.mediaId == item.mediaId }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable {
                            if (isCurrent) {
                                isPaused = !isPaused
                                exoPlayer.playWhenReady = !isPaused
                            }
                        }
                ) {
                    if (item.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.coverUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (isCurrent) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    player = exoPlayer
                                }
                            },
                            update = { it.player = exoPlayer },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Gradient overlay bawah
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0A0B0F), BgDark))
                            )
                    )

                    // Info detail konten & tombol tonton di kiri bawah
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, end = 84.dp, bottom = 24.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = ProviderRegistry.get(item.providerId).displayName,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AccentRed.copy(alpha = 0.85f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                            if (!item.badge.isNullOrBlank()) {
                                Text(
                                    text = item.badge!!,
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AccentGold)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                            if (item.totalEpisodes > 0) {
                                Text(
                                    text = "${item.totalEpisodes} Ep",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0x88000000))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(item.title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                        if (item.tags.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(item.tags.joinToString(" • "), color = AccentGold, fontSize = 12.sp, maxLines = 1)
                        }
                        if (item.description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = item.description,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Cuplikan Episode 1 • Tap untuk jeda/lanjut",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                exoPlayer.pause()
                                onOpenPlayer(item)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Tonton Lengkap (${item.totalEpisodes} Ep)")
                        }
                    }

                    // Aksi Koleksi di kanan bawah
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 28.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { onIntent(MainIntent.ToggleFavorite(item)) }
                        ) {
                            Icon(
                                imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                contentDescription = "Koleksi",
                                tint = if (isSaved) AccentGold else Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                text = if (isSaved) "Tersimpan" else "Koleksi",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // 2 Pilihan Tab di Tengah Atas (mis. Untukmu | Anime)
        if (tabs.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                tabs.forEach { t ->
                    val selected = t.id == activeTabId
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onIntent(MainIntent.SelectForYouTab(t.id)) }
                    ) {
                        Text(
                            text = t.title,
                            color = if (selected) Color.White else TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                        if (selected) {
                            Spacer(Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height(2.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(AccentRed)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesPage(favorites: List<MediaItem>, onOpenPlayer: (MediaItem) -> Unit) {
    if (favorites.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Koleksi masih kosong", color = TextSecondary)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding()
        ) {
            items(favorites, key = { "${it.providerId}:${it.mediaId}" }) { item ->
                PosterCard(item = item, onClick = { onOpenPlayer(item) })
            }
        }
    }
}

@Composable
private fun ProfilePage(
    state: MainUiState,
    statusText: String,
    onCheckUpdate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text("LIVE Stream Shell", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Versi: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = TextSecondary, fontSize = 13.sp)
        Text("Provider terdaftar: ${state.providers.size}", color = TextSecondary, fontSize = 13.sp)
        Text("Rust Native Core: ${NativeCore.ping()}", color = AccentGold, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onCheckUpdate,
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
        ) {
            Text("Cek Update OTA")
        }
        if (statusText.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(statusText, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MultiLadderFilterSheet(
    state: MainUiState,
    onIntent: (MainIntent) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(MainIntent.ToggleFilterSheet(false)) },
        containerColor = SurfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Filter Kategori", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            state.filterLadders.forEach { ladder ->
                Text(ladder.title, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ladder.options.forEach { opt ->
                        val selected = state.selectedFilters[ladder.id]?.optionId == opt.id
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onIntent(
                                    MainIntent.SelectFilterOption(
                                        FilterSelection(ladder.id, opt.id, opt.filterType)
                                    )
                                )
                            },
                            label = { Text(opt.name, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                OutlinedButton(
                    onClick = { onIntent(MainIntent.ClearFilters) },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset") }
                Button(
                    onClick = { onIntent(MainIntent.ApplyFilters) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    modifier = Modifier.weight(1f)
                ) { Text("Terapkan") }
            }
        }
    }
}
