package com.staninna.yourtube.page

import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.ChannelInfo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.VideoMeta
import com.staninna.yourtube.web.PageReport
import java.net.URI
import java.net.URLDecoder

/**
 * What the WebView is showing, derived from the current URL plus the page's raw facts.
 *
 * Rules (stage 0 findings): the URL is the source of truth for the page type and its id. A video's
 * title, author and channel id come only from the player response, and only when its video id equals
 * the URL's. `og:*` and `ytInitialData` go stale on SPA navigation and are never read. Canonical stays
 * current, so `document.title` is trusted only when the canonical URL describes the same page.
 * `/@handle` pages carry no UC id after SPA navigation; `ChannelResolver` supplies it.
 */
sealed interface PageIdentity {

    /** [meta] is null until the player confirms this video. */
    data class Video(val videoId: String, val meta: VideoMeta?) : PageIdentity

    /**
     * [path] is the channel's page path without sub-tabs (`/@handle`, `/channel/UC...`, `/c/name`),
     * lowercased except for UC ids. It keys the channel cache. [channelId] is known from `/channel/`
     * paths only.
     */
    data class Channel(val path: String, val channelId: String?, val title: String?) : PageIdentity

    data class Playlist(val playlistId: String, val title: String?) : PageIdentity

    data object Other : PageIdentity

    companion object {
        /** The identity [url] alone gives: type and id, nothing the page has to confirm. */
        fun fromUrl(url: String): PageIdentity {
            val uri = parse(url) ?: return Other
            if (uri.host?.lowercase() !in YouTube.CONTENT_HOSTS) return Other
            val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decode)
            val query = queryParams(uri.rawQuery)
            val first = segments.firstOrNull() ?: return Other
            return when {
                first == "watch" -> query["v"]?.takeIf(VIDEO_ID::matches)?.let { Video(it, null) }
                first == "shorts" || first == "live" ->
                    segments.getOrNull(1)?.takeIf(VIDEO_ID::matches)?.let { Video(it, null) }
                first == "channel" ->
                    segments.getOrNull(1)?.takeIf(CHANNEL_ID::matches)?.let { Channel("/channel/$it", it, null) }
                first.startsWith("@") && first.length > 1 -> Channel("/${first.lowercase()}", null, null)
                first == "c" || first == "user" ->
                    segments.getOrNull(1)?.let { Channel("/$first/${it.lowercase()}", null, null) }
                first == "playlist" -> query["list"]?.takeIf(PLAYLIST_ID::matches)?.let { Playlist(it, null) }
                else -> null
            } ?: Other
        }

        /**
         * The identity of [currentUrl], completed with [report]'s facts when the report describes the
         * same URL. A report for any other URL is stale and ignored.
         */
        fun derive(currentUrl: String, report: PageReport?): PageIdentity {
            val base = fromUrl(currentUrl)
            if (report == null || !sameUrl(report.url, currentUrl)) return base
            return when (base) {
                is Video -> base.copy(meta = confirmedMeta(base.videoId, report))
                is Channel -> base.copy(title = trustedTitle(base, report))
                is Playlist -> base.copy(title = trustedTitle(base, report))
                Other -> Other
            }
        }

        /**
         * Names the markup drift in [report]: a page that should have yielded identity facts but did
         * not. Null when the report is as complete as its moment allows.
         */
        fun drift(report: PageReport): String? {
            val base = fromUrl(report.url)
            if (base !is Video) return null
            val player = report.player
            return when {
                player?.videoId == null ->
                    if (report.reason == PageReport.Reason.METADATA) "video.no-player-response" else null
                player.videoId != base.videoId ->
                    if (report.reason == PageReport.Reason.METADATA) "video.player-behind-url" else null
                player.title.isNullOrBlank() -> "video.no-title"
                player.channelId?.matches(CHANNEL_ID) != true -> "video.no-channel-id"
                else -> null
            }
        }

        /** Two URLs name the same page when they differ at most in their fragment. */
        fun sameUrl(a: String, b: String?): Boolean = b != null && a.substringBefore('#') == b.substringBefore('#')

        private fun confirmedMeta(videoId: String, report: PageReport): VideoMeta? {
            val player = report.player ?: return null
            val title = player.title?.takeIf { it.isNotBlank() } ?: return null
            if (player.videoId != videoId) return null
            // The channel id is kept only when it has the UC shape; anything else is markup drift.
            return VideoMeta(videoId, title, player.author, player.channelId?.takeIf(CHANNEL_ID::matches))
        }

        private fun trustedTitle(base: PageIdentity, report: PageReport): String? {
            val canonical = report.canonical ?: return null
            if (fromUrl(canonical) != base) return null
            return report.title.removeSuffix(" - YouTube").trim().ifEmpty { null }
        }

        private fun parse(url: String): URI? = try {
            URI(url)
        } catch (_: java.net.URISyntaxException) {
            null
        }

        private fun queryParams(rawQuery: String?): Map<String, String> =
            rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
            }

        /** Percent-decodes [s]; malformed escapes are kept as they are, which no id pattern matches. */
        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }

        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
        private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,}")
    }
}

/**
 * The library item the star button acts on, or null while the page is not (yet) identified well
 * enough to star. [channel] is the resolved info for a [PageIdentity.Channel] page.
 */
fun starItem(page: PageIdentity, channel: ChannelInfo?): StarredItem? = when (page) {
    is PageIdentity.Video -> page.meta?.let { meta ->
        StarredItem(
            id = meta.videoId,
            type = ItemType.VIDEO,
            title = meta.title,
            author = meta.author,
            thumbnail = YouTube.thumbnail(meta.videoId),
            url = YouTube.watchUrl(meta.videoId),
            channelId = meta.channelId,
        )
    }
    is PageIdentity.Channel -> {
        val id = page.channelId ?: channel?.id
        val name = channel?.name ?: page.title
        if (id == null || name == null) {
            null
        } else {
            StarredItem(
                id = id,
                type = ItemType.CHANNEL,
                title = name,
                author = name,
                thumbnail = channel?.avatar,
                url = YouTube.channelUrl(id),
                channelId = id,
            )
        }
    }
    is PageIdentity.Playlist -> page.title?.let { title ->
        StarredItem(
            id = page.playlistId,
            type = ItemType.PLAYLIST,
            title = title,
            url = YouTube.playlistUrl(page.playlistId),
        )
    }
    PageIdentity.Other -> null
}
