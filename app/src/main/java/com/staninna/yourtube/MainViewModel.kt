package com.staninna.yourtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.feed.FeedClient
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.page.PageIdentity
import com.staninna.yourtube.page.starItem
import com.staninna.yourtube.web.PageListener
import com.staninna.yourtube.web.PageReport
import com.staninna.yourtube.web.PlaybackEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Tab { WATCH, LIBRARY, HISTORY, FEEDS }

class MainViewModel(
    private val store: Store,
    private val channels: ChannelResolver,
) : ViewModel(), PageListener {

    val library = store.library
    val history = store.history
    val settings = store.settings

    private val _tab = MutableStateFlow(startTab(store.library.value))
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val currentUrl = MutableStateFlow<String?>(null)
    private val lastReport = MutableStateFlow<PageReport?>(null)

    /** What the WebView shows, re-derived whenever the URL or the page's facts change. */
    val page: StateFlow<PageIdentity> = combine(currentUrl, lastReport) { url, report ->
        if (url == null) PageIdentity.Other else PageIdentity.derive(url, report)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PageIdentity.Other)

    /** What the star button would add or remove, or null while the page is not identified yet. */
    val starTarget: StateFlow<StarredItem?> = combine(page, store.channels) { page, cache ->
        starItem(page, (page as? PageIdentity.Channel)?.let { cache[it.path]?.info })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val opens = Channel<String>(Channel.BUFFERED)
    private var openedAnything = false

    /** URLs the WebView should load, in order. */
    val openRequests: Flow<String> = opens.receiveAsFlow()

    private val _feeds = MutableStateFlow<List<FeedVideo>>(emptyList())
    val feeds: StateFlow<List<FeedVideo>> = _feeds.asStateFlow()

    private val _feedsLoading = MutableStateFlow(false)
    val feedsLoading: StateFlow<Boolean> = _feedsLoading.asStateFlow()

    init {
        // A video can start playing before the player confirms its title; fill it in once it does.
        viewModelScope.launch {
            page.collect { (it as? PageIdentity.Video)?.meta?.let(store::annotateHistory) }
        }
        // Each channel page is resolved once per visit, not once per report.
        viewModelScope.launch {
            page.map { it as? PageIdentity.Channel }.distinctUntilChangedBy { it?.path }.filterNotNull()
                .collect { launch { resolve(it) } }
        }
    }

    /** Switches tabs. The Watch tab with nothing opened yet shows YouTube's home. */
    fun selectTab(t: Tab) {
        if (t == Tab.WATCH && !openedAnything) open(YouTube.HOME) else _tab.value = t
    }

    fun open(url: String) {
        openedAnything = true
        _tab.value = Tab.WATCH
        opens.trySend(url)
    }

    // --- PageListener: called on the main thread with decoded bridge messages ---

    override fun onNavigated(url: String) {
        currentUrl.value = url
    }

    override fun onPageReport(report: PageReport) {
        lastReport.value = report
        if (PageIdentity.sameUrl(report.url, currentUrl.value)) {
            PageIdentity.drift(report)?.let { Diagnostics.count(it, report.url) }
        }
    }

    override fun onPlayback(event: PlaybackEvent) {
        when (event.kind) {
            PlaybackKind.PLAY -> _playing.value = true
            PlaybackKind.PAUSE, PlaybackKind.ENDED -> _playing.value = false
            PlaybackKind.TIME, PlaybackKind.METADATA -> Unit
        }
        val meta = (page.value as? PageIdentity.Video)?.meta?.takeIf { it.videoId == event.videoId }
        store.recordPlayback(event.kind, event.videoId, event.position, event.duration, meta)
    }

    override fun resumePosition(videoId: String): Double? = store.resumePosition(videoId)

    // --- library ---

    fun toggleStarCurrent() {
        starTarget.value?.let(store::toggleStar)
    }

    fun toggleStar(item: StarredItem) = store.toggleStar(item)

    fun clearHistory() = store.clearHistory()

    private suspend fun resolve(page: PageIdentity.Channel) {
        if (channels.resolve(page.path) == null) Diagnostics.count("channel.unresolved", page.path)
    }

    // --- feeds ---

    fun refreshFeeds() {
        val channelIds = library.value.items.filter { it.type == ItemType.CHANNEL }.mapNotNull { it.channelId }
        if (channelIds.isEmpty()) {
            _feeds.value = emptyList()
            return
        }
        viewModelScope.launch {
            _feedsLoading.value = true
            try {
                _feeds.value = FeedClient.latest(channelIds)
            } finally {
                _feedsLoading.value = false
            }
        }
    }

    // Stage 3's Settings skeleton binds these.
    fun setHideShorts(enabled: Boolean) = store.updateSettings { it.copy(hideShorts = enabled) }
    fun setDynamicColor(enabled: Boolean) = store.updateSettings { it.copy(dynamicColor = enabled) }

    companion object {
        /** A cold launch opens Feeds, or Library when there is nothing to feed from. */
        fun startTab(library: Library): Tab =
            if (library.items.any { it.type == ItemType.CHANNEL }) Tab.FEEDS else Tab.LIBRARY
    }
}
