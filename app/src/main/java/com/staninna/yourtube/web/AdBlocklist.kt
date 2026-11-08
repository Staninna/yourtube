package com.staninna.yourtube.web

import java.net.URI
import java.net.URISyntaxException

/**
 * The network layer of ad blocking: the hosts and YouTube paths that serve ads or track them, taken
 * from EasyList, EasyPrivacy and what m.youtube.com was seen requesting (sources and dates in
 * `docs/research/ad-blocking.md`). The WebView answers a match with an empty response.
 *
 * Hosts match themselves and their subdomains. Paths match only on YouTube's own hosts, as a whole
 * path segment prefix, so `/youtubei/v1/player/ad_break` is blocked and `/youtubei/v1/player` (the
 * video's own data) is not. Nothing here may ever match `googlevideo.com`, where the video streams
 * come from; the tests hold that.
 */
object AdBlocklist {
    /** Ad servers. Nothing a signed-out viewer needs is served from them. */
    val HOSTS: Set<String> = setOf(
        "doubleclick.net", // googleads.g.doubleclick.net/pagead/id, static.doubleclick.net/instream/ad_status.js
        "googleadservices.com",
        "googlesyndication.com", // pagead2.googlesyndication.com/ytcs/adview, the ad view beacon
        "googletagservices.com",
        "fcmatch.youtube.com", // EasyPrivacy: ad cookie matching
    )

    /** Hosts whose [PATHS] are YouTube's ad endpoints. */
    private val YOUTUBE_HOSTS = setOf("m.youtube.com", "www.youtube.com", "youtube.com")

    /**
     * Google's own hosts serve conversion and audience pixels under `/pagead/` (seen: `www.google.com`
     * and the country domain, such as `www.google.nl`, both `/pagead/1p-user-list/`).
     */
    private val GOOGLE_HOST = Regex("""(www\.)?google\.[a-z]{2,3}(\.[a-z]{2})?""")

    /** YouTube paths that carry ads or report on them. */
    val PATHS: List<String> = listOf(
        "/pagead/", // conversion pixels and ad clicks (EasyList)
        "/api/stats/ads", // ad impression reports (EasyPrivacy)
        "/ptracking", // ad and conversion tracking ping (EasyPrivacy)
        "/get_midroll_info", // the getAdBreakUrl every mid-roll break in a player response names
        "/youtubei/v1/player/ad_break", // where the player fetched mid-roll ads in practice (EasyList)
        "/pcs/activeview", // ad viewability reports (EasyPrivacy)
    )

    /** Whether [url] goes to an ad or ad-tracking server. Unparseable URLs are left alone. */
    fun blocks(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val path = uri.rawPath.orEmpty()
        return when {
            host in YOUTUBE_HOSTS -> PATHS.any { path.matchesPrefix(it) }
            GOOGLE_HOST.matches(host) -> path.startsWith("/pagead/")
            else -> false
        }
    }

    /** Starts with [prefix] as whole segments: the prefix ends in `/`, or the path ends or goes on with `/` after it. */
    private fun String.matchesPrefix(prefix: String): Boolean =
        startsWith(prefix) && (prefix.endsWith("/") || length == prefix.length || this[prefix.length] == '/')
}
