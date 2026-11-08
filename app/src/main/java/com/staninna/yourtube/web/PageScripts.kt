package com.staninna.yourtube.web

import android.content.res.AssetManager
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.Settings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The two scripts injected into YouTube pages:
 * - `early.js` runs at document start in every frame and touches no DOM elements: the background
 *   guard and the app CSS, with the current settings baked into the script text.
 * - `main.js` waits for `DOMContentLoaded` in the main frame: navigation and playback reporting and
 *   resume.
 *
 * With `DOCUMENT_START_SCRIPT` both are registered on the WebView and re-registered when settings
 * change. Without it they are injected on page start and finish; both scripts guard against running
 * twice.
 */
internal class PageScripts(assets: AssetManager) {
    private val earlyTemplate = assets.open("early.js").bufferedReader().use { it.readText() }
    private val main = assets.open("main.js").bufferedReader().use { it.readText() }
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

    /** Applies [settings] to the document already showing; the early script updates its CSS in place. */
    fun applyToCurrentPage(webView: WebView, settings: Settings) = webView.evaluateJavascript(early(settings), null)

    /** Fallback for WebViews without document-start scripts. */
    fun onPageStarted(webView: WebView, settings: Settings) {
        if (!documentStart) webView.evaluateJavascript(early(settings), null)
    }

    fun onPageFinished(webView: WebView, settings: Settings) {
        if (!documentStart) webView.evaluateJavascript(early(settings) + "\n" + main, null)
    }

    /** Handlers die with their WebView; forget them so the next WebView starts clean. */
    fun forget() {
        handlers = emptyList()
    }

    private fun early(settings: Settings): String {
        val config = buildJsonObject { put("css", JsonPrimitive(css(settings))) }
        return earlyTemplate.replace(CONFIG_PLACEHOLDER, config.toString())
    }

    companion object {
        private const val CONFIG_PLACEHOLDER = "/*CONFIG*/{}"

        /**
         * YouTube's header (logo, search, "Open App", account) goes: Watch's native top bar replaces it.
         * YouTube lays the page out around a 48 px header with literal offsets, so each one that
         * matters is zeroed: the app's top padding, the sticky player, the comments and description
         * panel under the player, and the scroll anchor. Shorts and fullscreen keep their own layout.
         */
        private val HEADER_CSS = listOf(
            "ytm-mobile-topbar-renderer,#header-bar,.mobile-topbar-header-background{display:none !important}",
            // Except while YouTube's own search is open (home's in-page search box): it lives in the header.
            "#header-bar:has(.mobile-topbar-header[data-mode=\"searching\"]){display:block !important}",
            "ytm-app,ytm-app.sticky-player{padding-top:0 !important}",
            ".player-container,.player-container.sticky-player{top:0 !important}",
            "html.watch-scroll{scroll-padding-top:0 !important}",
            "html.watch-scroll.sticky-player{scroll-padding-top:56.25vw !important}",
            "@media (orientation:portrait){ytm-engagement-panel:not(.is-shorts-style):not(.force-fullscreen)" +
                "{top:56.25vw !important}}",
            "@media (orientation:landscape) and (min-width:931px){ytm-engagement-panel:not(.is-shorts-style)" +
                "{top:0 !important}}",
            ".mweb-phone-metadata-split-view ytm-engagement-panel:not(.is-shorts-style){top:0 !important}",
        ).joinToString("\n")

        /** Sign-in prompts, Premium and app-install promos: nothing a signed-out viewer can act on. */
        private val NAG_CSS = listOf(
            "ytm-mealbar-promo-renderer", ".mealbar-promo-renderer", "ytm-upsell-dialog-renderer",
            "ytm-statement-banner-renderer", "ytm-sign-in-promo-renderer", "ytm-account-promo-renderer",
            ".mobile-topbar-header-sign-in-button", "ytm-open-app-button-renderer", "ytm-app-promo-renderer",
            "ytm-inline-survey-renderer", "ytm-brand-video-singleton-renderer",
        ).joinToString(",") + "{display:none !important}"

        /** The app's CSS for YouTube pages under [settings]. */
        fun css(settings: Settings): String = buildList {
            add(
                "ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-promoted-sparkles-web-renderer," +
                    ".ad-container,ytm-player-ad,ytm-ad-slot-renderer,ad-slot-renderer," +
                    "ytm-rich-grid-slot-renderer:has(ytm-ad-slot-renderer){display:none !important}",
            )
            // The app is the chrome: YouTube's pivot bar (the app has its navigation bar), header and nags go.
            add("ytm-pivot-bar-renderer{display:none !important}")
            add("ytm-app,#app,[has-pivot-bar=\"true\"] ytm-app{padding-bottom:0 !important}")
            add(HEADER_CSS)
            add(NAG_CSS)
            if (settings.hideShorts) {
                add(
                    "ytm-reel-shelf-renderer,ytm-rich-section-renderer:has([href*=\"/shorts\"])," +
                        "ytm-pivot-bar-item-renderer:has([aria-label=\"Shorts\" i]),a[href^=\"/shorts\"]" +
                        "{display:none !important}",
                )
            }
        }.joinToString("\n")
    }
}
