package com.staninna.yourtube.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupTest {

    private companion object {
        const val NOW = 1_000_000L
    }

    private val channelId = "UC" + "c".repeat(22)

    private fun video(id: String, addedAt: Long = 0, tags: Set<String> = emptySet()) = StarredItem(
        id = id, type = ItemType.VIDEO, title = "Video $id", author = "Someone",
        thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg", url = "https://m.youtube.com/watch?v=$id",
        channelId = channelId, tagIds = tags, addedAt = addedAt,
    )

    private fun entry(id: String, watchedAt: Long, position: Double = 60.0) =
        HistoryEntry(id, VideoMeta(id, "Title $id", "Someone", channelId), position, 300.0, watchedAt)

    private val full = Backup(
        exportedAt = 1234,
        library = Library(
            items = listOf(
                video("aaaaaaaaaaa", addedAt = 2, tags = setOf("t1")),
                StarredItem(channelId, ItemType.CHANNEL, "A channel", "A channel", "https://yt3/avatar", "https://m.youtube.com/channel/$channelId", channelId, setOf("t1", "t2"), 1),
                StarredItem("PLxyz", ItemType.PLAYLIST, "A playlist", url = "https://m.youtube.com/playlist?list=PLxyz"),
            ),
            tags = listOf(Tag("t1", "Music", TagPalette.colors[0].argb), Tag("t2", "Talks", TagPalette.colors[1].argb)),
        ),
        history = listOf(entry("aaaaaaaaaaa", 20), entry("bbbbbbbbbbb", 10)),
        settings = Settings(hideShorts = true, dynamicColor = false),
    )

    private fun valid(text: String): BackupRead.Valid = BackupCodec.decode(text, NOW) as BackupRead.Valid

    private fun problem(text: String): BackupProblem? = (BackupCodec.decode(text, NOW) as? BackupRead.Invalid)?.problem

    // --- export and validation ---

    @Test fun exportRoundTripsEverything() {
        val text = BackupCodec.encode(full)
        assertTrue(text, text.contains("\"format\":\"yourtube-backup\""))
        assertTrue(text, text.contains("\"schemaVersion\":1"))
        val read = valid(text)
        assertEquals(full, read.backup)
        assertEquals(0, read.skipped)
    }

    @Test fun fileNameCarriesTheDate() =
        assertEquals("yourtube-backup-2026-11-08.json", BackupCodec.fileName(LocalDate.of(2026, 11, 8)))

    @Test fun garbageIsNotABackup() {
        for (text in listOf("", "not json", "[1,2]", "42", "{", """{"schemaVersion":1}""", """{"format":"other","schemaVersion":1}""", """{"format":7}""")) {
            assertEquals(text, BackupProblem.NOT_A_BACKUP, problem(text))
        }
    }

    @Test fun versionsAreChecked() {
        assertEquals(BackupProblem.NEWER_VERSION, problem("""{"format":"yourtube-backup","schemaVersion":2}"""))
        assertEquals(BackupProblem.DAMAGED, problem("""{"format":"yourtube-backup","schemaVersion":0}"""))
        assertEquals(BackupProblem.DAMAGED, problem("""{"format":"yourtube-backup","schemaVersion":"one"}"""))
        assertEquals(BackupProblem.DAMAGED, problem("""{"format":"yourtube-backup"}"""))
    }

    @Test fun wrongShapesAreDamaged() {
        val head = """"format":"yourtube-backup","schemaVersion":1"""
        assertEquals(BackupProblem.DAMAGED, problem("""{$head,"library":[]}"""))
        assertEquals(BackupProblem.DAMAGED, problem("""{$head,"history":[{"videoId":"aaaaaaaaaaa"}]}"""))
        assertEquals(BackupProblem.DAMAGED, problem("""{$head,"library":{"items":[{"id":"x","type":"ALIEN","title":"t","url":"u"}]}}"""))
        assertEquals(BackupProblem.DAMAGED, problem("""{$head,"settings":{"hideShorts":"maybe"}}"""))
    }

    @Test fun missingSectionsAndUnknownKeysAreFine() {
        val read = valid("""{"format":"yourtube-backup","schemaVersion":1,"fromTheFuture":true}""")
        assertEquals(Backup(), read.backup)
    }

    @Test fun entriesBreakingInvariantsAreSkippedAndCounted() {
        val bad = full.copy(
            library = full.library.copy(
                items = full.library.items + listOf(
                    video("short"), // not a video id
                    video("javascript:alert(1)"),
                    video("ccccccccccc").copy(title = " "),
                    video("aaaaaaaaaaa"), // duplicate id: the first wins
                    StarredItem("not-a-uc-id", ItemType.CHANNEL, "C", url = "u"),
                ),
                tags = full.library.tags + listOf(Tag("", "Nameless id", 3), Tag("t3", "   ", 3), Tag("t1", "Dupe", 3)),
            ),
            history = full.history + listOf(entry("bad", 1), entry("aaaaaaaaaaa", 1)),
        )
        val read = valid(BackupCodec.encode(bad))
        assertEquals(full.library.items.map { it.id }, read.backup.library.items.map { it.id })
        assertEquals(full.library.tags, read.backup.library.tags)
        assertEquals(full.history.map { it.videoId }, read.backup.history.map { it.videoId })
        assertEquals(5 + 3 + 2, read.skipped)
    }

    @Test fun linksAreRebuiltAndFieldsRepaired() {
        val evil = video("aaaaaaaaaaa", tags = setOf("t1", "dangling")).copy(
            url = "javascript:alert(1)", thumbnail = "http://plain", channelId = "nope",
        )
        val history = HistoryEntry("bbbbbbbbbbb", VideoMeta("other", "Wrong", null, null), position = 900.0, duration = 300.0, watchedAt = 1)
        val read = valid(BackupCodec.encode(Backup(library = Library(listOf(evil), listOf(Tag("t1", " Lo-fi   beats ", TagPalette.colors[2].argb))), history = listOf(history))))
        val item = read.backup.library.items.single()
        assertEquals("https://m.youtube.com/watch?v=aaaaaaaaaaa", item.url)
        assertNull(item.thumbnail)
        assertNull(item.channelId)
        assertEquals(setOf("t1"), item.tagIds)
        assertEquals("Lo-fi beats", read.backup.library.tags.single().name)
        val h = read.backup.history.single()
        assertNull("meta for another video is dropped", h.meta)
        assertEquals(300.0, h.position, 0.0)
    }

    @Test fun aChannelsOwnIdBuildsItsFeed() {
        val channel = StarredItem(channelId, ItemType.CHANNEL, "C", url = "x", channelId = null)
        val item = valid(BackupCodec.encode(Backup(library = Library(listOf(channel))))).backup.library.items.single()
        assertEquals(channelId, item.channelId)
        assertEquals("https://m.youtube.com/channel/$channelId", item.url)
    }

    @Test fun tagColorsAreOpaqueAndDatesNoLaterThanNow() {
        val future = Backup(
            library = Library(
                listOf(video("aaaaaaaaaaa", addedAt = NOW + 5_000, tags = setOf("t1"))),
                listOf(Tag("t1", "See-through", 0x00123456)),
            ),
            history = listOf(entry("aaaaaaaaaaa", watchedAt = NOW + 9_000)),
        )
        val read = valid(BackupCodec.encode(future)).backup
        assertEquals(0xFF123456.toInt(), read.library.tags.single().color)
        assertEquals(NOW, read.library.items.single().addedAt)
        assertEquals(NOW, read.history.single().watchedAt)
    }

    @Test fun nonFiniteNumbersAreNotJsonSoTheFileIsDamaged() {
        val text = BackupCodec.encode(Backup(history = listOf(entry("aaaaaaaaaaa", 1)))).replace("\"position\":60.0", "\"position\":NaN")
        assertEquals(BackupProblem.DAMAGED, problem(text))
    }

    // --- merge ---

    @Test fun tagsMatchByIdThenByNameAndItemsGainThem() {
        val current = Library(
            items = listOf(video("aaaaaaaaaaa", addedAt = 5, tags = setOf("mine"))),
            tags = listOf(Tag("mine", "Music", 10), Tag("same-id", "Old name", 11)),
        )
        val incoming = Library(
            items = listOf(
                video("aaaaaaaaaaa", addedAt = 1, tags = setOf("theirs-music", "same-id")).copy(title = "Their title"),
                video("bbbbbbbbbbb", addedAt = 9, tags = setOf("theirs-new")),
            ),
            tags = listOf(Tag("theirs-music", "music", 20), Tag("same-id", "New name", 21), Tag("theirs-new", "Talks", 22)),
        )
        val result = BackupMerge.library(current, incoming)
        assertEquals(listOf(Tag("mine", "Music", 10), Tag("same-id", "Old name", 11), Tag("theirs-new", "Talks", 22)), result.library.tags)
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), result.library.items.map { it.id })
        val kept = result.library.items.last()
        assertEquals("existing fields stay", "Video aaaaaaaaaaa", kept.title)
        assertEquals(setOf("mine", "same-id"), kept.tagIds)
        assertEquals(setOf("theirs-new"), result.library.items.first().tagIds)
        assertEquals(Triple(1, 1, 1), Triple(result.itemsAdded, result.itemsUpdated, result.tagsAdded))
    }

    @Test fun newTagsDifferingOnlyInCaseBecomeOne() {
        val incoming = Library(
            items = listOf(video("aaaaaaaaaaa", tags = setOf("a")), video("bbbbbbbbbbb", tags = setOf("b"))),
            tags = listOf(Tag("a", "Jazz", 1), Tag("b", "jazz", 2)),
        )
        val result = BackupMerge.library(Library(), incoming)
        assertEquals(listOf(Tag("a", "Jazz", 1)), result.library.tags)
        assertEquals(1, result.tagsAdded)
        assertTrue(result.library.items.all { it.tagIds == setOf("a") })
    }

    @Test fun importingTheSameFileTwiceChangesNothing() {
        val once = BackupMerge.library(Library(), full.library).library
        val twice = BackupMerge.library(once, full.library)
        assertEquals(once, twice.library)
        assertEquals(Triple(0, 0, 0), Triple(twice.itemsAdded, twice.itemsUpdated, twice.tagsAdded))
        val history = BackupMerge.history(full.history, full.history, cap = 10)
        assertEquals(full.history, history.history)
        assertEquals(0, history.added)
    }

    @Test fun aMissingThumbnailIsFilledButAPresentOneStays() {
        val current = Library(listOf(video("aaaaaaaaaaa").copy(thumbnail = null), video("bbbbbbbbbbb").copy(thumbnail = "https://mine")))
        val merged = BackupMerge.library(current, Library(listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb")))).library
        assertEquals(listOf("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", "https://mine"), merged.items.map { it.thumbnail })
    }

    @Test fun historyKeepsTheMoreRecentWatchAndTheCap() {
        val current = listOf(entry("aaaaaaaaaaa", watchedAt = 30, position = 100.0), entry("bbbbbbbbbbb", watchedAt = 10, position = 10.0))
        val incoming = listOf(
            entry("aaaaaaaaaaa", watchedAt = 5, position = 1.0), // older: ignored
            entry("bbbbbbbbbbb", watchedAt = 40, position = 200.0), // newer: wins
            entry("ccccccccccc", watchedAt = 20),
            entry("ddddddddddd", watchedAt = 1), // falls off the cap
        )
        val result = BackupMerge.history(current, incoming, cap = 3)
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa", "ccccccccccc"), result.history.map { it.videoId })
        assertEquals(listOf(200.0, 100.0, 60.0), result.history.map { it.position })
        assertEquals(1, result.added)
    }
}
