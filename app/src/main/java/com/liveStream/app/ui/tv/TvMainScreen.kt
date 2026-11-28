package com.liveStream.app.ui.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liveStream.app.BuildConfig
import com.liveStream.app.core.NativeCore
import com.liveStream.app.core.VideoCodecSupport
import com.liveStream.app.core.WatchHistoryStore
import com.liveStream.app.core.updater.AppUpdater
import com.liveStream.app.core.updater.UpdateInfo
import com.liveStream.app.ui.main.MainIntent
import com.liveStream.app.ui.main.MainUiState
import com.liveStream.app.ui.main.MainViewModel
import com.liveStream.app.ui.theme.AccentGold
import com.liveStream.app.ui.theme.AccentRed
import com.liveStream.app.ui.theme.BgDark
import com.liveStream.app.ui.theme.SurfaceCard
import com.liveStream.app.ui.theme.SurfaceElevated
import com.liveStream.app.ui.theme.TextPrimary
import com.liveStream.app.ui.theme.TextSecondary
import com.liveStream.contract.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class TvOverlay {
    NONE,
    PROVIDER_CATEGORY,
    SEARCH,
    PROFILE
}

/**
 * Layar utama khusus Android TV (gaya YouTube TV tanpa sidebar kiri):
 * - Top Bar: Profil (kiri) | List Provider & Kategori + Reload (tengah) | Mic & Pencarian (kanan)
 * - Zona 1 (awal): Hero Banner besar + 3 kotak baris pertama mengintip di bawah
 * - Zona 2 (tekan bawah): Konten naik menjadi 1 baris 3 kotak fokus + 3 kotak baris bawah mengintip
 * - Tombol Back dari Zona 2 langsung kembali ke Zona 1 (bukan keluar app)
 * - Kerangka UI (Skeleton) langsung tampil saat memuat + prioritas gambar (Hero -> Baris Aktif -> Ngintip) + Reload
 */
@Composable
fun TvMainScreen(
    viewModel: MainViewModel,
    onOpenPlayer: (MediaItem, Int, Long) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp

    val listState = rememberLazyListState()
    val heroPlayFocus = remember { FocusRequester() }
    val topProfileFocus = remember { FocusRequester() }

    var activeOverlay by remember { mutableStateOf(TvOverlay.NONE) }
    var focusedRowIndex by remember { mutableIntStateOf(-1) }
    var heroLoaded by remember { mutableStateOf(false) }
    var allowCardsLoad by remember { mutableStateOf(false) }
    var reloadEpoch by remember { mutableIntStateOf(0) }
    var lastBackPressMs by remember { mutableLongStateOf(0L) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadProgress by remember { mutableIntStateOf(-1) }
    var updateStatusText by remember { mutableStateOf("") }

    // Voice search launcher untuk tombol Mic di kanan atas
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                viewModel.onIntent(MainIntent.UpdateSearchQuery(spoken))
                viewModel.onIntent(MainIntent.SubmitSearch)
                activeOverlay = TvOverlay.NONE
            }
        }
    }

    LaunchedEffect(Unit) {
        AppUpdater.checkForUpdate { info ->
            if (info != null) updateInfo = info
        }
    }

    // Prioritas gambar: beri ruang agar Hero & rangka layout selesai dulu sebelum kartu memuat
    LaunchedEffect(state.activeProviderId, state.activeTabId, reloadEpoch, heroLoaded) {
        if (heroLoaded) {
            allowCardsLoad = true
        } else {
            allowCardsLoad = false
            delay(380L)
            allowCardsLoad = true
        }
    }

    // Reset state Hero setiap ganti provider/tab
    LaunchedEffect(state.activeProviderId, state.activeTabId, reloadEpoch) {
        heroLoaded = false
    }

    // Fokus awal ke tombol Tonton di Zona 1 saat overlay tertutup
    LaunchedEffect(state.items.isNotEmpty(), activeOverlay) {
        if (activeOverlay == TvOverlay.NONE && focusedRowIndex < 0) {
            delay(120L)
            runCatching { heroPlayFocus.requestFocus() }
        }
    }

    val isScrollingFast by remember {
        derivedStateOf { listState.isScrollInProgress }
    }
    val isInZone2 by remember {
        derivedStateOf { focusedRowIndex >= 0 || listState.firstVisibleItemIndex > 0 }
    }

    val featuredItem = remember(state.items) { state.items.firstOrNull() }
    val gridRows = remember(state.items) {
        if (state.items.size <= 1) state.items.chunked(3)
        else state.items.drop(1).chunked(3)
    }

    // Auto-pagination saat D-pad mendekati baris akhir
    LaunchedEffect(focusedRowIndex, gridRows.size, state.hasMore) {
        if (focusedRowIndex >= 0 && gridRows.isNotEmpty() && focusedRowIndex >= gridRows.size - 2 && state.hasMore) {
            viewModel.onIntent(MainIntent.LoadNextPage)
        }
    }

    // Back button TV: Tutup overlay -> Kembali ke Zona 1 -> Baru keluar app (double back)
    BackHandler(enabled = true) {
        when {
            activeOverlay != TvOverlay.NONE -> {
                activeOverlay = TvOverlay.NONE
            }
            isInZone2 -> {
                focusedRowIndex = -1
                scope.launch {
                    listState.animateScrollToItem(0)
                    delay(80L)
                    runCatching { heroPlayFocus.requestFocus() }
                }
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs < 2000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressMs = now
                    Toast.makeText(context, "Tekan Kembali sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val activeProvider = state.providers.firstOrNull { it.id == state.activeProviderId }
    val activeTabTitle = state.tabs.firstOrNull { it.id == state.activeTabId }?.title ?: "Beranda"

    // Tinggi Zona 1 dibuat pas (~72% tinggi layar) sehingga 3 kotak baris pertama otomatis mengintip di bawah
    val heroSectionHeight = (screenHeightDp * 0.70f).coerceIn(330.dp, 460.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // === ZONA 1: HERO BANNER ===
            item(key = "tv_zone_1_hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heroSectionHeight)
                ) {
                    if (state.isLoading && featuredItem == null) {
                        TvHeroSkeleton(modifier = Modifier.fillMaxSize())
                    } else if (featuredItem != null) {
                        val isFav = state.favorites.any {
                            it.providerId == featuredItem.providerId && it.mediaId == featuredItem.mediaId
                        }
                        TvHeroBanner(
                            item = featuredItem,
                            providerName = activeProvider?.displayName.orEmpty(),
                            isFavorite = isFav,
                            reloadEpoch = reloadEpoch,
                            playFocusRequester = heroPlayFocus,
                            onImageLoaded = { heroLoaded = true },
                            onHeroFocused = { focusedRowIndex = -1 },
                            onPlay = {
                                val hist = WatchHistoryStore.find(context, featuredItem.providerId, featuredItem.mediaId)
                                onOpenPlayer(featuredItem, hist?.episodeIndex ?: 0, hist?.positionMs ?: 0L)
                            },
                            onToggleFavorite = { viewModel.onIntent(MainIntent.ToggleFavorite(featuredItem)) },
                            onReload = {
                                reloadEpoch += 1
                                viewModel.onIntent(MainIntent.Refresh)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        TvEmptyHeroState(
                            errorMessage = state.errorMessage,
                            playFocusRequester = heroPlayFocus,
                            onReload = {
                                reloadEpoch += 1
                                viewModel.onIntent(MainIntent.Refresh)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // === ZONA 2: BARIS 3 KOTAK + 3 KOTAK MENGINTIP DI BAWAH ===
            if (state.isLoading && state.items.isEmpty()) {
                // Kerangka 2 baris (3 kotak + 3 kotak ngintip) agar tidak pernah loading polos
                items(2, key = { "skeleton_row_$it" }) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 36.dp)
                    ) {
                        repeat(3) {
                            TvSkeletonCard16x9(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = gridRows,
                    key = { rowIdx, row -> "tv_row_${rowIdx}_${row.firstOrNull()?.mediaId.orEmpty()}" }
                ) { rowIdx, rowItems ->
                    val isVisibleRow = focusedRowIndex < 0 && rowIdx == 0 ||
                        (focusedRowIndex >= 0 && rowIdx <= focusedRowIndex)
                    val rowPriority = if (isVisibleRow) TvImagePriority.VISIBLE_ROW else TvImagePriority.PEEK_ROW

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 36.dp)
                    ) {
                        rowItems.forEach { item ->
                            TvMediaCard16x9(
                                item = item,
                                priority = rowPriority,
                                allowImageLoad = allowCardsLoad,
                                isScrollingFast = isScrollingFast,
                                reloadEpoch = reloadEpoch,
                                onFocused = {
                                    val changedRow = focusedRowIndex != rowIdx
                                    focusedRowIndex = rowIdx
                                    if (changedRow) {
                                        // Geser halus sehingga baris 3 kotak naik ke atas & 3 kotak bawahnya mengintip
                                        scope.launch {
                                            listState.animateScrollToItem(
                                                index = rowIdx + 1,
                                                scrollOffset = -84
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    val hist = WatchHistoryStore.find(context, item.providerId, item.mediaId)
                                    onOpenPlayer(item, hist?.episodeIndex ?: 0, hist?.positionMs ?: 0L)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (state.isLoadingMore) {
                    item(key = "tv_loading_more_skeleton") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 36.dp)
                        ) {
                            repeat(3) {
                                TvSkeletonCard16x9(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // === TOP BAR FLOATING (PROFIL KIRI | PROVIDER & KATEGORI TENGAH | MIC & PENCARIAN KANAN) ===
        TvTopBar(
            providerName = activeProvider?.displayName ?: "Provider",
            providerIconUrl = activeProvider?.iconUrl.orEmpty(),
            categoryName = activeTabTitle,
            searchQuery = state.searchQuery,
            isScrolled = isInZone2,
            hasUpdate = updateInfo != null,
            profileFocusRequester = topProfileFocus,
            onOpenProfile = { activeOverlay = TvOverlay.PROFILE },
            onOpenProviderCategory = { activeOverlay = TvOverlay.PROVIDER_CATEGORY },
            onReload = {
                reloadEpoch += 1
                viewModel.onIntent(MainIntent.Refresh)
            },
            onVoiceSearch = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Sebutkan judul drama...")
                }
                runCatching { voiceLauncher.launch(intent) }.onFailure {
                    Toast.makeText(context, "Voice search tidak tersedia, membuka pencarian teks", Toast.LENGTH_SHORT).show()
                    activeOverlay = TvOverlay.SEARCH
                }
            },
            onOpenSearch = { activeOverlay = TvOverlay.SEARCH },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        )

        // === OVERLAY: PROVIDER & KATEGORI ===
        AnimatedVisibility(
            visible = activeOverlay == TvOverlay.PROVIDER_CATEGORY,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TvProviderCategoryOverlay(
                state = state,
                onSelectProvider = { pid ->
                    viewModel.onIntent(MainIntent.SelectProvider(pid))
                    focusedRowIndex = -1
                    scope.launch { listState.scrollToItem(0) }
                },
                onSelectTab = { tabId ->
                    viewModel.onIntent(MainIntent.SelectCatalogTab(tabId))
                    focusedRowIndex = -1
                    activeOverlay = TvOverlay.NONE
                    scope.launch { listState.scrollToItem(0) }
                },
                onClose = { activeOverlay = TvOverlay.NONE }
            )
        }

        // === OVERLAY: PENCARIAN TV ===
        AnimatedVisibility(
            visible = activeOverlay == TvOverlay.SEARCH,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TvSearchOverlay(
                initialQuery = state.searchQuery,
                onSubmit = { query ->
                    viewModel.onIntent(MainIntent.UpdateSearchQuery(query))
                    viewModel.onIntent(MainIntent.SubmitSearch)
                    focusedRowIndex = -1
                    activeOverlay = TvOverlay.NONE
                    scope.launch { listState.scrollToItem(0) }
                },
                onClose = { activeOverlay = TvOverlay.NONE }
            )
        }

        // === OVERLAY: PROFIL, RIWAYAT, KOLEKSI, PENGATURAN & OTA ===
        AnimatedVisibility(
            visible = activeOverlay == TvOverlay.PROFILE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TvProfileOverlay(
                state = state,
                updateInfo = updateInfo,
                downloadProgress = downloadProgress,
                statusText = updateStatusText,
                onOpenPlayer = { item, epIdx, posMs ->
                    activeOverlay = TvOverlay.NONE
                    onOpenPlayer(item, epIdx, posMs)
                },
                onToggleProvider = { pid, enabled ->
                    viewModel.onIntent(MainIntent.ToggleProviderEnabled(pid, enabled))
                },
                onCheckUpdate = {
                    updateStatusText = "Memeriksa pembaruan..."
                    AppUpdater.checkForUpdate { info ->
                        if (info != null) {
                            updateInfo = info
                            updateStatusText = "Tersedia v${info.versionName}"
                        } else {
                            updateStatusText = "Sudah versi terbaru (v${BuildConfig.VERSION_NAME})"
                        }
                    }
                },
                onDownloadUpdate = { info ->
                    val act = context as? Activity
                    if (act == null) {
                        updateStatusText = "Gagal memulai unduhan update"
                    } else {
                        downloadProgress = 0
                        AppUpdater.downloadAndInstall(
                            activity = act,
                            info = info,
                            onProgress = { pct -> downloadProgress = pct },
                            onError = { msg ->
                                downloadProgress = -1
                                updateStatusText = msg
                            }
                        )
                    }
                },
                onClose = { activeOverlay = TvOverlay.NONE }
            )
        }
    }
}

@Composable
private fun TvTopBar(
    providerName: String,
    providerIconUrl: String,
    categoryName: String,
    searchQuery: String,
    isScrolled: Boolean,
    hasUpdate: Boolean,
    profileFocusRequester: FocusRequester,
    onOpenProfile: () -> Unit,
    onOpenProviderCategory: () -> Unit,
    onReload: () -> Unit,
    onVoiceSearch: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val barBg = if (isScrolled) {
        Brush.verticalGradient(listOf(Color(0xF00A0B0F), Color(0xBB0A0B0F), Color.Transparent))
    } else {
        Brush.verticalGradient(listOf(Color(0xCC0A0B0F), Color(0x550A0B0F), Color.Transparent))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .background(barBg)
            .padding(horizontal = 36.dp, vertical = 18.dp)
    ) {
        // KIRI: Bulat Profil
        TvCircleIconButton(
            icon = Icons.Rounded.Person,
            contentDescription = "Profil & Pengaturan",
            badgeDot = hasUpdate,
            onClick = onOpenProfile,
            modifier = Modifier.focusRequester(profileFocusRequester)
        )

        // TENGAH: List Provider & Kategori + Reload cepat
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TvPillButton(
                onClick = onOpenProviderCategory,
                leadingContent = {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    if (providerIconUrl.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        AsyncImage(
                            model = providerIconUrl,
                            contentDescription = providerName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                        )
                    }
                },
                label = "$providerName  ·  $categoryName"
            )

            TvCircleIconButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = "Muat ulang katalog & gambar",
                onClick = onReload
            )
        }

        // UJUNG KANAN: Mic + Pencarian
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TvCircleIconButton(
                icon = Icons.Rounded.Mic,
                contentDescription = "Pencarian suara",
                onClick = onVoiceSearch
            )

            TvPillButton(
                onClick = onOpenSearch,
                leadingContent = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = if (searchQuery.isNotBlank()) searchQuery else "Penelusuran"
            )
        }
    }
}

@Composable
private fun TvHeroBanner(
    item: MediaItem,
    providerName: String,
    isFavorite: Boolean,
    reloadEpoch: Int,
    playFocusRequester: FocusRequester,
    onImageLoaded: () -> Unit,
    onHeroFocused: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF0A0B0F))
    ) {
        // Poster vertikal utuh diposisikan di sisi kanan dengan gradasi sinematik ke kiri
        TvPriorityImage(
            url = item.coverUrl,
            contentDescription = item.title,
            priority = TvImagePriority.HERO,
            allowLoad = true,
            isScrollingFast = false,
            reloadEpoch = reloadEpoch,
            contentScale = ContentScale.Fit,
            onLoaded = onImageLoaded,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 56.dp, top = 62.dp, bottom = 18.dp)
                .fillMaxHeight()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
        )

        // Vignette kiri & bawah untuk kontras teks super tajam di layar TV
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to BgDark,
                        0.50f to Color(0xEE0A0B0F),
                        0.76f to Color(0x550A0B0F),
                        1.0f to Color(0x220A0B0F)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0x550A0B0F),
                        0.68f to Color.Transparent,
                        1.0f to BgDark
                    )
                )
        )

        // Info & tombol aksi di kiri bawah Hero
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(start = 36.dp, bottom = 26.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (providerName.isNotBlank()) {
                    Text(
                        text = providerName.uppercase(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentRed)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                if (item.totalEpisodes > 0) {
                    Text(
                        text = "${item.totalEpisodes} Episode",
                        color = AccentGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (item.tags.isNotEmpty()) {
                    Text(
                        text = "· ${item.tags.take(3).joinToString(" · ")}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = item.title,
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 35.sp
            )

            if (item.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = item.description,
                    color = Color(0xFFD0D4E0),
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvPrimaryHeroButton(
                    label = "Tonton",
                    icon = Icons.Rounded.PlayArrow,
                    onFocused = onHeroFocused,
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(playFocusRequester)
                )

                TvPillButton(
                    label = if (isFavorite) "Tersimpan" else "Koleksi",
                    leadingContent = {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            contentDescription = null,
                            tint = if (isFavorite) AccentGold else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onFocused = onHeroFocused,
                    onClick = onToggleFavorite
                )

                TvCircleIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "Muat ulang banner",
                    onFocused = onHeroFocused,
                    onClick = onReload
                )
            }
        }
    }
}

@Composable
private fun TvHeroSkeleton(modifier: Modifier = Modifier) {
    val shimmer = tvShimmerBrush()
    Box(modifier = modifier.background(BgDark)) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 56.dp, top = 68.dp, bottom = 22.dp)
                .fillMaxHeight()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(shimmer)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.55f)
                .padding(start = 36.dp, bottom = 28.dp)
        ) {
            Box(Modifier.width(120.dp).height(18.dp).clip(RoundedCornerShape(6.dp)).background(shimmer))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(0.85f).height(34.dp).clip(RoundedCornerShape(8.dp)).background(shimmer))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(0.70f).height(16.dp).clip(RoundedCornerShape(6.dp)).background(shimmer))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(130.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(shimmer))
                Box(Modifier.width(120.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(shimmer))
            }
        }
    }
}

@Composable
private fun TvEmptyHeroState(
    errorMessage: String?,
    playFocusRequester: FocusRequester,
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = errorMessage ?: "Katalog belum tersedia",
                color = TextSecondary,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(14.dp))
            TvPrimaryHeroButton(
                label = "Muat Ulang",
                icon = Icons.Rounded.Refresh,
                onClick = onReload,
                modifier = Modifier.focusRequester(playFocusRequester)
            )
        }
    }
}

@Composable
private fun TvPrimaryHeroButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (focused) Color.White else Color(0xFFE8EBF2))
            .border(
                width = if (focused) 2.5.dp else 0.dp,
                color = if (focused) AccentRed else Color.Transparent,
                shape = RoundedCornerShape(24.dp)
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 11.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TvPillButton(
    label: String,
    leadingContent: @Composable (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (focused) Color(0xFF2B3042) else Color(0xBB191C26))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color(0x28FFFFFF),
                shape = RoundedCornerShape(22.dp)
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TvCircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    badgeDot: Boolean = false,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color(0xBB191C26))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color(0x28FFFFFF),
                shape = CircleShape
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(20.dp)
        )
        if (badgeDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentRed)
            )
        }
    }
}

@Composable
private fun TvProviderCategoryOverlay(
    state: MainUiState,
    onSelectProvider: (String) -> Unit,
    onSelectTab: (String) -> Unit,
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
                .fillMaxWidth(0.76f)
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
                Text("Provider & Kategori", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TvCircleIconButton(icon = Icons.Rounded.Close, contentDescription = "Tutup", onClick = onClose)
            }

            Spacer(Modifier.height(16.dp))
            Text("Pilih Provider", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.providers, key = { _, p -> p.id }) { idx, p ->
                    val active = p.id == state.activeProviderId
                    TvPillButton(
                        label = p.displayName,
                        leadingContent = {
                            if (p.iconUrl.isNotBlank()) {
                                AsyncImage(
                                    model = p.iconUrl,
                                    contentDescription = p.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(20.dp).clip(CircleShape)
                                )
                            } else if (active) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = AccentRed, modifier = Modifier.size(16.dp))
                            }
                        },
                        onClick = { onSelectProvider(p.id) },
                        modifier = if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("Kategori", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.tabs, key = { it.id }) { tab ->
                    val active = tab.id == state.activeTabId
                    TvPillButton(
                        label = if (active) "● ${tab.title}" else tab.title,
                        onClick = { onSelectTab(tab.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSearchOverlay(
    initialQuery: String,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit
) {
    var text by remember { mutableStateOf(initialQuery) }
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { inputFocus.requestFocus() }
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
                .fillMaxWidth(0.62f)
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceCard)
                .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            Text("Pencarian Drama", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElevated)
                    .border(1.5.dp, Color.White, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary)
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(AccentRed),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit(text) }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocus)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvPrimaryHeroButton(label = "Cari", icon = Icons.Rounded.Search, onClick = { onSubmit(text) })
                TvPillButton(label = "Reset", onClick = { onSubmit("") })
                TvPillButton(label = "Tutup", onClick = onClose)
            }
        }
    }
}

@Composable
private fun TvProfileOverlay(
    state: MainUiState,
    updateInfo: UpdateInfo?,
    downloadProgress: Int,
    statusText: String,
    onOpenPlayer: (MediaItem, Int, Long) -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (UpdateInfo) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val history = remember { WatchHistoryStore.loadAll(context) }
    val codecSummary = remember { VideoCodecSupport.supportedCodecSummary() }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { closeFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE605060A))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceCard)
                .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text("Profil & Pengaturan TV", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "LIVE v${BuildConfig.VERSION_NAME} · Rust: ${if (NativeCore.isAvailable()) "Aktif" else "Fallback"} · Decoder: $codecSummary",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    TvCircleIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Tutup",
                        onClick = onClose,
                        modifier = Modifier.focusRequester(closeFocus)
                    )
                }
            }

            if (history.isNotEmpty()) {
                item {
                    Text("Lanjut Tonton", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(history.take(8), key = { "${it.providerId}_${it.mediaId}" }) { h ->
                            TvPillButton(
                                label = "${h.title} (Ep ${h.episodeNumber})",
                                leadingContent = {
                                    Icon(Icons.Rounded.History, contentDescription = null, tint = AccentGold, modifier = Modifier.size(16.dp))
                                },
                                onClick = { onOpenPlayer(h.toMediaItem(), h.episodeIndex, h.positionMs) }
                            )
                        }
                    }
                }
            }

            if (state.favorites.isNotEmpty()) {
                item {
                    Text("Koleksi Tersimpan", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.favorites.take(8), key = { "${it.providerId}_${it.mediaId}" }) { fav ->
                            TvPillButton(
                                label = fav.title,
                                leadingContent = {
                                    Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = AccentRed, modifier = Modifier.size(16.dp))
                                },
                                onClick = { onOpenPlayer(fav, 0, 0L) }
                            )
                        }
                    }
                }
            }

            item {
                Text("Pembaruan OTA", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (updateInfo != null) {
                        TvPrimaryHeroButton(
                            label = "Update ke v${updateInfo.versionName}",
                            icon = Icons.Rounded.SystemUpdate,
                            onClick = { onDownloadUpdate(updateInfo) }
                        )
                    } else {
                        TvPillButton(
                            label = "Cek Update",
                            leadingContent = {
                                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            },
                            onClick = onCheckUpdate
                        )
                    }
                    if (statusText.isNotBlank()) {
                        Text(statusText, color = AccentGold, fontSize = 12.sp)
                    }
                }
                if (downloadProgress in 0..100) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        color = AccentRed,
                        trackColor = SurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Text("Kelola Provider Aktif", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            items(state.allProviders, key = { it.id }) { p ->
                val enabled = p.id !in state.disabledProviderIds
                var focused by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (focused) Color(0xFF262B3A) else SurfaceElevated)
                        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x18FFFFFF), RoundedCornerShape(12.dp))
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .clickable { onToggleProvider(p.id, !enabled) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(p.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { onToggleProvider(p.id, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = AccentRed)
                    )
                }
            }
        }
    }
}
