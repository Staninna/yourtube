package com.staninna.yourtube.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryDaysTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    private fun at(date: String, time: String, zone: ZoneId = amsterdam): Long =
        ZonedDateTime.of(LocalDate.parse(date), java.time.LocalTime.parse(time), zone).toInstant().toEpochMilli()

    private fun entry(id: String, watchedAt: Long) = HistoryEntry(id, watchedAt = watchedAt)

    private val now = at("2026-11-08", "09:00")

    @Test fun groupsByLocalDayNewestFirst() {
        val history = listOf(
            entry("a", at("2026-11-08", "08:59")),
            entry("b", at("2026-11-08", "00:00")),
            entry("c", at("2026-11-07", "23:59")),
            entry("d", at("2026-11-05", "12:00")),
            entry("e", at("2025-12-31", "12:00")),
        )
        val groups = HistoryDays.group(history, now, amsterdam)
        assertEquals(
            listOf(
                HistoryDay.Today,
                HistoryDay.Yesterday,
                HistoryDay.On(LocalDate.of(2026, 11, 5)),
                HistoryDay.On(LocalDate.of(2025, 12, 31)),
            ),
            groups.map { it.day },
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d"), listOf("e")), groups.map { g -> g.entries.map { it.videoId } })
    }

    @Test fun theZoneDecidesTheDay() {
        // 23:30 in Amsterdam is already the next day in Tokyo.
        val history = listOf(entry("a", at("2026-11-07", "23:30")))
        assertEquals(HistoryDay.Yesterday, HistoryDays.group(history, now, amsterdam).single().day)
        assertEquals(HistoryDay.Today, HistoryDays.group(history, now, ZoneId.of("Asia/Tokyo")).single().day)
    }

    @Test fun emptyHistoryHasNoGroups() = assertEquals(emptyList<HistoryGroup>(), HistoryDays.group(emptyList(), now, amsterdam))
}
