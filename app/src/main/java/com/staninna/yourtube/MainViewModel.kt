package com.staninna.yourtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.ServerInput
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedRules
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.page.PageIdentity
import com.staninna.yourtube.page.starItem
import com.staninna.yourtube.playback.PlaybackHub
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.web.BridgeReply
import com.staninna.yourtube.web.PageListener
import com.staninna.yourtube.web.PageReport
import com.staninna.yourtube.web.PlaybackEvent
import com.staninna.yourtube.web.PlayerCommand
import com.staninna.yourtube.web.SegmentSkipped
import com.staninna.yourtube.web.SegmentsReply
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
import java.io.InputStream

enum class Tab { WATCH, FEEDS, LIBRARY, HISTORY }

class MainViewModel(
    private val store: Store,
    private val channels: ChannelResolver,
    feedFetcher: FeedFetcher,
    segments: SegmentSource,
    private val playback: PlaybackHub,
) : ViewModel(), PageListener {

    val history = store.history
    val settings = store.settings

    /** Store files that were unreadable at startup, until the user answers the notice about them. */
    val corruptNotice: StateFlow<Set<String>?> = store.corruptNotice

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

    /** The page's last playback event, while the URL still shows its video. */
    private val lastPlayback = MutableStateFlow<PlaybackEvent?>(null)

    /** The video loaded in Watch, for the now-playing bar on the native tabs and the media session. */
    val nowPlaying: StateFlow<NowPlaying?> = combine(page, store.history, lastPlayback, NowPlaying::of)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val playing: StateFlow<Boolean> = nowPlaying.map { it?.playing == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Watch progress per video id, for the bars under thumbnails. */
    val progress: StateFlow<Map<String, WatchProgress>> = store.history
        .map { history -> history.mapNotNull { e -> WatchProgress.of(e)?.let { e.videoId to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _events = Channel<UiEvent>(Channel.BUFFERED)

    /** One-off notices for the snackbar. */
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    /** Starring, tags and Library's view. */
    val library = LibraryModel(store, channels, viewModelScope, starTarget, emit = { _events.trySend(it) })

    /** Feeds: subscribed channels' uploads, chips and paging. */
    val feeds = FeedsModel(store, feedFetcher, viewModelScope)

    /** Picture-in-picture: mode, support, and Watch's menu entry. */
    val pip = PipModel(store, playback, playing, viewModelScope)

    /** SponsorBlock segments for the page, and skips with Undo. */
    private val sponsor = SponsorModel(store, segments, viewModelScope, emit = { _events.trySend(it) })

    /** Messages for the page that it did not ask for; the WebView host delivers them. */
    val pagePushes: Flow<BridgeReply> = sponsor.pagePushes

    private val backups = BackupTransfer(store)

    private val opens = Channel<String>(Channel.BUFFERED)
    private var openedAnything = false

    /** URLs the WebView should load, in order. */
    val openRequests: Flow<String> = opens.receiveAsFlow()

    init {
        viewModelScope.launch { nowPlaying.collect(playback::publish) }
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

    /** Opens YouTube's results for [query] in Watch. Returns false, and does nothing, for a blank query. */
    fun search(query: String): Boolean {
        val url = YouTube.searchUrl(query) ?: return false
        open(url)
        return true
    }

    // --- PageListener: called on the main thread with decoded bridge messages ---

    override fun onNavigated(url: String) {
        currentUrl.value = url
        // Leaving a video takes its player down without a pause event; the next video reports its own.
        val videoId = (PageIdentity.fromUrl(url) as? PageIdentity.Video)?.videoId
        if (lastPlayback.value?.videoId != videoId) lastPlayback.value = null
    }

    override fun onPageReport(report: PageReport) {
        lastReport.value = report
        if (PageIdentity.sameUrl(report.url, currentUrl.value)) {
            PageIdentity.drift(report)?.let { Diagnostics.count(it, report.url) }
        }
    }

    override fun onPlayback(event: PlaybackEvent) {
        lastPlayback.value = event
        val meta = (page.value as? PageIdentity.Video)?.meta?.takeIf { it.videoId == event.videoId }
        store.recordPlayback(event.kind, event.videoId, event.position, event.duration, meta)
    }

    override fun resumePosition(videoId: String): Double? = store.resumePosition(videoId)

    override fun requestSegments(videoId: String, reply: (SegmentsReply) -> Unit) = sponsor.request(videoId, reply)

    override fun onSegmentSkipped(skip: SegmentSkipped) = sponsor.onSkipped(skip)

    override fun onAdLeak(url: String) = Diagnostics.count("ad.leak", url)

    /** Plays or pauses the video in Watch, the way the media session does. */
    fun playPause() = playback.send(if (playing.value) PlayerCommand.Pause else PlayerCommand.Play)

    // --- history ---

    /** Clears history and resume positions, offering Undo. */
    fun clearHistory() {
        val cleared = history.value
        if (cleared.isEmpty()) return
        store.clearHistory()
        _events.trySend(UiEvent.HistoryCleared(cleared))
    }

    /** Removes one video and its resume position, offering Undo. */
    fun removeHistory(videoId: String) {
        val entry = history.value.firstOrNull { it.videoId == videoId } ?: return
        store.removeHistory(videoId)
        _events.trySend(UiEvent.HistoryRemoved(entry))
    }

    fun undo(event: UiEvent.Undoable) = when (event) {
        is UiEvent.HistoryCleared -> store.restoreHistory(event.entries)
        is UiEvent.HistoryRemoved -> store.restoreHistory(listOf(event.entry))
        is UiEvent.Unstarred -> library.undoUnstar(event)
        is UiEvent.SegmentSkipped -> sponsor.undo(event)
    }

    fun dismissCorruptNotice() = store.dismissCorruptNotice()

    // --- backup: the UI owns the files, BackupTransfer the content ---

    fun exportBackup(write: suspend (ByteArray) -> Unit) {
        viewModelScope.launch { _events.send(backups.export(write)) }
    }

    fun importBackup(open: suspend () -> InputStream) {
        viewModelScope.launch { _events.send(backups.import(open)) }
    }

    private suspend fun resolve(page: PageIdentity.Channel) {
        if (channels.resolve(page.path) == null) Diagnostics.count("channel.unresolved", page.path)
    }

    // --- settings ---

    fun setRemoveShorts(enabled: Boolean) = store.updateSettings { it.copy(removeShorts = enabled) }
    fun setDynamicColor(enabled: Boolean) = store.updateSettings { it.copy(dynamicColor = enabled) }
    fun setAdLayer(layer: AdLayer, on: Boolean) = store.updateSettings { it.copy(adBlocking = it.adBlocking.with(layer, on)) }
    fun setSponsorBlock(enabled: Boolean) = store.updateSettings { it.copy(sponsorBlock = it.sponsorBlock.copy(enabled = enabled)) }
    fun setSegmentAction(category: SegmentCategory, action: SegmentAction) =
        store.updateSettings { it.copy(sponsorBlock = it.sponsorBlock.with(category, action)) }

    fun setSponsorServer(server: ServerInput.Valid) =
        store.updateSettings { it.copy(sponsorBlock = it.sponsorBlock.copy(server = server.url)) }

    companion object {
        /** A cold launch opens Feeds, or Library when there is nothing to feed from. */
        fun startTab(library: Library): Tab = if (FeedRules.subscriptions(library).isEmpty()) Tab.LIBRARY else Tab.FEEDS
    }
}
