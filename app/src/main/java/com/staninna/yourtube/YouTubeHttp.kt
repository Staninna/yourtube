package com.staninna.yourtube

/**
 * GETs to YouTube outside the WebView, shaped like a current Chrome on Android with the consent cookie
 * set: without `SOCS` channel pages answer with a redirect to the consent page. No account and no other
 * cookie, so every request is anonymous. Used by the channel resolver and Feeds.
 */
object YouTubeHttp {
    private val HEADERS = mapOf(
        // YouTube serves the full mobile page with metadata to a current Chrome on Android.
        "User-Agent" to Http.CHROME_ANDROID_UA,
        // English pages, so relative dates ("3 days ago") read the same everywhere.
        "Accept-Language" to "en",
        "Cookie" to "SOCS=${YouTube.SOCS_REJECT_ALL}",
    )

    suspend fun get(url: String): HttpResult = Http.get(url, HEADERS)
}
