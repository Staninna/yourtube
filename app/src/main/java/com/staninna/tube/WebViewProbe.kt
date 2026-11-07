package com.staninna.tube

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Debug builds only: logs which androidx.webkit features the installed WebView provider supports
 * (logcat tag `YT-PROBE`) and turns on remote debugging. Call it before the first WebView is created.
 * It loads the WebView provider, so it must never run ahead of `ProcessGlobalConfig` setup.
 */
object WebViewProbe {
    private const val TAG = "YT-PROBE"

    private val features = listOf(
        WebViewFeature.MULTI_PROFILE,
        WebViewFeature.DELETE_BROWSING_DATA,
        WebViewFeature.DOCUMENT_START_SCRIPT,
        WebViewFeature.WEB_MESSAGE_LISTENER,
        WebViewFeature.USER_AGENT_METADATA,
        WebViewFeature.HTTP_CACHE_MANAGER,
    )

    fun run(context: Context) {
        if (!BuildConfig.DEBUG) return
        WebView.setWebContentsDebuggingEnabled(true)
        val pkg = WebViewCompat.getCurrentWebViewPackage(context)
        Log.i(TAG, "webview package=${pkg?.packageName} version=${pkg?.versionName}")
        for (feature in features) {
            Log.i(TAG, "$feature=${WebViewFeature.isFeatureSupported(feature)}")
        }
        val suffix = WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX
        Log.i(TAG, "$suffix=${WebViewFeature.isStartupFeatureSupported(context, suffix)}")
    }
}
