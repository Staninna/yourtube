package com.staninna.tube

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.tube.ui.TubeRoot
import com.staninna.tube.ui.TubeTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private lateinit var webView: WebView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenBack: OnBackPressedCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebViewProbe.run(this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = userAgentString.replace("; wv", "")
            }
            addJavascriptInterface(Bridge(), "TubeBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(injectedScript, null)
                    view.evaluateJavascript(
                        "window.__tubeApplySettings && window.__tubeApplySettings('{\"hideShorts\":${vm.settings.value.hideShorts}}')",
                        null,
                    )
                }
            }
            webChromeClient = FullscreenChromeClient()
        }

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            TubeTheme(dynamicColor = settings.dynamicColor) {
                TubeRoot(vm = vm, webView = webView)
            }
        }

        if (savedInstanceState == null) webView.loadUrl(START_URL) else webView.restoreState(savedInstanceState)
    }

    private inner class Bridge {
        @JavascriptInterface fun setPlaying(isPlaying: Boolean) = vm.setPlaying(isPlaying)
        @JavascriptInterface fun pageInfo(json: String) = vm.onPageInfo(json)
        @JavascriptInterface fun watch(json: String) = vm.onWatch(json)
    }

    private inner class FullscreenChromeClient : WebChromeClient() {
        private val content: ViewGroup get() = findViewById(android.R.id.content)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view.apply { setBackgroundColor(Color.BLACK) }
            customViewCallback = callback
            content.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            fullscreenBack = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = onHideCustomView()
            }.also { onBackPressedDispatcher.addCallback(this@MainActivity, it) }
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            content.removeView(view)
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            fullscreenBack?.remove()
            fullscreenBack = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Keep audio going when backgrounded: don't pause the WebView, and hold the process with a
        // foreground service while something is actually playing.
        if (vm.playing.value && !isChangingConfigurations) {
            ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        stopService(Intent(this, PlaybackService::class.java))
    }

    override fun onDestroy() {
        stopService(Intent(this, PlaybackService::class.java))
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private val injectedScript: String by lazy {
        assets.open("inject.js").bufferedReader().use { it.readText() }
    }

    companion object {
        private const val START_URL = "https://m.youtube.com"
    }
}
