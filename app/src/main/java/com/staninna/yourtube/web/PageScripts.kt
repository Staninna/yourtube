package com.staninna.yourtube.web

import android.content.res.AssetManager
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.PlayerPrefs
import com.staninna.yourtube.data.Settings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The scripts injected into YouTube pages. Two follow the settings:
 * - `early.js` runs at document start in every frame and touches no DOM elements: the background
 *   guard, the app CSS and the player-ads prune, with the current [PageConfig] baked into the script
 *   text. It keeps the config for `main.js`.
 * - `main.js` waits for `DOMContentLoaded` in the main frame: navigation and playback reporting,
 *   resume, SponsorBlock segments, the ad safety net and Shorts links.
 *
 * Two are fixed for a WebView's life:
 * - `identity.js` runs at document start in every frame: `navigator` that agrees with the reduced user
 *   agent, and the last identity's player choices. Without document-start scripts it is left out.
 * - `consent.js` rejects YouTube's cookie choice, on consent.youtube.com and in YouTube's own dialog.
 *
 * With `DOCUMENT_START_SCRIPT` all are registered on the WebView, the first two again when settings
 * change. Without it, the rest are injected on page start and finish; every script guards against
 * running twice.
 */
internal class PageScripts(assets: AssetManager) {
    private val earlyTemplate = assets.open("early.js").bufferedReader().use { it.readText() }
    private val main = assets.open("main.js").bufferedReader().use { it.readText() }
    private val identityTemplate = assets.open("identity.js").bufferedReader().use { it.readText() }
    private val consent = assets.open("consent.js").bufferedReader().use { it.readText() }
    private val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var handlers: List<ScriptHandler> = emptyList()

    /** Registers the scripts for every future document of [webView], replacing earlier ones. */
    fun register(webView: WebView, settings: Settings) {
        handlers.forEach { it.remove() }
        handlers = if (documentStart) {
            listOf(early(settings), main).map { WebViewCompat.addDocumentStartJavaScript(webView, it, YouTube.ORIGINS) }
        } else {
            emptyList()
        }
    }

    /** Registers the fixed scripts for every document of a new [webView]; [prefs] go to its first identity. */
    fun registerFixed(webView: WebView, prefs: PlayerPrefs, now: Long) {
        if (!documentStart) return
        WebViewCompat.addDocumentStartJavaScript(webView, identity(prefs, now), YouTube.ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(webView, consent, YouTube.ORIGINS + CONSENT_ORIGIN)
    }

    /** Applies [settings] to the document already showing; the early script swaps its config in place. */
    fun applyToCurrentPage(webView: WebView, settings: Settings) = webView.evaluateJavascript(early(settings), null)

    /** Fallback for WebViews without document-start scripts. */
    fun onPageStarted(webView: WebView, settings: Settings) {
        if (!documentStart) webView.evaluateJavascript(early(settings), null)
    }

    fun onPageFinished(webView: WebView, settings: Settings) {
        if (!documentStart) webView.evaluateJavascript(early(settings) + "\n" + main + "\n" + consent, null)
    }

    /** Handlers die with their WebView; forget them so the next WebView starts clean. */
    fun forget() {
        handlers = emptyList()
    }

    /** The early script with [settings]' [PageConfig] in place of its config literal. */
    internal fun early(settings: Settings): String = earlyTemplate.replace(CONFIG_PLACEHOLDER, PageConfig.of(settings).toString())

    /** The identity script with the platform and [prefs]' localStorage entries in place of its config literal. */
    internal fun identity(prefs: PlayerPrefs, now: Long): String {
        val config = buildJsonObject {
            put("platform", JsonPrimitive(UserAgent.PLATFORM))
            put("prefs", PlayerPrefsStorage.entriesJson(prefs, now))
        }
        return identityTemplate.replace(CONFIG_PLACEHOLDER, config.toString())
    }

    private companion object {
        const val CONFIG_PLACEHOLDER = "/*CONFIG*/{}"
        const val CONSENT_ORIGIN = "https://consent.youtube.com"
    }
}
