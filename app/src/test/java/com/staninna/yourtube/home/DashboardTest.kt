package com.staninna.yourtube.home

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.VideoMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which videos each home row takes, in what order, and when the home counts as empty. */
class DashboardTest {

    private fun watched(id: String, position: Double, duration: Double = 600.0, at: Long = 0, live: Boolean = false) =
        HistoryEntry(id, VideoMeta(id, "Video $id", "Someone", null, live), position, duration, watchedAt = at)

    private fun starred(id: String, type: ItemType, at: Long) = StarredItem(id, type, "Item $id", url = "u", addedAt = at)

    private fun upload(id: String) = FeedVideo(id, "Upload $id", "Channel", "UC1", publishedAt = 0)

    @Test fun nothingAnywhereIsEmpty() {
        val home = Dashboard.of(emptyList(), emptyList(), Library())
        assertTrue(home.empty)
    }

    @Test fun anyOneRowIsEnoughToNotBeEmpty() {
        assertFalse(Dashboard.of(listOf(watched("a", 100.0)), emptyList(), Library()).empty)
        assertFalse(Dashboard.of(emptyList(), listOf(upload("u")), Library()).empty)
        assertFalse(Dashboard.of(emptyList(), emptyList(), Library(listOf(starred("v", ItemType.VIDEO, 1)))).empty)
    }

    @Test fun continueWatchingTakesOnlyVideosStoppedPartwayNewestFirst() {
        val history = listOf(
            watched("older", 100.0, at = 10),
            watched("barely", 2.0, at = 50), // not past the resume threshold
            watched("done", 595.0, at = 40), // within the end margin: counts as watched
            watched("newer", 300.0, at = 30),
            watched("live", 100.0, at = 60, live = true), // a stream on air has no time left
            watched("unknown", 100.0, duration = 0.0, at = 70), // no length reported yet
        )
        val home = Dashboard.of(history, emptyList(), Library())
        assertEquals(listOf("newer", "older"), home.continueWatching.map { it.videoId })
    }

    @Test fun savedTakesOnlyVideosNewestStarredFirst() {
        val library = Library(
            listOf(
                starred("v1", ItemType.VIDEO, 1),
                starred("ch", ItemType.CHANNEL, 5),
                starred("v3", ItemType.VIDEO, 3),
                starred("pl", ItemType.PLAYLIST, 4),
                starred("v2", ItemType.VIDEO, 2),
            ),
        )
        assertEquals(listOf("v3", "v2", "v1"), Dashboard.of(emptyList(), emptyList(), library).saved.map { it.id })
    }

    @Test fun everyRowStopsAtTheRowSize() {
        val many = Dashboard.ROW_SIZE + 5
        val home = Dashboard.of(
            List(many) { watched("w$it", 100.0, at = it.toLong()) },
            List(many) { upload("u$it") },
            Library(List(many) { starred("v$it", ItemType.VIDEO, it.toLong()) }),
        )
        assertEquals(Dashboard.ROW_SIZE, home.continueWatching.size)
        assertEquals(Dashboard.ROW_SIZE, home.newFromSubs.size)
        assertEquals(Dashboard.ROW_SIZE, home.saved.size)
        assertEquals("w${many - 1}", home.continueWatching.first().videoId)
        assertEquals("u0", home.newFromSubs.first().videoId)
        assertEquals("v${many - 1}", home.saved.first().id)
    }
}
