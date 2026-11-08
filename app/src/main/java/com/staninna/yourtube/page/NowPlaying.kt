package com.staninna.yourtube.page

import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.HistoryEntry

/** The video loaded in Watch, as the now-playing bar shows it on the native tabs. */
data class NowPlaying(
    val videoId: String,
    /** Null until the player or history confirms the title. */
    val title: String?,
    val author: String?,
    /** Position over duration, 0 to 1; null until the player reports a duration. */
    val progress: Float?,
    val playing: Boolean,
) {
    val thumbnail: String get() = YouTube.thumbnail(videoId)

    companion object {
        /**
         * The video Watch shows, or null when it shows anything else. Title and progress come from the
         * page when it has confirmed them, else from the video's history entry.
         */
        fun of(page: PageIdentity, history: List<HistoryEntry>, playing: Boolean): NowPlaying? {
            val video = page as? PageIdentity.Video ?: return null
            val entry = history.firstOrNull { it.videoId == video.videoId }
            val meta = video.meta ?: entry?.meta
            return NowPlaying(video.videoId, meta?.title, meta?.author, entry?.fraction, playing)
        }
    }
}
