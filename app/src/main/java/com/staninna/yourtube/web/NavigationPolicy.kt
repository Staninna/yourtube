package com.staninna.yourtube.web

import com.staninna.yourtube.YouTube
import java.net.URI
import java.net.URISyntaxException

/** Where a main-frame navigation goes. */
sealed interface Navigation {
    /** Loads in the WebView as asked. */
    data object InApp : Navigation

    /** Opens in another app. */
    data object External : Navigation

    /** A Short while Shorts are removed: [url] is its normal watch page, loaded instead. */
    data class Rewrite(val url: String) : Navigation
}

/** Which main-frame navigations stay in the WebView, and which Shorts open as normal videos. */
object NavigationPolicy {
    private val IN_APP_HOSTS = YouTube.CONTENT_HOSTS + "consent.youtube.com"

    fun decide(url: String, removeShorts: Boolean): Navigation {
        if (removeShorts) shortAsWatch(url)?.let { return Navigation.Rewrite(it) }
        return if (opensInApp(url)) Navigation.InApp else Navigation.External
    }

    fun opensInApp(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        // www.google.com/sorry is YouTube's bot-check captcha; it must load where the session lives.
        return host in IN_APP_HOSTS || (host == "www.google.com" && uri.rawPath.orEmpty().startsWith("/sorry"))
    }

    /**
     * The watch page of the Short [url] points to (`/shorts/<id>` on a YouTube host, any query), or null
     * when it is not a Short. `main.js` applies the same rule to in-page navigation.
     */
    fun shortAsWatch(url: String): String? {
        val uri = parse(url) ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.host?.lowercase() !in YouTube.CONTENT_HOSTS) return null
        val id = SHORTS_PATH.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1) ?: return null
        return YouTube.watchUrl(id)
    }

    private val SHORTS_PATH = Regex("""/shorts/(${YouTube.VIDEO_ID.pattern})/?""")

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (_: URISyntaxException) {
        null
    }
}
