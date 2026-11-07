package com.staninna.yourtube.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Library, folder and history logic of the local store. */
@RunWith(RobolectricTestRunner::class)
class StoreTest {

    @Before fun clean() {
        Store.resetForTest(ApplicationProvider.getApplicationContext())
    }

    private fun video(id: String) = StarredItem(
        id = id, type = ItemType.VIDEO, title = "Video $id", url = "https://m.youtube.com/watch?v=$id",
    )

    private fun channel(id: String) = StarredItem(
        id = id, type = ItemType.CHANNEL, title = "Channel $id",
        url = "https://m.youtube.com/channel/$id", channelId = id,
    )

    @Test fun togglingStarsAndUnstars() {
        val a = video("A")
        assertFalse(Store.isStarred("A"))

        Store.toggleStar(a)
        assertTrue(Store.isStarred("A"))
        assertEquals(1, Store.library.value.items.size)

        Store.toggleStar(a)
        assertFalse(Store.isStarred("A"))
        assertTrue(Store.library.value.items.isEmpty())
    }

    @Test fun newestStarComesFirst() {
        Store.toggleStar(video("A"))
        Store.toggleStar(video("B"))
        assertEquals(listOf("B", "A"), Store.library.value.items.map { it.id })
    }

    @Test fun setFolderAssignsAndRemoveFolderClearsIt() {
        Store.toggleStar(channel("UC1"))
        val folder = Store.addFolder("Music")
        assertEquals(1, Store.library.value.folders.size)

        Store.setFolder("UC1", folder.id)
        assertEquals(folder.id, Store.library.value.items.first { it.id == "UC1" }.folderId)

        Store.removeFolder(folder.id)
        assertTrue(Store.library.value.folders.isEmpty())
        assertNull(Store.library.value.items.first { it.id == "UC1" }.folderId)
    }

    @Test fun recordWatchDeduplicatesAndMovesToFront() {
        Store.recordWatch(HistoryEntry(videoId = "A", title = "A", url = "u/A"))
        Store.recordWatch(HistoryEntry(videoId = "B", title = "B", url = "u/B"))
        Store.recordWatch(HistoryEntry(videoId = "A", title = "A", url = "u/A", position = 42.0))

        val history = Store.history.value
        assertEquals(listOf("A", "B"), history.map { it.videoId })
        assertEquals(42.0, history.first().position, 0.0)
    }

    @Test fun recordWatchKeepsEarlierMetadataWhenNewReportIsBlank() {
        Store.recordWatch(HistoryEntry(videoId = "A", title = "Real title", author = "Creator", url = "u/A"))
        // A later progress ping may arrive with empty metadata; it must not wipe what we had.
        Store.recordWatch(HistoryEntry(videoId = "A", title = "", author = "", url = "u/A", position = 10.0))

        val entry = Store.history.value.single()
        assertEquals("Real title", entry.title)
        assertEquals("Creator", entry.author)
        assertEquals(10.0, entry.position, 0.0)
    }

    @Test fun blankVideoIdIsIgnored() {
        Store.recordWatch(HistoryEntry(videoId = "", title = "nope", url = "u"))
        assertTrue(Store.history.value.isEmpty())
    }

    @Test fun clearHistoryEmptiesIt() {
        Store.recordWatch(HistoryEntry(videoId = "A", title = "A", url = "u/A"))
        Store.clearHistory()
        assertTrue(Store.history.value.isEmpty())
    }

    @Test fun settingsUpdateIsApplied() {
        assertFalse(Store.settings.value.hideShorts)
        Store.updateSettings { it.copy(hideShorts = true) }
        assertTrue(Store.settings.value.hideShorts)
    }
}
