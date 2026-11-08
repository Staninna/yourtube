package com.staninna.yourtube.ui

import com.staninna.yourtube.data.HistoryDay
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.StarredItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** A video length as YouTube writes it (`4:05`, `1:02:09`), or null when it is not known yet. */
fun formatDuration(seconds: Double): String? {
    if (seconds <= 0.0 || seconds.isNaN() || seconds.isInfinite()) return null
    val total = seconds.roundToLong()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "1 item", "3 items": [count] with the noun that fits it. Callers decide what zero says. */
fun plural(count: Int, one: String, many: String): String = if (count == 1) "1 $one" else "$count $many"

/**
 * A library row's second line: the channel for videos and playlists, then the item's [tags]. The
 * segment already says what the item is, so a channel without tags has none.
 */
fun librarySubtitle(item: StarredItem, tags: List<String>): String? {
    val owner = if (item.type == ItemType.CHANNEL) null else item.author?.takeIf { it.isNotBlank() }
    val tagText = tags.joinToString(", ").takeIf { it.isNotEmpty() }
    return listOfNotNull(owner, tagText).joinToString(" · ").takeIf { it.isNotEmpty() }
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
