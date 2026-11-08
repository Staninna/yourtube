package com.staninna.yourtube.data

/**
 * When a video resumes and where. A `t=` URL parameter beats all of this; the page script skips
 * the resume request when the URL has one.
 */
object ResumeRules {
    /** Seconds to rewind so the viewer gets some context back. */
    const val REWIND = 5.0

    /** Positions this early are not worth resuming. */
    const val MIN_POSITION = 5.0

    /** Positions this close to the end count as finished. */
    const val END_MARGIN = 15.0

    /** The position to start [entry]'s video at, or null to start from the beginning. */
    fun startAt(entry: HistoryEntry?): Double? {
        if (entry == null || entry.duration <= 0.0) return null
        if (entry.position < MIN_POSITION || entry.position > entry.duration - END_MARGIN) return null
        return (entry.position - REWIND).coerceAtLeast(0.0)
    }
}

/** How far a video in history was watched, for the bar under its thumbnail. */
data class WatchProgress(
    /** Position over duration, 0 to 1. */
    val fraction: Float,
    /** Within [ResumeRules.END_MARGIN] of the end: the video resumes from the start, so it counts as watched. */
    val finished: Boolean,
) {
    companion object {
        /** [entry]'s progress, or null when there is none to show (no duration yet, or barely started). */
        fun of(entry: HistoryEntry): WatchProgress? {
            val fraction = entry.fraction ?: return null
            val finished = entry.position > entry.duration - ResumeRules.END_MARGIN
            if (!finished && entry.position < ResumeRules.MIN_POSITION) return null
            return WatchProgress(fraction, finished)
        }
    }
}
