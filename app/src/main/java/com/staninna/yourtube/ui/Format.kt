package com.staninna.yourtube.ui

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.HistoryDay
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.VideoLength
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.feed.RelativeAge
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

/** A video length as YouTube writes it (`4:05`, `1:02:09`), or null when it is not known yet. */
fun formatDuration(seconds: Double): String? {
    if (seconds <= 0.0 || seconds.isNaN() || seconds.isInfinite()) return null
    val total = seconds.roundToLong()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    // Locale.ROOT: Western digits whatever the device's language, as YouTube writes lengths.
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** "1 item", "3 items": [count] with the noun that fits it. Callers decide what zero says. */
fun plural(count: Int, one: String, many: String): String = if (count == 1) "1 $one" else "$count $many"

/**
 * A library row's second line: the channel for videos and playlists, then the item's [tags]. A channel
 * leads with its newest upload instead ([latestUpload], from the feed cache, as an age at [now]) when
 * Feeds has one. The segment already says what the item is, so a channel with neither has none.
 */
fun librarySubtitle(item: StarredItem, tags: List<String>, latestUpload: Long?, now: Long): String? {
    val lead = when (item.type) {
        ItemType.CHANNEL -> latestUpload?.let { "Uploaded " + RelativeAge.format(it, now).lowercase() }
        else -> item.author?.takeIf { it.isNotBlank() }
    }
    val tagText = tags.joinToString(", ").takeIf { it.isNotEmpty() }
    return listOfNotNull(lead, tagText).joinToString(" · ").takeIf { it.isNotEmpty() }
}

/**
 * A view count as YouTube writes it under a video: "No views", "1 view", "999 views", "1.2K views",
 * "12K views", "1.2M views". Above a thousand it keeps one decimal under 10 and cuts, never rounds up.
 * Built from `Long.toString`, so the digits are Western in any locale.
 */
fun formatViews(count: Long): String = when {
    count <= 0L -> "No views"
    count == 1L -> "1 view"
    count < 1_000L -> "$count views"
    count < 1_000_000L -> compact(count, 1_000L) + "K views"
    count < 1_000_000_000L -> compact(count, 1_000_000L) + "M views"
    else -> compact(count, 1_000_000_000L) + "B views"
}

private fun compact(count: Long, unit: Long): String {
    val whole = count / unit
    if (whole >= 10) return whole.toString()
    val tenth = count * 10 / unit % 10
    return if (tenth == 0L) whole.toString() else "$whole.$tenth"
}

/** The badge on a feed video's still: its length, LIVE for a stream on air, SHORT for a Short; else none. */
fun feedBadge(video: FeedVideo): String? = when (val length = video.length) {
    is VideoLength.Known -> formatDuration(length.seconds.toDouble())
    is VideoLength.Live -> "LIVE"
    else -> if (video.isShort) "SHORT" else null
}

/** A feed row's detail line at [now]: "1.2M views · 3 days ago", or just the age without a count. */
fun feedDetail(video: FeedVideo, now: Long): String =
    listOfNotNull(video.views?.let(::formatViews), RelativeAge.format(video.publishedAt, now)).joinToString(" · ")

/**
 * What is left of a video [entry] stopped partway: "12 min left", "1 hr 5 min left", rounded up to the
 * minute. Null when there is no progress to speak of, the video counts as watched, or it has no length
 * (a stream on air).
 */
fun timeLeft(entry: HistoryEntry): String? {
    val length = entry.length ?: return null
    val progress = WatchProgress.of(entry) ?: return null
    if (progress.finished) return null
    val minutes = ceil((length - entry.position) / 60).toLong().coerceAtLeast(1)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$rest min left"
        rest == 0L -> "$hours hr left"
        else -> "$hours hr $rest min left"
    }
}

/**
 * Feeds' notice for channels whose latest fetch failed ([names], at least one): "Veritasium didn't
 * load.", "A and B didn't load.", "A and 3 other channels didn't load.", led by "You're offline." when
 * a missing connection is why.
 */
fun feedFailureNotice(names: List<String>, offline: Boolean): String {
    val who = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> "${names[0]} and ${names.size - 1} other channels"
    }
    return (if (offline) "You're offline. " else "") + "$who didn't load."
}

/**
 * A history day header: "Today", "Yesterday", then the weekday and date ("Monday 3 November"),
 * with the year once it is not [today]'s.
 */
fun historyDayLabel(day: HistoryDay, today: LocalDate, locale: Locale): String = when (day) {
    HistoryDay.Today -> "Today"
    HistoryDay.Yesterday -> "Yesterday"
    is HistoryDay.On -> day.date.format(
        DateTimeFormatter.ofPattern(if (day.date.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy", locale),
    )
}
