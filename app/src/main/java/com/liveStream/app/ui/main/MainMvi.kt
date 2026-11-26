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
    val displayName: String
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
    val isRefreshing: Boolean = false,
    val searchQuery: String = "",
    val filterLadders: List<FilterLadder> = emptyList(),
    val selectedFilters: Map<String, FilterSelection> = emptyMap(),
    val showFilterSheet: Boolean = false,
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
        val summaries = all.map { ProviderSummary(it.id, it.displayName) }
        val firstId = summaries.firstOrNull()?.id.orEmpty()
        _uiState.update { it.copy(providers = summaries, activeProviderId = firstId) }
        if (firstId.isNotEmpty()) {
            loadProvider(firstId)
        }
    }

    fun onIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.SelectBottomTab -> _uiState.update { it.copy(bottomTab = intent.tab) }
            is MainIntent.SelectProvider -> if (intent.providerId != _uiState.value.activeProviderId) {
                loadProvider(intent.providerId)
            }
            is MainIntent.SelectCatalogTab -> loadFeed(tabId = intent.tabId, reset = true)
            is MainIntent.UpdateSearchQuery -> _uiState.update { it.copy(searchQuery = intent.query) }
            MainIntent.SubmitSearch -> runSearch()
            MainIntent.Refresh -> loadFeed(tabId = _uiState.value.activeTabId, reset = true, isRefresh = true)
            MainIntent.LoadNextPage -> if (_uiState.value.hasMore && !_uiState.value.isLoading) {
                loadFeed(tabId = _uiState.value.activeTabId, reset = false)
            }
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
                Triple(tabs, ladders, defaultTab to page)
            }.onSuccess { (tabs, ladders, feedPair) ->
                val (defaultTab, page) = feedPair
                _uiState.update {
                    it.copy(
                        tabs = tabs,
                        activeTabId = defaultTab,
                        filterLadders = ladders,
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

    private fun loadFeed(tabId: String, reset: Boolean, isRefresh: Boolean = false) {
        viewModelScope.launch {
            val cursor = if (reset) "" else _uiState.value.nextCursor
            _uiState.update {
                it.copy(
                    activeTabId = tabId,
                    isLoading = !isRefresh,
                    isRefreshing = isRefresh,
                    errorMessage = null
                )
            }
            runCatching { activeProvider().getFeed(tabId, cursor) }
                .onSuccess { page ->
                    _uiState.update { state ->
                        state.copy(
                            items = if (reset) page.items else state.items + page.items,
                            nextCursor = page.nextCursor,
                            hasMore = page.hasMore,
                            isLoading = false,
                            isRefreshing = false
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, errorMessage = err.message)
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
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { activeProvider().getFilteredFeed(filters, cursor) }
                .onSuccess { page ->
                    _uiState.update { state ->
                        state.copy(
                            items = if (reset) page.items else state.items + page.items,
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
