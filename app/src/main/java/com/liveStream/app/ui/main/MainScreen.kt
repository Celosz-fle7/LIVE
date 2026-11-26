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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liveStream.app.core.NativeCore
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

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenPlayer: (MediaItem) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

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
                    BottomNavTab.DISCOVER -> DiscoverPage(state, viewModel::onIntent, onOpenPlayer)
                    BottomNavTab.FOR_YOU -> ForYouPagerPage(state.items, onOpenPlayer)
                    BottomNavTab.MY_LIST -> FavoritesPage(state.favorites, onOpenPlayer)
                    BottomNavTab.PROFILE -> ProfilePage(state)
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
private fun DiscoverPage(
    state: MainUiState,
    onIntent: (MainIntent) -> Unit,
    onOpenPlayer: (MediaItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        // Bar pemilih Provider
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.providers, key = { it.id }) { p ->
                val active = p.id == state.activeProviderId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) AccentRed else SurfaceElevated)
                        .clickable { onIntent(MainIntent.SelectProvider(p.id)) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = p.displayName,
                        color = if (active) Color.White else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.items, key = { "${it.providerId}:${it.mediaId}" }) { item ->
                    PosterCard(item = item, onClick = { onOpenPlayer(item) })
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ForYouPagerPage(items: List<MediaItem>, onOpenPlayer: (MediaItem) -> Unit) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Belum ada feed", color = TextSecondary)
        }
        return
    }
    val pagerState = rememberPagerState(pageCount = { items.size })
    VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { idx ->
        val item = items[idx]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(24.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(item.title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (item.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(item.tags.joinToString(" • "), color = TextSecondary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onOpenPlayer(item) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Tonton (${item.totalEpisodes} Ep)")
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
private fun ProfilePage(state: MainUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text("LIVE Stream Shell", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Provider terdaftar: ${state.providers.size}", color = TextSecondary, fontSize = 13.sp)
        Text("Rust Native Core: ${NativeCore.ping()}", color = AccentGold, fontSize = 13.sp)
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
