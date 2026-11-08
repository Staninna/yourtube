package com.staninna.yourtube.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class StoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dir: File get() = tmp.root
    private var clock = 1_000L

    private fun TestScope.store() = Store(dir, this) { clock }

    private fun video(id: String) = StarredItem(
        id = id, type = ItemType.VIDEO, title = "Video $id", url = "https://m.youtube.com/watch?v=$id",
    )

    private fun meta(id: String, title: String = "Title $id") = VideoMeta(id, title, "Author", "UC" + "x".repeat(22))

    // --- library ---

    @Test fun togglingStarsAndUnstarsNewestFirst() = runTest {
        val store = store()
        store.toggleStar(video("A"))
        clock += 10
        store.toggleStar(video("B"))
        val items = store.library.value.items
        assertEquals(listOf("B", "A"), items.map { it.id })
        assertEquals(listOf(clock, clock - 10), items.map { it.addedAt })
        store.toggleStar(video("A"))
        assertEquals(listOf("B"), store.library.value.items.map { it.id })
    }

    @Test fun libraryAndSettingsAreWrittenRightAwayAndReadBack() = runTest {
        val store = store()
        store.toggleStar(video("A"))
        store.updateSettings { it.copy(hideShorts = true) }
        runCurrent()

        val text = File(dir, "library.json").readText()
        assertTrue(text, text.contains("\"schemaVersion\":1"))
        val reloaded = store()
        assertEquals(listOf("A"), reloaded.library.value.items.map { it.id })
        assertTrue(reloaded.settings.value.hideShorts)
        assertTrue(reloaded.corruptFiles.isEmpty())
    }

    @Test fun concurrentUpdatesAreAllKept() = runTest {
        val store = store()
        val threads = (0 until 8).map { t ->
            thread { repeat(50) { i -> store.toggleStar(video("v$t-$i")) } }
        }
        threads.forEach { it.join() }
        advanceUntilIdle()

        assertEquals(400, store.library.value.items.size)
        assertEquals(400, store().library.value.items.size)
    }

    @Test fun deletingATagDetachesItInTheSameWrite() = runTest {
        val store = store()
        store.toggleStar(video("A"))
        val music = store.createTag("Music")
        val talks = store.createTag("Talks")
        store.setItemTags("A", setOf(music.id, talks.id, "unknown"))
        assertEquals(setOf(music.id, talks.id), store.library.value.items.single().tagIds)
        assertTrue(music.color != talks.color)

        store.deleteTag(music.id)
        val lib = store.library.value
        assertEquals(listOf("Talks"), lib.tags.map { it.name })
        assertEquals(setOf(talks.id), lib.items.single().tagIds)
        assertEquals(listOf("A"), lib.items.map { it.id })
    }

    // --- missing and corrupt files ---

    @Test fun missingFilesGiveDefaultsWithoutAFlag() = runTest {
        val store = store()
        assertTrue(store.library.value.items.isEmpty())
        assertEquals(Settings(), store.settings.value)
        assertTrue(store.corruptFiles.isEmpty())
    }

    @Test fun corruptFileIsKeptAsideAndFlagged() = runTest {
        File(dir, "library.json").writeText("{not json")
        val store = store()

        assertEquals(setOf("library.json"), store.corruptFiles)
        assertTrue(store.library.value.items.isEmpty())
        assertEquals("{not json", File(dir, "library.json.corrupt").readText())

        store.toggleStar(video("A"))
        runCurrent()
        assertEquals(listOf("A"), store().library.value.items.map { it.id })
        assertEquals("{not json", File(dir, "library.json.corrupt").readText())
    }

    @Test fun unknownSchemaVersionCountsAsCorrupt() = runTest {
        File(dir, "settings.json").writeText("""{"schemaVersion":99,"data":{"hideShorts":true}}""")
        val store = store()
        assertEquals(setOf("settings.json"), store.corruptFiles)
        assertFalse(store.settings.value.hideShorts)
    }

    // --- playback: history with positions ---

    private data class Event(val kind: PlaybackKind, val id: String, val position: Double, val duration: Double)

    private fun event(kind: PlaybackKind, id: String, position: Double = 0.0, duration: Double = 300.0) =
        Event(kind, id, position, duration)

    private fun Store.recordPlayback(e: Event, meta: VideoMeta?) =
        recordPlayback(e.kind, e.id, e.position, e.duration, meta)

    private fun Store.time(id: String, position: Double, duration: Double = 300.0) =
        recordPlayback(event(PlaybackKind.TIME, id, position, duration), null)

    @Test fun playbackIsWrittenAfterTheDebounce() = runTest {
        val store = store()
        store.recordPlayback(event(PlaybackKind.PLAY, "A"), meta("A"))
        store.time("A", 42.0)
        runCurrent()
        assertFalse(File(dir, "history.json").exists())

        advanceTimeBy(Store.PLAYBACK_FLUSH_MS - 1)
        runCurrent()
        assertFalse(File(dir, "history.json").exists())

        advanceTimeBy(2)
        runCurrent()
        val entry = store().history.value.single()
        assertEquals(42.0, entry.position, 0.0)
        assertEquals("Title A", entry.meta!!.title)
    }

    @Test fun progressInsideTheWindowSharesOneWrite() = runTest {
        val store = store()
        store.time("A", 10.0)
        advanceTimeBy(5_000)
        store.time("A", 15.0)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(15.0, store().history.value.single().position, 0.0)
    }

    @Test fun pauseAndEndWriteAtOnce() = runTest {
        for (kind in listOf(PlaybackKind.PAUSE, PlaybackKind.ENDED)) {
            val store = store()
            store.recordPlayback(event(kind, "A", position = 42.0), null)
            runCurrent()
            assertEquals(kind.name, 42.0, store().history.value.single().position, 0.0)
            File(dir, "history.json").delete()
        }
    }

    @Test fun flushWritesPlaybackNow() = runTest {
        val store = store()
        store.time("A", 42.0)
        store.flush()
        runCurrent()
        assertEquals(42.0, store().history.value.single().position, 0.0)
    }

    @Test fun playMovesToTopButKeepsTheStoredPosition() = runTest {
        val store = store()
        store.time("A", 120.0)
        store.time("B", 30.0)
        // Play fires at position 0, before the page asked where to resume.
        store.recordPlayback(event(PlaybackKind.PLAY, "A", position = 0.0), null)
        assertEquals(listOf("A", "B"), store.history.value.map { it.videoId })
        assertEquals(115.0, store.resumePosition("A")!!, 0.0)
    }

    @Test fun metadataChangesNothing() = runTest {
        val store = store()
        store.time("A", 120.0)
        store.time("B", 30.0)
        store.recordPlayback(event(PlaybackKind.METADATA, "A", position = 0.0), meta("A"))
        assertEquals(listOf("B", "A"), store.history.value.map { it.videoId })
        assertEquals(120.0, store.history.value.last().position, 0.0)
    }

    @Test fun progressOfANewVideoMovesItToTheTop() = runTest {
        // After a pre-roll ad the content can continue with time events only.
        val store = store()
        store.time("A", 10.0)
        clock += 50
        store.time("B", 10.0)
        assertEquals(listOf("B", "A"), store.history.value.map { it.videoId })
        assertEquals(clock, store.history.value.first().watchedAt)
    }

    @Test fun theTopEntryIsUpdatedInPlace() = runTest {
        val store = store()
        store.time("A", 10.0)
        val stamped = store.history.value.single().watchedAt
        clock += 50
        store.time("A", 20.0)
        val entry = store.history.value.single()
        assertEquals(20.0, entry.position, 0.0)
        assertEquals(stamped, entry.watchedAt)
    }

    @Test fun metaIsKeptWhenAnEventBringsNone() = runTest {
        val store = store()
        store.recordPlayback(event(PlaybackKind.PLAY, "A"), meta("A", "Real title"))
        store.time("B", 10.0)
        store.time("A", 10.0)
        val history = store.history.value
        assertEquals(listOf("A", "B"), history.map { it.videoId })
        assertEquals("Real title", history.first().meta!!.title)
        assertNull(history.last().meta)
        assertEquals("https://m.youtube.com/watch?v=A", history.first().url)
    }

    @Test fun annotateHistoryFillsAnExistingEntryOnly() = runTest {
        val store = store()
        store.time("A", 10.0)
        store.annotateHistory(meta("A", "Late title"))
        store.annotateHistory(meta("Z"))
        assertEquals(listOf("A"), store.history.value.map { it.videoId })
        assertEquals("Late title", store.history.value.single().meta!!.title)
    }

    @Test fun historyIsCapped() = runTest {
        val store = store()
        repeat(Store.HISTORY_CAP + 1) { store.time("v$it", 10.0) }
        val history = store.history.value
        assertEquals(Store.HISTORY_CAP, history.size)
        assertEquals("v${Store.HISTORY_CAP}", history.first().videoId)
        assertTrue(history.none { it.videoId == "v0" })
    }

    @Test fun removingAndClearingHistoryForgetsPositions() = runTest {
        val store = store()
        store.time("A", 60.0)
        store.time("B", 60.0)
        store.removeHistory("A")
        assertNull(store.resumePosition("A"))
        assertEquals(55.0, store.resumePosition("B")!!, 0.0)

        store.clearHistory()
        runCurrent()
        assertTrue(store.history.value.isEmpty())
        assertTrue(store().history.value.isEmpty())
    }

    @Test fun undoingAClearRestoresPositionsAndKeepsNewerWatches() = runTest {
        val store = store()
        store.time("A", 60.0)
        clock += 10
        store.time("B", 60.0)
        val cleared = store.history.value
        store.clearHistory()
        clock += 10
        store.time("B", 120.0) // watched again before Undo: the newer entry wins
        clock += 10
        store.time("C", 30.0)

        store.restoreHistory(cleared)
        runCurrent()
        assertEquals(listOf("C", "B", "A"), store.history.value.map { it.videoId })
        assertEquals(115.0, store.resumePosition("B")!!, 0.0)
        assertEquals(55.0, store.resumePosition("A")!!, 0.0)
        assertEquals(listOf("C", "B", "A"), store().history.value.map { it.videoId })
    }

    @Test fun resumePositionAppliesTheRules() = runTest {
        val store = store()
        store.time("A", 60.0)
        assertEquals(55.0, store.resumePosition("A")!!, 0.0)
        assertNull(store.resumePosition("unknown"))
    }

    // --- channel cache ---

    @Test fun channelsAreCachedWithTheirFetchTime() = runTest {
        val store = store()
        store.cacheChannel("/@someone", ChannelInfo("UC" + "a".repeat(22), "Someone", "https://x/avatar"))
        runCurrent()
        val cached = store().cachedChannel("/@someone")!!
        assertEquals("Someone", cached.info.name)
        assertEquals(clock, cached.fetchedAt)
    }
}
