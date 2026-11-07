package com.staninna.yourtube.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.lifecycleScope
import com.staninna.yourtube.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** A main-frame load that failed. */
data class LoadError(val url: String, val description: String)

/** The app's WebView: it carries the identity it was bound to, so its release can clear it. */
class TubeWebView internal constructor(context: Context, internal val identity: Identity) : WebView(context)

/**
 * Owns the WebView and everything bound to it, for exactly as long as its Activity lives: identity
 * and profile binding, settings, the message bridge, document-start scripts, the navigation policy,
 * navigation state, fullscreen, and render-process recovery.
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

    private val bridge = Bridge(listener, isCurrent = { it === webView })
    private val scripts = PageScripts(activity.assets)
    private val chrome = FullscreenChromeClient(activity) { view, progress ->
        if (view === webView) _nav.update { it.copy(progress = progress) }
    }

    private var generations = 0
    private var preparing: Job? = null

    /** The identity the next [create] binds; null while one is being prepared. */
    private var identity: Identity? = null
    private var webView: TubeWebView? = null
    private var pendingUrl: String? = null
    private var requestedUrl: String? = null
    private var appliedCss: String = PageScripts.css(settings.value)

    /** Creates the WebView for the current generation. Only the Compose factory calls this. */
    fun create(context: Context): TubeWebView {
        val identity = checkNotNull(identity) { "create() needs a ready identity" }
        val view = TubeWebView(context, identity)
        identity.bind(view) // must be the first call on the new WebView
        configure(view)
        webView = view
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

    fun load(url: String) {
        requestedUrl = url
        _nav.update { it.copy(error = null) }
        webView?.loadUrl(url) ?: run { pendingUrl = url }
    }

    fun goBack() {
        webView?.goBack()
    }

    /** The URL to bring back after process death or a new identity. */
    val restorableUrl: String? get() = nav.value.url ?: requestedUrl

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
                next.seed(seed())
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
            if (NavigationPolicy.opensInApp(url)) return false
            openExternally(url)
            return true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (view !== webView) return
            _nav.update { it.copy(url = url, canGoBack = view.canGoBack()) }
            listener.onNavigated(url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== webView) return
            _nav.update { it.copy(error = null) }
            scripts.onPageStarted(view, settings.value)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== webView) return
            _nav.update { it.copy(canGoBack = view.canGoBack()) }
            scripts.onPageFinished(view, settings.value)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== webView || !request.isForMainFrame) return
            _nav.update { it.copy(error = LoadError(request.url.toString(), error.description.toString())) }
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
        newIdentity(url = null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.setBackgroundColor(Color.BLACK)
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // Drop the WebView marker but keep "Version/4.0": YouTube exempts that UA from consent.
            userAgentString = userAgentString.replace("; wv", "")
        }
        view.webViewClient = client
        view.webChromeClient = chrome
        bridge.install(view)
        scripts.register(view, settings.value)
    }

    private fun openExternally(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "no app opens $url")
        }
    }

    private fun forgetCurrentView() {
        webView = null
        scripts.forget()
    }

    private fun applySettings(settings: Settings) {
        val css = PageScripts.css(settings)
        if (css == appliedCss) return
        appliedCss = css
        val view = webView ?: return
        scripts.register(view, settings)
        scripts.applyToCurrentPage(view, settings)
    }

    private fun seed(): IdentitySeed {
        val config = activity.resources.configuration
        val dark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // An empty locale list does not happen on a real device; the JVM default is the same answer.
        return IdentitySeed(dark, ConfigurationCompat.getLocales(config)[0] ?: Locale.getDefault())
    }

    private companion object {
        const val TAG = "YT-HOST"
    }
}
