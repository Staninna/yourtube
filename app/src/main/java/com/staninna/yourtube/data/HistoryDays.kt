package com.staninna.yourtube.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The day a group of history entries was watched, relative to today. */
sealed interface HistoryDay {
    data object Today : HistoryDay
    data object Yesterday : HistoryDay
    data class On(val date: LocalDate) : HistoryDay
}

data class HistoryGroup(val day: HistoryDay, val entries: List<HistoryEntry>)

/** History grouped by the local day each entry was last watched. */
object HistoryDays {
    /**
     * Groups [history] by day in [zone], in the order the days first appear (history is newest
     * first, so groups are too). Entries keep their order within a group.
     */
    fun group(history: List<HistoryEntry>, now: Long, zone: ZoneId): List<HistoryGroup> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return history
            .groupBy { Instant.ofEpochMilli(it.watchedAt).atZone(zone).toLocalDate() }
            .map { (date, entries) -> HistoryGroup(dayOf(date, today), entries) }
    }

    private fun dayOf(date: LocalDate, today: LocalDate): HistoryDay = when (date) {
        today -> HistoryDay.Today
        today.minusDays(1) -> HistoryDay.Yesterday
        else -> HistoryDay.On(date)
    }
}
