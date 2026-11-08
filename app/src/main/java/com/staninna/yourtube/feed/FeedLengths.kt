package com.staninna.yourtube.feed

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.VideoLength

/**
 * When RSS videos get their lengths. RSS carries none, so they come from the channel's videos page
 * (30 lengths in one request) and, for what that lacks, its live page. A length never changes, so a
 * video keeps the one it has from the last fetch and the pages are read only for videos without one.
 * A stream on air or a video neither page lists is asked about again after [RECHECK_MS]. Shorts are
 * on neither page and are never asked about.
 */
object FeedLengths {
    /** How long a live or unlisted answer holds before the pages are read again. */
    const val RECHECK_MS = 6 * 60 * 60 * 1000L

    /** [videos] with the lengths [known] (the channel's last list) already had for them. */
    fun carry(videos: List<FeedVideo>, known: List<FeedVideo>): List<FeedVideo> {
        val lengths = known.mapNotNull { video -> video.length?.let { video.videoId to it } }.toMap()
        if (lengths.isEmpty()) return videos
        return videos.map { video -> if (video.length == null) lengths[video.videoId]?.let { video.copy(length = it) } ?: video else video }
    }

    /** Whether [video] wants the pages read at [now]. */
    fun wanted(video: FeedVideo, now: Long): Boolean = !video.isShort && when (val length = video.length) {
        null -> true
        is VideoLength.Known -> false
        is VideoLength.Live -> now - length.checkedAt >= RECHECK_MS
        is VideoLength.Unlisted -> now - length.checkedAt >= RECHECK_MS
    }

    /**
     * Whether [tab] is worth a request for [videos] at [now]: some want a length, and for the videos
     * page, not all of them are streams on air, which only the live page lists.
     */
    fun worthReading(tab: ChannelPage.Tab, videos: List<FeedVideo>, now: Long): Boolean {
        val wanting = videos.filter { wanted(it, now) }
        return wanting.isNotEmpty() && (tab != ChannelPage.Tab.VIDEOS || wanting.any { it.length !is VideoLength.Live })
    }

    /** [videos] with what a page [found], for the ones that wanted it. */
    fun fill(videos: List<FeedVideo>, found: Map<String, VideoLength>, now: Long): List<FeedVideo> = videos.map { video ->
        found[video.videoId]?.takeIf { wanted(video, now) }?.let { video.copy(length = it) } ?: video
    }

    /** Every page has been read: what still wants a length is on neither, as of [now]. */
    fun unlisted(videos: List<FeedVideo>, now: Long): List<FeedVideo> = videos.map { video ->
        if (wanted(video, now)) video.copy(length = VideoLength.Unlisted(now)) else video
    }
}
