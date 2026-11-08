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

    @Test fun starringIsNewestFirstAndUnstarringRemoves() = runTest {
        val store = store()
        store.star(video("A"))
        clock += 10
        store.star(video("B"))
        store.star(video("A")) // already starred: stays where it is
        val items = store.library.value.items
        assertEquals(listOf("B", "A"), items.map { it.id })
        assertEquals(listOf(clock, clock - 10), items.map { it.addedAt })
        store.unstar("A")
        assertEquals(listOf("B"), store.library.value.items.map { it.id })
    }

    @Test fun libraryAndSettingsAreWrittenRightAwayAndReadBack() = runTest {
        val store = store()
        store.star(video("A"))
        store.updateSettings { it.copy(removeShorts = true) }
        runCurrent()

        val text = File(dir, "library.json").readText()
        assertTrue(text, text.contains("\"schemaVersion\":1"))
        val reloaded = store()
        assertEquals(listOf("A"), reloaded.library.value.items.map { it.id })
        assertTrue(reloaded.settings.value.removeShorts)
        assertTrue(reloaded.corruptFiles.isEmpty())
    }

    @Test fun theFeedPageSizeIsStoredByItsCount() = runTest {
        val store = store()
        assertEquals(FeedPageSize.P50, store.settings.value.feedPageSize)
        store.updateSettings { it.copy(feedPageSize = FeedPageSize.P100) }
        runCurrent()
        val text = File(dir, "settings.json").readText()
        assertTrue(text, text.contains("\"feedPageSize\":\"100\""))
        assertEquals(FeedPageSize.P100, store().settings.value.feedPageSize)
    }

    @Test fun theFeedCacheIsCappedReadBackAndLeftOutOfBackups() = runTest {
        val store = store()
        val uc = "UC" + "a".repeat(22)
        val lengths = listOf(VideoLength.Known(754), VideoLength.Live(5), VideoLength.Unlisted(6), null)
        val videos = List(40) { FeedVideo("v%010d".format(it), "Video $it", "Channel", uc, 1_000L - it, views = it * 1_000L, length = lengths[it % 4]) }
        store.cacheFeeds(mapOf(uc to videos))
        runCurrent()
        val reloaded = store()
        assertEquals(videos.take(Store.FEED_CACHE_PER_CHANNEL), reloaded.feeds.value.getValue(uc))
        assertTrue(reloaded.corruptFiles.isEmpty())
        assertFalse("a cache is no backup", BackupCodec.encode(reloaded.backup()).contains("Video 1"))
    }

    @Test fun aFeedCacheFromBeforeViewsAndLengthsStillReads() = runTest {
        val uc = "UC" + "a".repeat(22)
        File(dir, "feeds.json").writeText(
            """{"schemaVersion":1,"data":{"$uc":[{"videoId":"aaaaaaaaaaa","title":"T","author":"A","channelId":"$uc","publishedAt":5,"isShort":false}]}}""",
        )
        val video = store().feeds.value.getValue(uc).single()
        assertNull(video.views)
        assertNull(video.length)
    }

    @Test fun concurrentUpdatesAreAllKept() = runTest {
        val store = store()
        val threads = (0 until 8).map { t ->
            thread { repeat(50) { i -> store.star(video("v$t-$i")) } }
        }
        threads.forEach { it.join() }
        advanceUntilIdle()

        assertEquals(400, store.library.value.items.size)
        assertEquals(400, store().library.value.items.size)
    }

    @Test fun deletingATagDetachesItInTheSameWrite() = runTest {
        val store = store()
        store.star(video("A"))
        val music = store.createTag("Music")
        val talks = store.createTag("Talks")
        store.setItemTag("A", music!!.id, attached = true)
        store.setItemTag("A", talks!!.id, attached = true)
        store.setItemTag("A", "unknown", attached = true)
        assertEquals(setOf(music.id, talks.id), store.library.value.items.single().tagIds)
        assertTrue(music.color != talks.color)

        store.deleteTag(music.id)
        val lib = store.library.value
        assertEquals(listOf("Talks"), lib.tags.map { it.name })
        assertEquals(setOf(talks.id), lib.items.single().tagIds)
        assertEquals(listOf("A"), lib.items.map { it.id })
    }

    @Test fun restoringAnUnstarKeepsItsDateAndSurvivingTags() = runTest {
        val store = store()
        store.star(video("A"))
        clock += 10
        store.star(video("B"))
        val keep = store.createTag("Keep", attachTo = "A")!!
        val gone = store.createTag("Gone", attachTo = "A")!!
        val unstarred = store.library.value.items.first { it.id == "A" }

        store.unstar("A")
        store.deleteTag(gone.id)
        store.restoreStar(unstarred)
        val restored = store.library.value.items
        assertEquals(listOf("B", "A"), restored.map { it.id })
        assertEquals(unstarred.addedAt, restored.last().addedAt)
        assertEquals(setOf(keep.id), restored.last().tagIds)
    }

    @Test fun restoringAfterARestarKeepsTheNewerStar() = runTest {
        val store = store()
        store.star(video("A"))
        val old = store.library.value.items.single()
        store.unstar("A")
        clock += 10
        store.star(video("A"))
        store.restoreStar(old)
        assertEquals(listOf(clock), store.library.value.items.map { it.addedAt })
    }

    @Test fun creatingATagAttachesItAndReusesAnExistingName() = runTest {
        val store = store()
        store.star(video("A"))
        store.star(video("B"))
        val music = store.createTag("  Music  ", attachTo = "A")!!
        assertEquals("Music", music.name)
        val again = store.createTag("music", attachTo = "B")!!
        assertEquals(music.id, again.id)
        assertEquals(1, store.library.value.tags.size)
        assertTrue(store.library.value.items.all { music.id in it.tagIds })
        assertNull(store.createTag("   ", attachTo = "A"))
        assertEquals(TagPalette.colors[0].argb, music.color)
        assertEquals(TagPalette.colors[1].argb, store.createTag("Talks")!!.color)
    }

    @Test fun detachingATagKeepsTheOthers() = runTest {
        val store = store()
        store.star(video("A"))
        val music = store.createTag("Music", attachTo = "A")!!
        val talks = store.createTag("Talks", attachTo = "A")!!
        store.setItemTag("A", music.id, attached = false)
        assertEquals(setOf(talks.id), store.library.value.items.single().tagIds)
    }

    @Test fun renamingRefusesBlankAndTakenNames() = runTest {
        val store = store()
        val music = store.createTag("Music")!!
        store.createTag("Talks")
        store.renameTag(music.id, "talks")
        store.renameTag(music.id, "  ")
        assertEquals("Music", store.library.value.tags.first().name)
        store.renameTag(music.id, "music") // its own name in another case is fine
        assertEquals("music", store.library.value.tags.first().name)
        store.renameTag(music.id, " Lo-fi   beats ")
        assertEquals("Lo-fi beats", store.library.value.tags.first().name)
    }

    @Test fun recoloringChangesOneTag() = runTest {
        val store = store()
        val music = store.createTag("Music")!!
        val talks = store.createTag("Talks")!!
        store.recolorTag(music.id, TagPalette.colors[5].argb)
        assertEquals(listOf(TagPalette.colors[5].argb, talks.color), store.library.value.tags.map { it.color })
    }

    @Test fun avatarsFillOnlyChannelsWithoutOne() = runTest {
        val store = store()
        val channel = StarredItem(id = "UC1", type = ItemType.CHANNEL, title = "C", url = "u", channelId = "UC1")
        store.star(channel)
        store.star(channel.copy(id = "UC2", thumbnail = "https://kept"))
        store.setChannelAvatar("UC1", "https://new")
        store.setChannelAvatar("UC2", "https://new")
        assertEquals(mapOf("UC1" to "https://new", "UC2" to "https://kept"), store.library.value.items.associate { it.id to it.thumbnail })
    }

    @Test fun removingOneEntryCanBeUndoneWithItsPosition() = runTest {
        val store = store()
        store.time("A", 60.0)
        clock += 10
        store.time("B", 90.0)
        val removed = store.history.value.first { it.videoId == "A" }
        store.removeHistory("A")
        assertNull(store.resumePosition("A"))

        store.restoreHistory(listOf(removed))
        assertEquals(listOf("B", "A"), store.history.value.map { it.videoId })
        assertEquals(55.0, store.resumePosition("A")!!, 0.0)
    }

    // --- backup ---

    @Test fun importMergesAndIsWrittenRightAway() = runTest {
        val store = store()
        store.star(video("A"))
        val music = store.createTag("Music", attachTo = "A")!!
        store.time("aaaaaaaaaaa", 40.0)
        val incoming = Backup(
            library = Library(
                items = listOf(video("A").copy(tagIds = setOf("t-talks")), video("B").copy(tagIds = setOf("t-music"))),
                tags = listOf(Tag("t-music", "MUSIC", 1), Tag("t-talks", "Talks", 2)),
            ),
            history = listOf(HistoryEntry("bbbbbbbbbbb", position = 30.0, duration = 300.0, watchedAt = 5)),
            settings = Settings(removeShorts = true),
        )
        val report = store.import(incoming)
        runCurrent()

        assertEquals(ImportReport(itemsAdded = 1, itemsUpdated = 1, tagsAdded = 1, historyAdded = 1), report)
        val reloaded = store()
        val lib = reloaded.library.value
        assertEquals(listOf("Music", "Talks"), lib.tags.map { it.name })
        assertEquals(setOf(music.id), lib.items.first { it.id == "B" }.tagIds)
        assertEquals(2, lib.items.first { it.id == "A" }.tagIds.size)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), reloaded.history.value.map { it.videoId })
        assertTrue(reloaded.settings.value.removeShorts)
    }

    @Test fun importIntoAFullHistoryStaysCapped() = runTest {
        val store = store()
        repeat(Store.HISTORY_CAP) {
            clock += 1
            store.time("v$it", 10.0)
        }
        val older = (0 until 10).map { HistoryEntry("old$it", watchedAt = it.toLong()) }
        val newer = (0 until 10).map { HistoryEntry("new$it", watchedAt = clock + 1 + it) }
        val report = store.import(Backup(history = older + newer))
        val history = store.history.value
        assertEquals(Store.HISTORY_CAP, history.size)
        assertEquals("only the newer entries made it", 10, report.historyAdded)
        assertEquals("new9", history.first().videoId)
        assertTrue(history.none { it.videoId.startsWith("old") })
    }

    @Test fun backupHoldsPlaybackNotYetWritten() = runTest {
        val store = store()
        store.time("A", 42.0) // still inside the debounce window
        assertEquals(42.0, store.backup().history.single().position, 0.0)
        assertEquals(clock, store.backup().exportedAt)
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
        assertEquals(setOf("library.json"), store.corruptNotice.value)
        store.dismissCorruptNotice()
        assertNull("answered once, gone", store.corruptNotice.value)
        assertTrue(store.library.value.items.isEmpty())
        assertEquals("{not json", File(dir, "library.json.corrupt").readText())

        store.star(video("A"))
        runCurrent()
        val next = store()
        assertEquals(listOf("A"), next.library.value.items.map { it.id })
        assertNull("the next process finds nothing to report", next.corruptNotice.value)
        assertEquals("{not json", File(dir, "library.json.corrupt").readText())
    }

    @Test fun aCorruptCacheIsSetAsideWithoutANotice() = runTest {
        File(dir, "feeds.json").writeText("{not json")
        File(dir, "channels.json").writeText("{not json")
        val store = store()
        assertEquals(setOf("feeds.json", "channels.json"), store.corruptFiles)
        assertNull("caches refill themselves; nothing to tell the user", store.corruptNotice.value)
        assertTrue(store.feeds.value.isEmpty())
    }

    @Test fun unknownSchemaVersionCountsAsCorrupt() = runTest {
        File(dir, "settings.json").writeText("""{"schemaVersion":99,"data":{"hideShorts":true}}""")
        val store = store()
        assertEquals(setOf("settings.json"), store.corruptFiles)
        assertFalse(store.settings.value.removeShorts)
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
