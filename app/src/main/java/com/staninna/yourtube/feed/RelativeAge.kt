package com.staninna.yourtube.feed

/**
 * Ages as YouTube writes them: "3 hours ago", "9 days ago", "2 weeks ago", "4 months ago". A channel's
 * videos page gives only this text, so [parse] turns it into a duration, and Feeds writes every age
 * with [format]: a rounded page age then reads exactly as YouTube showed it, and an exact RSS time
 * reads the same way.
 */
object RelativeAge {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
    private const val MONTH = 30 * DAY
    private const val YEAR = 365 * DAY

    private val UNITS = mapOf(
        "second" to 1_000L, "minute" to MINUTE, "hour" to HOUR, "day" to DAY,
        "week" to WEEK, "month" to MONTH, "year" to YEAR,
    )

    /** "Streamed 2 days ago" and "Premiered 1 week ago" carry the same age as plain "2 days ago". */
    private val AGE = Regex("""(\d+) (second|minute|hour|day|week|month|year)s? ago""")

    /** Larger counts are not ages YouTube writes; refusing them also keeps the product from overflowing. */
    private const val MAX_COUNT = 1000L

    /** How long ago [text] says, in milliseconds; null when it is not an English age of at most [MAX_COUNT] units. */
    fun parse(text: String): Long? {
        val match = AGE.find(text) ?: return null
        val count = match.groupValues[1].toLongOrNull()?.takeIf { it <= MAX_COUNT } ?: return null
        return count * UNITS.getValue(match.groupValues[2])
    }

    /** [at] as an age at [now]. Days run up to 13, as on YouTube, then weeks until a month. */
    fun format(at: Long, now: Long): String {
        val age = (now - at).coerceAtLeast(0)
        return when {
            age < MINUTE -> "Just now"
            age < HOUR -> ago(age / MINUTE, "minute")
            age < DAY -> ago(age / HOUR, "hour")
            age < 2 * WEEK -> ago(age / DAY, "day")
            age < MONTH -> ago(age / WEEK, "week")
            age < YEAR -> ago(age / MONTH, "month")
            else -> ago(age / YEAR, "year")
        }
    }

    private fun ago(count: Long, unit: String) = if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
}
