package com.staninna.tube

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.staninna.tube.data.FeedVideo
import com.staninna.tube.data.ItemType
import com.staninna.tube.data.PageInfo
import com.staninna.tube.data.Store
import com.staninna.tube.data.WatchReport
import com.staninna.tube.feed.FeedClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class Tab { WATCH, LIBRARY, HISTORY, FEEDS }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val json = Json { ignoreUnknownKeys = true }

    val library = Store.library
    val history = Store.history
    val settings = Store.settings

    private val _tab = MutableStateFlow(Tab.WATCH)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _page = MutableStateFlow(PageInfo())
    val page: StateFlow<PageInfo> = _page.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _nav = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val nav: SharedFlow<String> = _nav.asSharedFlow()

    private val _feeds = MutableStateFlow<List<FeedVideo>>(emptyList())
    val feeds: StateFlow<List<FeedVideo>> = _feeds.asStateFlow()

    private val _feedsLoading = MutableStateFlow(false)
    val feedsLoading: StateFlow<Boolean> = _feedsLoading.asStateFlow()

    fun selectTab(t: Tab) { _tab.value = t }

    fun open(url: String) {
        _tab.value = Tab.WATCH
        _nav.tryEmit(url)
    }

    // --- called from the WebView bridge (any thread) ---
    fun onPageInfo(payload: String) {
        runCatching { json.decodeFromString<PageInfo>(payload) }.getOrNull()?.let { _page.value = it }
    }

    fun onWatch(payload: String) {
        runCatching { json.decodeFromString<WatchReport>(payload) }.getOrNull()?.let {
            Store.recordWatch(it.toHistoryEntry())
        }
    }

    fun setPlaying(isPlaying: Boolean) { _playing.value = isPlaying }

    // --- library ---
    fun toggleStarCurrent() {
        val p = _page.value
        if (p.starrable && p.id.isNotBlank()) Store.toggleStar(p.toStarredItem())
    }

    fun channelIdsForFeeds(): List<String> =
        library.value.items.filter { it.type == ItemType.CHANNEL && it.channelId.isNotBlank() }
            .map { it.channelId }

    fun refreshFeeds() {
        val channels = channelIdsForFeeds()
        if (channels.isEmpty()) { _feeds.value = emptyList(); return }
        viewModelScope.launch {
            _feedsLoading.value = true
            _feeds.value = FeedClient.latest(channels)
            _feedsLoading.value = false
        }
    }

    fun setHideShorts(enabled: Boolean) = Store.updateSettings { it.copy(hideShorts = enabled) }
    fun setDynamicColor(enabled: Boolean) = Store.updateSettings { it.copy(dynamicColor = enabled) }
}
