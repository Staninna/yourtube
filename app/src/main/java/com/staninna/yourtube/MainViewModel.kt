package com.staninna.yourtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.PlayerPrefs
import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.ServerInput
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.feed.FeedFetcher
import com.staninna.yourtube.feed.FeedRules
import com.staninna.yourtube.home.Dashboard
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.page.PageIdentity
import com.staninna.yourtube.page.starItem
import com.staninna.yourtube.playback.PlaybackHub
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.web.BridgeReply
import com.staninna.yourtube.web.Carried
import com.staninna.yourtube.web.Interstitial
import com.staninna.yourtube.web.Interstitials
import com.staninna.yourtube.web.PageListener
import com.staninna.yourtube.web.PageReport
import com.staninna.yourtube.web.PlaybackEvent
import com.staninna.yourtube.web.PlayerCommand
import com.staninna.yourtube.web.ResumeReply
import com.staninna.yourtube.web.SegmentSkipped
import com.staninna.yourtube.web.SegmentsReply
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    /**
     * The tab, whether Watch shows its home over the page, whether the page came as a link from another
     * app ([fromLink]: Back at its first entry returns there), and the overlay over all of it: one
     * value, so no reader sees half a switch. Anything that opens a page closes the overlay with it.
     */
    private data class Shell(val tab: Tab, val home: Boolean, val fromLink: Boolean = false, val overlay: Overlay = Overlay.NONE) {
        val screen: Screen get() = Screen.of(tab, home)
    }

    private val shell = MutableStateFlow(Shell(startTab(store.library.value), home = true))

    val tab: StateFlow<Tab> = shell.map { it.tab }.stateIn(viewModelScope, SharingStarted.Eagerly, shell.value.tab)

    /**
     * What the shell shows. Watch shows its home until something opens, and again after its Home button
     * or Back at the page's first entry; the page stays loaded under it.
     */
    val screen: StateFlow<Screen> = shell.map { it.screen }.stateIn(viewModelScope, SharingStarted.Eagerly, shell.value.screen)

    /**
     * Where Back goes now, from the shell as it is at this moment (not as last drawn): the page's
     * [canGoBack] and [pageLoaded] come from the WebView's live state. Null leaves the app.
     */
    fun backStep(canGoBack: Boolean, pageLoaded: Boolean): BackStep? =
        shell.value.let { it.screen.back(canGoBack, pageLoaded, it.fromLink) }

    /** Watch's page came as a link from another app and nothing else was opened since; see [Screen.back]. */
    val fromLink: StateFlow<Boolean> = shell.map { it.fromLink }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Settings or search over the whole shell, or neither. */
    val overlay: StateFlow<Overlay> = shell.map { it.overlay }.stateIn(viewModelScope, SharingStarted.Eagerly, Overlay.NONE)

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

    /** Video lengths the player reported, in seconds by video id, for the badges on Library's video rows. */
    val lengths: StateFlow<Map<String, Double>> = store.history
        .map { history -> history.mapNotNull { e -> e.length?.let { e.videoId to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _events = Channel<UiEvent>(Channel.BUFFERED)

    /** One-off notices for the snackbar. */
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    /** Feeds: subscribed channels' uploads, chips and paging. */
    val feeds = FeedsModel(store, feedFetcher, viewModelScope)

    /** Starring, tags and Library's view; Feeds' newest uploads order Subs by latest upload. */
    val library = LibraryModel(store, channels, viewModelScope, starTarget, emit = { _events.trySend(it) }, latestUploads = feeds.latestUploads)

    /** Watch's home rows, from history, Feeds' newest uploads and the library. */
    val dashboard: StateFlow<Dashboard> = combine(store.history, feeds.newest, store.library, Dashboard::of)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Dashboard.of(store.history.value, feeds.newest.value, store.library.value))

    /** Picture-in-picture: mode, support, and Watch's menu entry. */
    val pip = PipModel(store, playback, playing, viewModelScope)

    /** SponsorBlock segments for the page, and skips with Undo. */
    private val sponsor = SponsorModel(store, segments, viewModelScope, emit = { _events.trySend(it) })

    /** Messages for the page that it did not ask for; the WebView host delivers them. */
    val pagePushes: Flow<BridgeReply> = sponsor.pagePushes

    private val backups = BackupTransfer(store)

    private val opens = Channel<OpenRequest>(Channel.BUFFERED)

    /** URLs the WebView should load, in order. */
    val openRequests: Flow<OpenRequest> = opens.receiveAsFlow()

    private val identities = Channel<Unit>(Channel.CONFLATED)

    /** Each element asks the WebView host for a new identity. */
    val identityRequests: Flow<Unit> = identities.receiveAsFlow()

    /** The video playing when the user asked for a new identity: it picks up as it was (speed, pause). */
    private var carry: PlaybackEvent? = null

    /**
     * Whether the viewer muted the video, as last set by their own hand in this run. Every video starts
     * that way: a fresh identity would otherwise let YouTube start it muted.
     */
    private var viewerMuted = false

    /** The playing video the viewer took out of history, kept out while it plays on. */
    private val historyHold = HistoryHold()

    /** Whether Watch shows a bot check, which only a new identity may get past. */
    val botCheck: StateFlow<Boolean> = combine(currentUrl, lastReport) { url, report ->
        Interstitials.of(url) == Interstitial.BOT_CHECK ||
            (report != null && PageIdentity.sameUrl(report.url, url) && Interstitials.isBotCheck(report))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch { nowPlaying.collect(playback::publish) }
        // A video can start playing before the player confirms its title; fill it in once it does.
        viewModelScope.launch {
            page.collect { (it as? PageIdentity.Video)?.meta?.let(store::annotateHistory) }
        }
        viewModelScope.launch {
            botCheck.collect { if (it) Diagnostics.count("botcheck", currentUrl.value.orEmpty()) }
        }
        // Each channel page is resolved once per visit, not once per report.
        viewModelScope.launch {
            page.map { it as? PageIdentity.Channel }.distinctUntilChangedBy { it?.path }.filterNotNull()
                .collect { launch { resolve(it) } }
        }
        // Leaving Library ends its selection mode, however the tab changes (a tap, a link, back).
        viewModelScope.launch { tab.collect { if (it != Tab.LIBRARY) library.selection.clear() } }
    }

    /**
     * Switches tabs. Watch comes back as it was left, on its home or on its page; a link's page that was
     * left for another tab is the app's own from then on, so Back at its root goes to the home.
     */
    fun selectTab(t: Tab) = shell.update { it.copy(tab = t, fromLink = it.fromLink && t == it.tab) }

    /**
     * Loads [url] in Watch and shows it, closing Settings, search and the tag sheet (which belongs to the
     * page before). Over the home it starts a new page history, see [OpenRequest]. A link from another
     * app ([fromLink]) always does, so one Back at its first page returns to that app.
     */
    fun open(url: String, fromLink: Boolean = false) {
        val overHome = shell.getAndUpdate { Shell(Tab.WATCH, home = false, fromLink = fromLink) }.home
        library.closeTagSheet()
        historyHold.onOpen((PageIdentity.fromUrl(url) as? PageIdentity.Video)?.videoId)
        opens.trySend(OpenRequest(url, freshHistory = overHome || fromLink))
    }

    fun openSearch() = shell.update { it.copy(overlay = Overlay.SEARCH) }

    fun openSettings() = shell.update { it.copy(overlay = Overlay.SETTINGS) }

    fun closeOverlay() = shell.update { it.copy(overlay = Overlay.NONE) }

    /** Watch's home, over the page: from Watch's Home button, and Back at the page's first entry. */
    fun showHome() = shell.update { Shell(Tab.WATCH, home = true) }

    /** Watch's page again, as it was under the home: the now-playing bar's tap. */
    fun showPage() = shell.update { Shell(Tab.WATCH, home = false) }

    /** Library on its Videos segment: the home's Saved videos row's ›. */
    fun openSavedVideos() {
        library.selectSegment(ItemType.VIDEO)
        selectTab(Tab.LIBRARY)
    }

    /**
     * A new identity for Watch: YouTube sees a new anonymous viewer, the page reloads and a playing video
     * carries on where it was. Library, history and settings are untouched.
     */
    fun newIdentity() {
        carry = lastPlayback.value
        identities.trySend(Unit)
        _events.trySend(UiEvent.NewIdentity)
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
        if (Interstitials.of(url) == Interstitial.CONSENT) Diagnostics.count("consent.page", url)
        // Leaving a video takes its player down without a pause event; the next video reports its own.
        val videoId = (PageIdentity.fromUrl(url) as? PageIdentity.Video)?.videoId
        if (lastPlayback.value?.videoId != videoId) lastPlayback.value = null
        // The carry is for the video the new identity reloads, and no other.
        if (carry?.videoId != videoId) carry = null
        historyHold.onPage(videoId)
    }

    override fun onPageReport(report: PageReport) {
        lastReport.value = report
        if (PageIdentity.sameUrl(report.url, currentUrl.value)) {
            PageIdentity.drift(report)?.let { Diagnostics.count(it, report.url) }
        }
    }

    override fun onPlayback(event: PlaybackEvent) {
        lastPlayback.value = event
        if (!historyHold.records(event.videoId)) return
        val meta = (page.value as? PageIdentity.Video)?.meta?.takeIf { it.videoId == event.videoId }
        store.recordPlayback(event.kind, event.videoId, event.position, event.duration, meta)
    }

    override fun resume(videoId: String): ResumeReply {
        val carried = carry?.takeIf { it.videoId == videoId }?.let { Carried(it.rate, it.paused) }
        carry = null
        return ResumeReply(videoId, store.resumePosition(videoId), viewerMuted, carried)
    }

    override fun requestSegments(videoId: String, reply: (SegmentsReply) -> Unit) = sponsor.request(videoId, reply)

    override fun onSegmentSkipped(skip: SegmentSkipped) = sponsor.onSkipped(skip)

    override fun onAdLeak(url: String) = Diagnostics.count("ad.leak", url)

    override fun onViewerMute(muted: Boolean) {
        viewerMuted = muted
    }

    override fun onConsentRejected() = Diagnostics.count("consent.dialog", currentUrl.value.orEmpty())

    override fun playerPrefs(): PlayerPrefs = store.playerPrefs.value

    override fun onPlayerPrefs(prefs: PlayerPrefs) = store.setPlayerPrefs(prefs)

    /** Plays or pauses the video in Watch, the way the media session does. */
    fun playPause() = playback.send(if (playing.value) PlayerCommand.Pause else PlayerCommand.Play)

    // --- history ---

    /** Clears history and resume positions, offering Undo. A playing video stays out, see [HistoryHold]. */
    fun clearHistory() {
        val cleared = history.value
        if (cleared.isEmpty()) return
        historyHold.hold(nowPlaying.value?.takeIf { it.playing }?.videoId)
        store.clearHistory()
        _events.trySend(UiEvent.HistoryCleared(cleared))
    }

    /** Removes one video and its resume position, offering Undo. A playing video stays out, see [HistoryHold]. */
    fun removeHistory(videoId: String) {
        val entry = history.value.firstOrNull { it.videoId == videoId } ?: return
        if (nowPlaying.value?.takeIf { it.playing }?.videoId == videoId) historyHold.hold(videoId)
        store.removeHistory(videoId)
        _events.trySend(UiEvent.HistoryRemoved(entry))
    }

    fun undo(event: UiEvent.Undoable) = when (event) {
        is UiEvent.HistoryCleared -> restoreHistory(event.entries)
        is UiEvent.HistoryRemoved -> restoreHistory(listOf(event.entry))
        is UiEvent.Unstarred -> library.undoUnstar(event)
        is UiEvent.SegmentSkipped -> sponsor.undo(event)
    }

    private fun restoreHistory(entries: List<HistoryEntry>) {
        historyHold.onRestore(entries)
        store.restoreHistory(entries)
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
    fun setShowNewIdentity(shown: Boolean) = store.updateSettings { it.copy(showNewIdentity = shown) }
    fun setPlayerGestures(on: Boolean) = store.updateSettings { it.copy(playerGestures = on) }

    /** Turning it on starts the new-video check over, so the backlog never notifies. */
    fun setNewVideoNotifications(on: Boolean) = store.setNewVideoNotifications(on)
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
