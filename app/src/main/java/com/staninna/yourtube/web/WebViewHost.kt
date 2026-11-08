package com.staninna.yourtube.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.Locale

/** Observable navigation state of the current WebView. */
data class NavState(
    /** The main-frame URL, including SPA navigations. Null until something loads. */
    val url: String? = null,
    val canGoBack: Boolean = false,
    /** Load progress, 0 to 100. */
    val progress: Int = 100,
    val error: LoadError? = null,
)

/**
 * A main-frame load that failed. [description] is Chromium's error name, such as `net::ERR_TIMED_OUT`;
 * [offline] says the device could not reach the network at all, as opposed to YouTube failing.
 */
data class LoadError(val url: String, val description: String, val offline: Boolean) {
    companion object {
        /**
         * Whether a `WebViewClient.ERROR_*` code means no connection. WebView reports a disconnected
         * device and a failed DNS lookup as [WebViewClient.ERROR_HOST_LOOKUP], and an unreachable
         * address as [WebViewClient.ERROR_CONNECT].
         */
        fun isOffline(errorCode: Int): Boolean =
            errorCode == WebViewClient.ERROR_HOST_LOOKUP || errorCode == WebViewClient.ERROR_CONNECT
    }
}

/** The app's WebView: it carries the identity it was bound to, so its release can clear it. */
class TubeWebView internal constructor(context: Context, internal val identity: Identity) : WebView(context) {
    private var windowVisibility = VISIBLE

    /**
     * While true, the WebView tells Chromium its window is visible whatever it really is. Chromium
     * pauses media once the window turns invisible (Home, screen off; stage 0), so this is what lets a
     * video play on in the background.
     */
    internal var keepPlaying = false
        set(value) {
            if (field == value) return
            field = value
            super.onWindowVisibilityChanged(reportedVisibility())
        }

    override fun onWindowVisibilityChanged(visibility: Int) {
        windowVisibility = visibility
        super.onWindowVisibilityChanged(reportedVisibility())
    }

    private fun reportedVisibility() = if (keepPlaying) VISIBLE else windowVisibility
}

/**
 * Owns the WebView and everything bound to it, for exactly as long as its Activity lives: identity
 * and profile binding, settings, the message bridge, document-start scripts, the navigation policy,
 * the network layer of ad blocking, the user agent, navigation state, fullscreen, the video's place
 * for picture-in-picture, render-process recovery and the user's new identities.
 *
 * Compose renders the WebView keyed on [generation] (see `WebViewSlot`), calling [create] and
 * [release], so creation and destruction follow composition. A new identity means a new generation
 * and so a new WebView. [generation] is null while the next identity is being prepared.
 */
class WebViewHost(
    private val activity: ComponentActivity,
    private val settings: StateFlow<Settings>,
    private val listener: PageListener,
    /** Called when this device can only get a new identity by restarting the process. */
    private val onRestartNeeded: (url: String?) -> Unit,
    private val strategy: IdentityStrategy = IdentityStrategy.process,
) {
    private val _nav = MutableStateFlow(NavState())
    val nav: StateFlow<NavState> = _nav.asStateFlow()

    private val _generation = MutableStateFlow<Int?>(null)
    val generation: StateFlow<Int?> = _generation.asStateFlow()

    private val _videoBox = MutableStateFlow<VideoBox?>(null)

    /** Where the current page's main video last showed; null until it reported. */
    val videoBox: StateFlow<VideoBox?> = _videoBox.asStateFlow()

    /** Whether the page shows only the video; its box then is the PiP window's and says nothing. */
    private var filling = false

    private val bridge = Bridge(listener, isCurrent = { it === webView }, onVideoBox = { if (!filling) _videoBox.value = it })
    private val scripts = PageScripts(activity.assets)
    private val chrome = FullscreenChromeClient(activity, current = { webView }) { view, progress ->
        if (view === webView) _nav.update { it.copy(progress = progress) }
    }

    /** Whether the page's fullscreen video covers the Activity. */
    val fullscreen: StateFlow<Boolean> = chrome.showing

    private var generations = 0
    private var preparing: Job? = null

    /** The identity the next [create] binds; null while one is being prepared. */
    private var identity: Identity? = null
    private var webView: TubeWebView? = null
    private var pendingUrl: String? = null
    private var requestedUrl: String? = null

    /**
     * The document [load] asked for drops the history before it once it commits. Until then the page
     * reports no history, so Back during the load never walks the old one.
     */
    private var dropHistoryOnCommit = false
    private var appliedConfig: String = PageConfig.of(settings.value).toString()

    /** The theme the current identity's `PREF` cookie carries: seeded, then kept in step by [syncTheme]. */
    private var syncedDark = false

    private var keepPlaying = false

    /** Creates the WebView for the current generation. Only the Compose factory calls this. */
    fun create(context: Context): TubeWebView {
        val identity = checkNotNull(identity) { "create() needs a ready identity" }
        val view = TubeWebView(context, identity)
        identity.bind(view) // must be the first call on the new WebView
        // Explicit, not wrap_content: a WebView laid out to wrap its content gets a 0 px tall layout
        // viewport, so `100vh` is 0 and YouTube takes the page for landscape (a short, letterboxed
        // player and misplaced panels).
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        configure(view)
        view.keepPlaying = keepPlaying
        webView = view
        // The theme may have changed while this identity was being prepared.
        syncTheme(view, playing = false)
        pendingUrl?.let {
            pendingUrl = null
            view.loadUrl(it)
        }
        return view
    }

    /** Destroys a WebView Compose no longer shows, then clears the identity it used. */
    fun release(view: TubeWebView) {
        if (view === webView) forgetCurrentView()
        chrome.exit()
        view.destroy()
        strategy.clear(view.identity)
        if (identity === view.identity) identity = null
    }

    /**
     * Loads [url], or the watch page in its place when it is a Short and Shorts are removed. With
     * [freshHistory] the page becomes the first entry of the history once it commits, so Back from it
     * has nothing to go back to.
     */
    fun load(url: String, freshHistory: Boolean = false) {
        val target = rewritten(url)
        requestedUrl = target
        dropHistoryOnCommit = freshHistory
        _nav.update { it.copy(error = null, canGoBack = it.canGoBack && !freshHistory) }
        webView?.loadUrl(target) ?: run { pendingUrl = target }
    }

    /** Sends [reply] to the page showing now, unasked (SponsorBlock segments after a settings change, Undo). */
    fun post(reply: BridgeReply) {
        webView?.let { bridge.push(it, reply) }
    }

    fun goBack() {
        webView?.goBack()
    }

    /** Lets the page play on while the app is in the background, or not; see [TubeWebView.keepPlaying]. */
    fun keepPlaying(on: Boolean) {
        keepPlaying = on
        webView?.keepPlaying = on
    }

    /**
     * Shows only the video, filling the WebView, or the page again: picture-in-picture. Chromium's own
     * fullscreen view needs none of it.
     */
    fun fillWithVideo(on: Boolean) {
        filling = on
        webView?.evaluateJavascript("window.__yourtubeFill && window.__yourtubeFill($on)", null)
    }

    /** The WebView's place in its window, in pixels; null without a WebView. */
    fun boundsInWindow(): Rect? {
        val view = webView ?: return null
        val at = IntArray(2).also(view::getLocationInWindow)
        return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    /** Loads the failed URL again after a main-frame error. */
    fun retry() {
        val failed = nav.value.error?.url ?: return
        load(failed)
    }

    /**
     * Follows the system theme after a configuration change. A WebView that does not exist yet
     * catches up in [create].
     */
    fun syncTheme(playing: Boolean) {
        webView?.let { syncTheme(it, playing) }
    }

    /**
     * Sets [view]'s background to YouTube's for the current theme, and brings the identity's `PREF`
     * theme flag along when it differs from [syncedDark]; [ThemeSync] decides whether to reload.
     */
    private fun syncTheme(view: TubeWebView, playing: Boolean) {
        val dark = isDark()
        // YouTube's own background, so a load never flashes another color.
        view.setBackgroundColor(YouTube.pageBackground(dark))
        val action = ThemeSync.decide(syncedDark, dark, pageLoaded = view.url != null, playing = playing)
        if (action == ThemeSync.NONE) return
        syncedDark = dark
        val cookies = view.identity.cookies
        val pref = ThemePref.withTheme(ThemePref.read(cookies.getCookie(YouTube.HOME)), dark)
        cookies.setCookie(YouTube.HOME, IdentitySeed.cookie("PREF", pref)) {
            if (action == ThemeSync.REWRITE_AND_RELOAD && view === webView) view.reload()
        }
    }

    /** The URL to bring back after process death or a new identity. */
    val restorableUrl: String? get() = nav.value.url ?: requestedUrl

    /**
     * A new identity on the user's request. The page's player choices go to the listener first, then
     * the page reloads under the new identity; an interstitial gives way to the page it stood in front of.
     */
    fun reroll() {
        // The video resumes where it is, not at a `t=` it was opened with.
        val url = restorableUrl?.let { rewritten(YouTube.withoutStartTime(Interstitials.reloadUrl(it))) }
        snapshotPlayerPrefs { newIdentity(url) }
    }

    /**
     * Reads the page's player choices into [PageListener.onPlayerPrefs], then runs [then]. Without a
     * YouTube page there is nothing to read and [then] runs at once.
     */
    fun snapshotPlayerPrefs(then: () -> Unit = {}) {
        val view = webView?.takeIf { originOf(it.url) in YouTube.ORIGINS } ?: return then()
        view.evaluateJavascript(PlayerPrefsStorage.READ_SCRIPT) { answer ->
            listener.onPlayerPrefs(PlayerPrefsStorage.read(answer))
            then()
        }
    }

    /**
     * Replaces the WebView with a new one under a fresh identity and loads [url] in it. The old
     * identity is cleared when Compose releases its WebView.
     */
    fun newIdentity(url: String?) {
        preparing?.cancel()
        // An identity that was prepared but never got a WebView has no release to clear it.
        identity?.takeIf { webView == null }?.let(strategy::clear)
        identity = null
        forgetCurrentView()
        _generation.value = null // Compose releases the current WebView
        _nav.value = NavState()
        if (url != null) {
            pendingUrl = url
            requestedUrl = url
        }
        preparing = activity.lifecycleScope.launch {
            val next = strategy.create()
            if (next == null) {
                onRestartNeeded(url)
                return@launch
            }
            var seeded = false
            try {
                val seed = seed()
                next.seed(seed)
                syncedDark = seed.dark
                seeded = true
            } finally {
                // Superseded or cancelled mid-seed: nothing will ever bind this identity.
                if (!seeded) strategy.clear(next)
            }
            identity = next
            Log.i(TAG, "new identity ${next.profileName ?: "default"}")
            _generation.value = ++generations
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Subframes (players, ads) load wherever they point; only main-frame navigation is policed.
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            return when (val decision = NavigationPolicy.decide(url, settings.value.removeShorts)) {
                Navigation.InApp -> false
                Navigation.External -> true.also { openExternally(url) }
                is Navigation.Rewrite -> true.also { view.loadUrl(decision.url) }
            }
        }

        // Runs on a WebView thread; settings.value is safe to read there.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // A main frame never loads an ad server: the navigation policy sends such links out first.
            if (request.isForMainFrame || !settings.value.adBlocking.isOn(AdLayer.NETWORK)) return null
            val url = request.url.toString()
            if (!AdBlocklist.blocks(url)) return null
            Log.d(TAG_ADS, "blocked ${url.take(160)}")
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (view !== webView) return
            _nav.update { it.copy(url = url, canGoBack = canGoBack(view)) }
            listener.onNavigated(url)
        }

        // Called when a new document commits, just before doUpdateVisitedHistory; never for an in-page
        // (pushState) navigation, so the old page's own history updates cannot take the fresh start.
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== webView) return
            if (dropHistoryOnCommit) {
                dropHistoryOnCommit = false
                view.clearHistory()
            }
            _nav.update { it.copy(error = null) }
            scripts.onPageStarted(view, settings.value)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== webView) return
            _nav.update { it.copy(canGoBack = canGoBack(view)) }
            scripts.onPageFinished(view, settings.value)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== webView || !request.isForMainFrame) return
            val failed = LoadError(request.url.toString(), error.description.toString(), LoadError.isOffline(error.errorCode))
            _nav.update { it.copy(error = failed) }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone (crashed=${detail.didCrash()}), recovering under a new identity")
            if (view === webView) newIdentity(restorableUrl)
            return true // the app survives; Compose releases the dead WebView
        }
    }

    // After every property above, including the client: this starts preparing the first identity.
    init {
        activity.lifecycleScope.launch { settings.collect(::applySettings) }
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) abandon() })
        newIdentity(url = null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            val major = UserAgent.majorVersion(userAgentString)
            // Without a Chrome version WebView's own UA stays; it is a WebView UA all the same.
            if (major != null) {
                userAgentString = UserAgent.of(major)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                    WebSettingsCompat.setUserAgentMetadata(this, UserAgent.metadata(major, WebSettingsCompat.getUserAgentMetadata(this)))
                }
            }
        }
        view.webViewClient = client
        view.webChromeClient = chrome
        bridge.install(view)
        scripts.registerFixed(view, listener.playerPrefs(), System.currentTimeMillis())
        scripts.register(view, settings.value)
    }

    private fun openExternally(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "no app opens $url")
        }
    }

    /** Whether Back has a page to go back to; not while a fresh load is still to commit. */
    private fun canGoBack(view: WebView): Boolean = view.canGoBack() && !dropHistoryOnCommit

    private fun forgetCurrentView() {
        webView = null
        _videoBox.value = null
        scripts.forget()
    }

    /**
     * Brings the live page in line with [settings]: future documents get the new scripts, the current one
     * its new config (CSS at once; the prune, Shorts links and the safety net from their next use), and
     * a Short on screen when Shorts get removed turns into its watch page. The network layer reads the
     * settings per request.
     */
    private fun applySettings(settings: Settings) {
        val config = PageConfig.of(settings).toString()
        if (config == appliedConfig) return
        appliedConfig = config
        val view = webView ?: return
        scripts.register(view, settings)
        scripts.applyToCurrentPage(view, settings)
        val watch = view.url?.takeIf { settings.removeShorts }?.let(NavigationPolicy::shortAsWatch) ?: return
        view.loadUrl(watch)
    }

    /** [url], or its watch page when it is a Short and Shorts are removed. */
    private fun rewritten(url: String): String =
        (NavigationPolicy.decide(url, settings.value.removeShorts) as? Navigation.Rewrite)?.url ?: url

    /**
     * The Activity is gone. An identity that never got its WebView has no release to clear it, and a
     * strategy that waits for the last identity's wipe would wait for it forever.
     */
    private fun abandon() {
        preparing?.cancel()
        identity?.takeIf { webView == null }?.let(strategy::clear)
        identity = null
    }

    private fun isDark(): Boolean =
        activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun seed(): IdentitySeed {
        // An empty locale list does not happen on a real device; the JVM default is the same answer.
        val locale = ConfigurationCompat.getLocales(activity.resources.configuration)[0] ?: Locale.getDefault()
        return IdentitySeed(isDark(), locale)
    }

    private companion object {
        const val TAG = "YT-HOST"
        const val TAG_ADS = "YT-ADS"
    }
}
