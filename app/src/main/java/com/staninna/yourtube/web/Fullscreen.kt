package com.staninna.yourtube.web

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shows the page's fullscreen video over the Activity: system bars hidden until swiped in, the screen
 * turned to the video's shape, back leaving it. Leaving brings the bars and the orientation back. Also
 * forwards load progress, the other thing WebView reports through its chrome client.
 *
 * Until the screen has turned and Chromium has laid out the fullscreen video, its view shows the whole
 * page stretched over the screen; it stays transparent over a black backdrop until then.
 */
internal class FullscreenChromeClient(
    private val activity: ComponentActivity,
    /** The WebView showing now, whose video gives the shape. */
    private val current: () -> WebView?,
    private val onProgress: (WebView, Int) -> Unit,
) : WebChromeClient() {
    private var customView: View? = null
    private var backdrop: FrameLayout? = null
    private var customViewCallback: CustomViewCallback? = null
    private var backCallback: OnBackPressedCallback? = null
    private var orientationBefore = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private val content: ViewGroup get() = activity.findViewById(android.R.id.content)
    private val bars: WindowInsetsControllerCompat get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    private val _showing = MutableStateFlow(false)

    /** Whether the page's fullscreen video covers the Activity. */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(view, newProgress)

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        _showing.value = true
        view.alpha = 0f
        val backdrop = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, fill())
        }
        this.backdrop = backdrop
        content.addView(backdrop, fill())
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        orientationBefore = activity.requestedOrientation
        val reveal = Runnable { if (customView === view) view.animate().alpha(1f).duration = FADE_MS }
        // Whatever happens below, the video shows.
        backdrop.postDelayed(reveal, REVEAL_LATEST_MS)
        val webView = current()
        if (webView == null) {
            backdrop.post(reveal)
        } else {
            webView.evaluateJavascript(VIDEO_SIZE) { size ->
                val orientation = FullscreenOrientation.of(size)
                if (customView !== view) return@evaluateJavascript
                orientation?.let { activity.requestedOrientation = it }
                revealOnceShaped(backdrop, orientation, reveal)
            }
        }
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onHideCustomView()
        }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        // Bars and orientation first, under the black fullscreen view, so the shell never shows in the
        // fullscreen's shape.
        bars.show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = orientationBefore
        backdrop?.let {
            it.removeView(view)
            content.removeView(it)
        }
        backdrop = null
        customView = null
        _showing.value = false
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        backCallback?.remove()
        backCallback = null
    }

    /** Leaves fullscreen, if it is showing. The host calls this before destroying a WebView. */
    fun exit() = onHideCustomView()

    /** Runs [reveal] a moment after [backdrop] has the shape [orientation] asks for, giving Chromium time to lay out. */
    private fun revealOnceShaped(backdrop: View, orientation: Int?, reveal: Runnable) {
        fun shaped() = FullscreenOrientation.fits(orientation, backdrop.width, backdrop.height)
        if (shaped()) {
            backdrop.postDelayed(reveal, SETTLE_MS)
            return
        }
        backdrop.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (!shaped()) return
                v.removeOnLayoutChangeListener(this)
                v.postDelayed(reveal, SETTLE_MS)
            }
        })
    }

    private fun fill() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private companion object {
        const val SETTLE_MS = 200L
        const val FADE_MS = 150L
        const val REVEAL_LATEST_MS = 1_500L

        /** The main video's size in pixels, as `[width,height]`, or null before it has loaded. */
        const val VIDEO_SIZE =
            "(function () { var v = document.querySelector('#movie_player video') || document.querySelector('video');" +
                " return v && v.videoWidth ? [v.videoWidth, v.videoHeight] : null })()"
    }
}

/** Which way the screen turns for a fullscreen video. */
internal object FullscreenOrientation {
    private val SIZE = Regex("""^\[(\d+),(\d+)]$""")

    /**
     * The orientation for a video of the size `VIDEO_SIZE` evaluates to: landscape for a wide video,
     * portrait for a tall one, either way of holding the phone. Null for a square video or no size yet,
     * which leave the screen as it is.
     */
    fun of(size: String?): Int? {
        val (width, height) = SIZE.find(size.orEmpty())?.destructured ?: return null
        return of(width.toInt(), height.toInt())
    }

    fun of(width: Int, height: Int): Int? = when {
        width > height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        height > width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> null
    }

    /** Whether a view of [width] by [height] has turned the way [orientation] asks; null asks for nothing. */
    fun fits(orientation: Int?, width: Int, height: Int): Boolean = when (orientation) {
        null -> true
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> width > height
        else -> height > width
    }
}
