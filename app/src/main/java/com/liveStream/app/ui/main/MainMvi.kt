package com.liveStream.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liveStream.app.provider.ProviderRegistry
import com.liveStream.contract.DramaProvider
import com.liveStream.contract.FilterLadder
import com.liveStream.contract.FilterSelection
import com.liveStream.contract.MediaItem
import com.liveStream.contract.TabSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BottomNavTab(val label: String) {
    DISCOVER("Jelajah"),
    FOR_YOU("Untukmu"),
    MY_LIST("Koleksi"),
    PROFILE("Profil")
}

data class ProviderSummary(
    val id: String,
    val displayName: String,
    val iconUrl: String = ""
)

data class MainUiState(
    val bottomTab: BottomNavTab = BottomNavTab.DISCOVER,
    val providers: List<ProviderSummary> = emptyList(),
    val activeProviderId: String = "",
    val tabs: List<TabSpec> = emptyList(),
    val activeTabId: String = "",
    val items: List<MediaItem> = emptyList(),
    val nextCursor: String = "",
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val searchQuery: String = "",
    val filterLadders: List<FilterLadder> = emptyList(),
    val selectedFilters: Map<String, FilterSelection> = emptyMap(),
    val showFilterSheet: Boolean = false,
    val forYouTabs: List<TabSpec> = emptyList(),
    val activeForYouTabId: String = "",
    val forYouItems: List<MediaItem> = emptyList(),
    val forYouCursor: String = "",
    val forYouHasMore: Boolean = false,
    val forYouLoading: Boolean = false,
    val forYouPageIndex: Int = 0,
    val favorites: List<MediaItem> = emptyList(),
    val errorMessage: String? = null
)

sealed interface MainIntent {
    data class SelectBottomTab(val tab: BottomNavTab) : MainIntent
    data class SelectProvider(val providerId: String) : MainIntent
    data class SelectCatalogTab(val tabId: String) : MainIntent
    data class UpdateSearchQuery(val query: String) : MainIntent
    data object SubmitSearch : MainIntent
    data object Refresh : MainIntent
    data object LoadNextPage : MainIntent
    data object LoadMoreForYou : MainIntent
    data class SelectForYouTab(val tabId: String) : MainIntent
    data class SetForYouPage(val index: Int) : MainIntent
    data class ToggleFilterSheet(val show: Boolean) : MainIntent
    data class SelectFilterOption(val selection: FilterSelection) : MainIntent
    data object ApplyFilters : MainIntent
    data object ClearFilters : MainIntent
    data class ToggleFavorite(val item: MediaItem) : MainIntent
}

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        val all = ProviderRegistry.all()
        val summaries = all.map { ProviderSummary(it.id, it.displayName, it.iconUrl) }
        val firstId = summaries.firstOrNull()?.id.orEmpty()
        _uiState.update { it.copy(providers = summaries, activeProviderId = firstId) }
        if (firstId.isNotEmpty()) {
            loadProvider(firstId)
        }
    }

    fun onIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.SelectBottomTab -> {
                _uiState.update { it.copy(bottomTab = intent.tab) }
                if (intent.tab == BottomNavTab.FOR_YOU && _uiState.value.forYouItems.isEmpty() && !_uiState.value.forYouLoading) {
                    loadForYou(reset = true)
                }
            }
            is MainIntent.SelectProvider -> if (intent.providerId != _uiState.value.activeProviderId) {
                loadProvider(intent.providerId)
            }
            is MainIntent.SelectCatalogTab -> loadFeed(tabId = intent.tabId, reset = true)
            is MainIntent.UpdateSearchQuery -> _uiState.update { it.copy(searchQuery = intent.query) }
            MainIntent.SubmitSearch -> runSearch()
            MainIntent.Refresh -> loadFeed(tabId = _uiState.value.activeTabId, reset = true, isRefresh = true)
            MainIntent.LoadNextPage -> if (_uiState.value.hasMore && !_uiState.value.isLoading && !_uiState.value.isLoadingMore) {
                if (_uiState.value.selectedFilters.isNotEmpty()) {
                    loadFilteredFeed(reset = false)
                } else {
                    loadFeed(tabId = _uiState.value.activeTabId, reset = false)
                }
            }
            MainIntent.LoadMoreForYou -> if (_uiState.value.forYouHasMore && !_uiState.value.forYouLoading) {
                loadForYou(reset = false)
            }
            is MainIntent.SelectForYouTab -> {
                _uiState.update {
                    it.copy(
                        activeForYouTabId = intent.tabId,
                        forYouItems = emptyList(),
                        forYouCursor = "",
                        forYouHasMore = false,
                        forYouPageIndex = 0
                    )
                }
                loadForYou(reset = true)
            }
            is MainIntent.SetForYouPage -> _uiState.update { it.copy(forYouPageIndex = intent.index) }
            is MainIntent.ToggleFilterSheet -> _uiState.update { it.copy(showFilterSheet = intent.show) }
            is MainIntent.SelectFilterOption -> _uiState.update { state ->
                val next = state.selectedFilters.toMutableMap()
                if (intent.selection.optionId == "0") {
                    next.remove(intent.selection.ladderId)
                } else {
                    next[intent.selection.ladderId] = intent.selection
                }
                state.copy(selectedFilters = next)
            }
            MainIntent.ApplyFilters -> {
                _uiState.update { it.copy(showFilterSheet = false) }
                loadFilteredFeed(reset = true)
            }
            MainIntent.ClearFilters -> {
                _uiState.update { it.copy(selectedFilters = emptyMap(), showFilterSheet = false) }
                loadFeed(tabId = _uiState.value.activeTabId, reset = true)
            }
            is MainIntent.ToggleFavorite -> _uiState.update { state ->
                val exists = state.favorites.any {
                    it.providerId == intent.item.providerId && it.mediaId == intent.item.mediaId
                }
                val updated = if (exists) {
                    state.favorites.filterNot {
                        it.providerId == intent.item.providerId && it.mediaId == intent.item.mediaId
                    }
                } else {
                    listOf(intent.item) + state.favorites
                }
                state.copy(favorites = updated)
            }
        }
    }

    private fun activeProvider(): DramaProvider = ProviderRegistry.get(_uiState.value.activeProviderId)

    private fun loadProvider(providerId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeProviderId = providerId,
                    isLoading = true,
                    items = emptyList(),
                    forYouItems = emptyList(),
                    forYouCursor = "",
                    forYouHasMore = false,
                    forYouPageIndex = 0,
                    selectedFilters = emptyMap(),
                    searchQuery = "",
                    errorMessage = null
                )
            }
            runCatching {
                val p = ProviderRegistry.get(providerId)
                val tabs = p.getTabs()
                val ladders = p.getFilterLadders()
                val defaultTab = tabs.firstOrNull { it.isDefault }?.id ?: tabs.firstOrNull()?.id.orEmpty()
                val page = p.getFeed(defaultTab, cursor = "")
                val fyTabs = runCatching { p.getForYouTabs() }.getOrDefault(emptyList())
                val defaultFyTab = fyTabs.firstOrNull { it.isDefault }?.id ?: fyTabs.firstOrNull()?.id.orEmpty()
                val forYou = runCatching { p.getForYouFeed(defaultFyTab, "") }.getOrDefault(page)
                listOf(tabs, ladders, defaultTab, page, forYou, fyTabs, defaultFyTab)
            }.onSuccess { res ->
                @Suppress("UNCHECKED_CAST")
                val tabs = res[0] as List<TabSpec>
                @Suppress("UNCHECKED_CAST")
                val ladders = res[1] as List<FilterLadder>
                val defaultTab = res[2] as String
                val page = res[3] as com.liveStream.contract.FeedPage
                val forYou = res[4] as com.liveStream.contract.FeedPage
                @Suppress("UNCHECKED_CAST")
                val fyTabs = res[5] as List<TabSpec>
                val defaultFyTab = res[6] as String
                _uiState.update {
                    it.copy(
                        tabs = tabs,
                        activeTabId = defaultTab,
                        filterLadders = ladders,
                        items = page.items,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore,
                        forYouTabs = fyTabs,
                        activeForYouTabId = defaultFyTab,
                        forYouItems = forYou.items,
                        forYouCursor = forYou.nextCursor,
                        forYouHasMore = forYou.hasMore,
                        isLoading = false
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
            }
        }
    }

    private fun loadFeed(tabId: String, reset: Boolean, isRefresh: Boolean = false) {
        viewModelScope.launch {
            val cursor = if (reset) "" else _uiState.value.nextCursor
            _uiState.update {
                it.copy(
                    activeTabId = tabId,
                    isLoading = reset && !isRefresh,
                    isLoadingMore = !reset,
                    isRefreshing = isRefresh,
                    errorMessage = null
                )
            }
            runCatching { activeProvider().getFeed(tabId, cursor) }
                .onSuccess { page ->
                    _uiState.update { state ->
                        val merged = if (reset) page.items else (state.items + page.items).distinctBy { "${it.providerId}:${it.mediaId}" }
                        state.copy(
                            items = merged,
                            nextCursor = page.nextCursor,
                            hasMore = page.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            isRefreshing = false
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, isLoadingMore = false, isRefreshing = false, errorMessage = err.message)
                    }
                }
        }
    }

    private fun loadFilteredFeed(reset: Boolean) {
        viewModelScope.launch {
            val filters = _uiState.value.selectedFilters.values.toList()
            if (filters.isEmpty()) {
                loadFeed(_uiState.value.activeTabId, reset = true)
                return@launch
            }
            val cursor = if (reset) "" else _uiState.value.nextCursor
            _uiState.update { it.copy(isLoading = reset, isLoadingMore = !reset, errorMessage = null) }
            runCatching { activeProvider().getFilteredFeed(filters, cursor) }
                .onSuccess { page ->
                    _uiState.update { state ->
                        val merged = if (reset) page.items else (state.items + page.items).distinctBy { "${it.providerId}:${it.mediaId}" }
                        state.copy(
                            items = merged,
                            nextCursor = page.nextCursor,
                            hasMore = page.hasMore,
                            isLoading = false,
                            isLoadingMore = false
                        )
                    }
                }.onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, isLoadingMore = false, errorMessage = err.message) }
                }
        }
    }

    private fun loadForYou(reset: Boolean) {
        viewModelScope.launch {
            val cursor = if (reset) "" else _uiState.value.forYouCursor
            val tabId = _uiState.value.activeForYouTabId
            _uiState.update { it.copy(forYouLoading = true) }
            runCatching { activeProvider().getForYouFeed(tabId, cursor) }
                .onSuccess { page ->
                    _uiState.update { state ->
                        val merged = if (reset) page.items else (state.forYouItems + page.items).distinctBy { "${it.providerId}:${it.mediaId}" }
                        state.copy(
                            forYouItems = merged,
                            forYouCursor = page.nextCursor,
                            forYouHasMore = page.hasMore,
                            forYouLoading = false
                        )
                    }
                }.onFailure {
                    _uiState.update { it.copy(forYouLoading = false) }
                }
        }
    }

    private fun runSearch() {
        val q = _uiState.value.searchQuery.trim()
        if (q.isEmpty()) {
            loadFeed(_uiState.value.activeTabId, reset = true)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { activeProvider().search(q, "") }
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            items = page.items,
                            nextCursor = page.nextCursor,
                            hasMore = page.hasMore,
                            isLoading = false
                        )
                    }
                }.onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
        }
    }
}
