package com.staninna.yourtube

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/** The one home for YouTube URLs, origins, colors and the consent value the app relies on. */
object YouTube {
    const val HOME = "https://m.youtube.com"

    /** Origins the bridge and the page scripts are exposed to. */
    val ORIGINS = setOf("https://m.youtube.com", "https://www.youtube.com")

    /** Hosts whose pages are YouTube content (as opposed to consent or captcha pages). */
    val CONTENT_HOSTS = setOf("m.youtube.com", "www.youtube.com", "youtube.com")

    /**
     * The `SOCS` value YouTube sets after "Reject all" on its consent page (stage 0 finding f).
     * Seeding it skips the consent interstitial without accepting tracking.
     */
    const val SOCS_REJECT_ALL =
        "CAESNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjYxMTA2LjA3X3AwGgJlbiACGgYIgNS51wY"

    /** m.youtube.com's page background in its dark theme, and its header on watch pages in both. */
    const val PAGE_DARK: Int = 0xFF0F0F0F.toInt()

    /** m.youtube.com's page background in its light theme. */
    const val PAGE_LIGHT: Int = 0xFFFFFFFF.toInt()

    /** A video id, as in `watch?v=`. */
    val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** A channel's canonical UC id, which builds its RSS feed. */
    val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")

    /** A playlist id, as in `playlist?list=`. Anything Watch can star must pass this, so it survives a backup. */
    val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,}")

    fun watchUrl(videoId: String) = "$HOME/watch?v=$videoId"
    fun channelUrl(channelId: String) = "$HOME/channel/$channelId"
    fun playlistUrl(playlistId: String) = "$HOME/playlist?list=$playlistId"
    fun thumbnail(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    /** The results page for [query], or null when there is nothing to search for. */
    fun searchUrl(query: String): String? =
        query.trim().takeIf { it.isNotEmpty() }?.let { "$HOME/results?search_query=" + URLEncoder.encode(it, "UTF-8") }

    /** The query a results page shows, so the search field can start from it; null on any other page. */
    fun searchQuery(url: String?): String? {
        val uri = url?.let(::uri) ?: return null
        if (uri.host?.lowercase() !in CONTENT_HOSTS || uri.rawPath != "/results") return null
        return queryParam(url, "search_query")?.takeIf { it.isNotBlank() }
    }

    /** [url] parsed, or null when it is no valid URI. */
    fun uri(url: String): URI? = try {
        URI(url)
    } catch (_: URISyntaxException) {
        null
    }

    /** The first [name] parameter of [url]'s query, decoded; null when it is missing or empty. */
    fun queryParam(url: String, name: String): String? =
        uri(url)?.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("$name=") }
            // URI has checked every escape, so decoding cannot fail.
            ?.let { URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            ?.takeIf { it.isNotEmpty() }

    /** [url] without the parameters that set where a video starts (`t`, `start`). */
    fun withoutStartTime(url: String): String {
        val query = uri(url)?.rawQuery ?: return url
        val kept = query.split('&').filterNot { it.substringBefore('=') in START_PARAMS }
        val fragment = url.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        return url.substringBefore('?') + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    private val START_PARAMS = setOf("t", "start")

    /** [url] as it is worth sharing: the desktop host, which opens in the YouTube app or any browser. */
    fun shareUrl(url: String): String = url.replaceFirst("://m.youtube.com", "://www.youtube.com")

    /**
     * YouTube's page background. Its header has the same color, except on watch pages, where it is
     * dark in both themes: `pageBackground(dark || watchPage)`.
     */
    fun pageBackground(dark: Boolean): Int = if (dark) PAGE_DARK else PAGE_LIGHT
}
