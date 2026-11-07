package com.staninna.yourtube.web

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Shows the page's fullscreen video over the Activity and routes back to leaving it. Also forwards
 * load progress, the other thing WebView reports through its chrome client.
 */
internal class FullscreenChromeClient(
    private val activity: ComponentActivity,
    private val onProgress: (WebView, Int) -> Unit,
) : WebChromeClient() {
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var backCallback: OnBackPressedCallback? = null
    private val content: ViewGroup get() = activity.findViewById(android.R.id.content)

    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(view, newProgress)

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view.apply { setBackgroundColor(Color.BLACK) }
        customViewCallback = callback
        content.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onHideCustomView()
        }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        content.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        backCallback?.remove()
        backCallback = null
    }

    /** Leaves fullscreen, if it is showing. The host calls this before destroying a WebView. */
    fun exit() = onHideCustomView()
}
