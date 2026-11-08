package com.staninna.yourtube.feed

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.NotifyState
import com.staninna.yourtube.data.SeenChannel

/**
 * What the background check notifies about. A video is new when its channel was seeded before, its id
 * has not been seen, it was published after the channel was seeded (with [CLOCK_MARGIN_MS] for the
 * device's clock against YouTube's), and it is not a Short that Remove all Shorts hides. A channel
 * read for the first time is seeded without a word, so turning the switch on or subscribing never
 * brings the backlog; an empty first answer seeds nothing.
 */
object NewVideos {
    /** Notifications posted per check at most; the summary counts the rest. */
    const val CAP = 10

    /** Ids remembered per channel: RSS lists 15, so a video that drops out and back stays known. */
    const val SEEN_PER_CHANNEL = 50

    /** How far the device's clock may be ahead of YouTube's before a new upload looks like backlog. */
    const val CLOCK_MARGIN_MS = 60 * 60 * 1000L

    /** One check's outcome: the new videos, newest first, and the state to keep. */
    data class Round(val fresh: List<FeedVideo>, val state: NotifyState)

    /**
     * [fetched] holds the lists of the channels that answered; one that did not keeps what it had.
     * Channels no longer in [subscriptions] are forgotten. [now] stamps channels seeded in this round.
     */
    fun check(state: NotifyState, fetched: Map<String, List<FeedVideo>>, subscriptions: Set<String>, removeShorts: Boolean, now: Long): Round {
        val fresh = mutableListOf<FeedVideo>()
        val seen = state.seen.filterKeys { it in subscriptions }.toMutableMap()
        fetched.filterKeys { it in subscriptions }.forEach { (channelId, videos) ->
            val known = seen[channelId]
            if (known == null) {
                // An empty answer is no proof the channel has nothing; seed it once it shows its uploads.
                if (videos.isNotEmpty()) seen[channelId] = SeenChannel(now, videos.map { it.videoId }.take(SEEN_PER_CHANNEL))
                return@forEach
            }
            val ids = known.ids.toHashSet()
            fresh += videos.filter {
                it.videoId !in ids && it.publishedAt >= known.seededAt - CLOCK_MARGIN_MS && !(removeShorts && it.isShort)
            }
            // Shorts left out still count as seen, so turning Remove all Shorts off later brings no old ones.
            seen[channelId] = known.copy(ids = (videos.map { it.videoId } + known.ids).distinct().take(SEEN_PER_CHANNEL))
        }
        return Round(fresh.sortedByDescending { it.publishedAt }, state.copy(seen = seen))
    }

    /** The group summary: its title, its lines newest first, and "+N more" for what the cap left out. */
    data class Summary(val title: String, val lines: List<String>, val more: String?)

    /** A notification's line in the summary: "Channel: Title". */
    fun line(video: FeedVideo): String = "${video.author}: ${video.title}"

    /**
     * The summary over every video notification in the group: [earlier] (lines by video id, still in
     * the shade from past checks) and [posted] now, plus [left] that the cap kept back. It is the one
     * that alerts, so a check sounds once however many it posts; with a single video Android shows
     * that one instead. Null when there is nothing to sum up.
     */
    fun summary(earlier: Map<String, String>, posted: List<FeedVideo>, left: Int): Summary? {
        val postedIds = posted.map { it.videoId }.toSet()
        val lines = posted.map(::line) + earlier.filterKeys { it !in postedIds }.values
        val total = lines.size + left
        if (total == 0) return null
        return Summary(if (total == 1) "1 new video" else "$total new videos", lines, if (left > 0) "+$left more" else null)
    }
}
