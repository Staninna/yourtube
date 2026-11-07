package com.staninna.yourtube.web

import com.staninna.yourtube.YouTube
import java.net.URI
import java.net.URISyntaxException

/** Which main-frame navigations stay in the WebView. Everything else opens in another app. */
object NavigationPolicy {
    private val IN_APP_HOSTS = YouTube.CONTENT_HOSTS + "consent.youtube.com"

    fun opensInApp(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        // www.google.com/sorry is YouTube's bot-check captcha; it must load where the session lives.
        return host in IN_APP_HOSTS || (host == "www.google.com" && uri.rawPath.orEmpty().startsWith("/sorry"))
    }
}
