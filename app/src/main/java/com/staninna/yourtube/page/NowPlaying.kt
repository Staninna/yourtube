package com.staninna.yourtube.page

import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.web.PlaybackEvent

/** The video loaded in Watch, as the now-playing bar and the media session show it. */
data class NowPlaying(
    val videoId: String,
    /** Null until the player or history confirms the title. */
    val title: String?,
    val author: String?,
    /** Seconds. */
    val position: Double,
    /** Seconds; 0 until the player reports a duration. */
    val duration: Double,
    val status: Status,
    val rate: Double,
) {
    enum class Status { PLAYING, PAUSED, ENDED }

    val playing: Boolean get() = status == Status.PLAYING

    /**
     * Playing, or paused or ended somewhere past the start: a reload would play it again from its
     * resume point, so a theme change leaves the page alone.
     */
    val started: Boolean get() = playing || position > 0.0

    val thumbnail: String get() = YouTube.thumbnail(videoId)

    /** Position over duration, 0 to 1; null until the player reports a duration. */
    val progress: Float? get() = if (duration > 0.0) (position / duration).toFloat().coerceIn(0f, 1f) else null

    companion object {
        /**
         * The video Watch shows, or null when it shows anything else. Title comes from the page when it
         * has confirmed it, else from the video's history entry. Position and status come from the page's
         * last event when it is about this video; before its first one, the video counts as paused where
         * history left it.
         */
        fun of(page: PageIdentity, history: List<HistoryEntry>, last: PlaybackEvent?): NowPlaying? {
            val video = page as? PageIdentity.Video ?: return null
            val entry = history.firstOrNull { it.videoId == video.videoId }
            val meta = video.meta ?: entry?.meta
            val event = last?.takeIf { it.videoId == video.videoId }
            return NowPlaying(
                videoId = video.videoId,
                title = meta?.title,
                author = meta?.author,
                position = event?.position ?: entry?.position ?: 0.0,
                duration = event?.duration?.takeIf { it > 0.0 } ?: entry?.duration ?: 0.0,
                status = event?.let(::statusOf) ?: Status.PAUSED,
                rate = event?.rate ?: 1.0,
            )
        }

        /** An ended video is not playing, whatever `paused` says; an ad's end resumes without a play event. */
        private fun statusOf(event: PlaybackEvent): Status = when {
            event.kind == PlaybackKind.ENDED -> Status.ENDED
            event.paused -> Status.PAUSED
            else -> Status.PLAYING
        }
    }
}
