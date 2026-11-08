package com.staninna.yourtube.playback

import com.staninna.yourtube.page.NowPlaying
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the media session shows: the video in Watch, with its position as of [at] (the hub's clock, in
 * ms). Between changes the position runs on by itself at [rate], so a new one is published on state
 * changes and seeks only, not on every progress report.
 */
data class SessionMedia(
    val videoId: String,
    val title: String?,
    val author: String?,
    val thumbnail: String,
    val positionMs: Long,
    /** Null while the player has not reported a duration (or the video is live). */
    val durationMs: Long?,
    val status: NowPlaying.Status,
    val rate: Float,
    val at: Long,
) {
    /** Where playback stands at [now], running on from [positionMs] while playing. */
    fun positionAt(now: Long): Long {
        if (status != NowPlaying.Status.PLAYING) return positionMs
        val position = positionMs + ((now - at) * rate).roundToLong()
        return durationMs?.let { position.coerceAtMost(it) } ?: position
    }

    /** Whether this is the same video at a position that time alone does not explain: a seek. */
    fun jumpedFrom(previous: SessionMedia): Boolean =
        previous.videoId == videoId && abs(previous.positionAt(at) - positionMs) > DRIFT_MS

    companion object {
        /** How far a reported position may stray from the running one before it counts as a seek. */
        const val DRIFT_MS = 1_500L

        fun of(nowPlaying: NowPlaying, now: Long) = SessionMedia(
            videoId = nowPlaying.videoId,
            title = nowPlaying.title,
            author = nowPlaying.author,
            thumbnail = nowPlaying.thumbnail,
            positionMs = (nowPlaying.position * 1000).roundToLong(),
            durationMs = nowPlaying.duration.takeIf { it > 0.0 }?.let { (it * 1000).roundToLong() },
            status = nowPlaying.status,
            rate = nowPlaying.rate.toFloat(),
            at = now,
        )

        /** Whether [next] tells the session something [current] does not: anything but time passing. */
        fun changed(current: SessionMedia?, next: SessionMedia?): Boolean {
            if (current == null || next == null) return current != next
            return current.copy(positionMs = next.positionMs, at = next.at) != next || next.jumpedFrom(current)
        }
    }
}
