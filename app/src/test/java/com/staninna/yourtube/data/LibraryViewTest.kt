package com.staninna.yourtube.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewTest {

    private val music = Tag("music", "Music", 1)
    private val talks = Tag("talks", "Talks", 2)
    private val news = Tag("news", "News", 3)

    private fun item(id: String, type: ItemType, addedAt: Long, vararg tags: Tag, author: String? = null) =
        StarredItem(id, type, "Title $id", author = author, url = "u", tagIds = tags.map { it.id }.toSet(), addedAt = addedAt)

    private val library = Library(
        items = listOf(
            item("c1", ItemType.CHANNEL, 1, music),
            item("v1", ItemType.VIDEO, 2, music),
            item("v2", ItemType.VIDEO, 5, talks),
            item("v3", ItemType.VIDEO, 3),
            item("c2", ItemType.CHANNEL, 4, news, music),
        ),
        tags = listOf(news, music, talks),
    )

    private fun ids(view: LibraryView) = view.items.map { it.id }

    @Test fun aSegmentIsOneTypeNewestFirst() {
        assertEquals(listOf("v2", "v3", "v1"), ids(LibraryFilter.view(library, ItemType.VIDEO, emptySet(), "")))
        assertEquals(listOf("c2", "c1"), ids(LibraryFilter.view(library, ItemType.CHANNEL, emptySet(), "")))
    }

    @Test fun chipsAreTheSegmentsTagsInPoolOrder() {
        assertEquals(listOf(music, talks), LibraryFilter.view(library, ItemType.VIDEO, emptySet(), "").chips)
        assertEquals(listOf(news, music), LibraryFilter.view(library, ItemType.CHANNEL, emptySet(), "").chips)
        assertTrue(LibraryFilter.view(library, ItemType.PLAYLIST, emptySet(), "").chips.isEmpty())
    }

    @Test fun selectedChipsAreOr() {
        assertEquals(listOf("v1"), ids(LibraryFilter.view(library, ItemType.VIDEO, setOf("music"), "")))
        assertEquals(listOf("v2", "v1"), ids(LibraryFilter.view(library, ItemType.VIDEO, setOf("music", "talks"), "")))
    }

    @Test fun aChipThatLeftTheSegmentFallsBackToAll() {
        val view = LibraryFilter.view(library, ItemType.VIDEO, setOf("news"), "")
        assertTrue(view.selected.isEmpty())
        assertEquals(3, view.items.size)
        // One gone, one still there: only the present one filters.
        assertEquals(setOf("talks"), LibraryFilter.view(library, ItemType.VIDEO, setOf("news", "talks"), "").selected)
    }

    @Test fun togglingFlipsOneChip() {
        assertEquals(setOf("music"), LibraryFilter.toggleChip(library, ItemType.VIDEO, emptySet(), "music"))
        assertEquals(setOf("talks"), LibraryFilter.toggleChip(library, ItemType.VIDEO, setOf("music", "talks"), "music"))
    }

    @Test fun aChipThatLeftAndCameBackIsNotSecretlySelected() {
        // Music is selected among videos, then its only video loses the tag.
        val withoutMusic = library.copy(items = library.items.map { if (it.id == "v1") it.copy(tagIds = emptySet()) else it })
        val stored = LibraryFilter.toggleChip(withoutMusic, ItemType.VIDEO, setOf("music"), "talks")
        assertEquals(setOf("talks"), stored)
        // Music comes back on a video: it shows unselected, and only talks filters.
        assertEquals(setOf("talks"), LibraryFilter.view(library, ItemType.VIDEO, stored, "").selected)
    }

    @Test fun searchAppearsPastThirtyAndOnlyThenFilters() {
        val small = LibraryFilter.view(library, ItemType.VIDEO, emptySet(), "nothing like this")
        assertFalse(small.searchable)
        assertEquals("a query on a short segment is ignored", 3, small.items.size)

        val many = Library(
            (1..31).map { item("v$it", ItemType.VIDEO, it.toLong(), author = if (it == 7) "Lofi Girl" else "Someone") },
        )
        val big = LibraryFilter.view(many, ItemType.VIDEO, emptySet(), "  LOFI ")
        assertTrue(big.searchable)
        assertEquals(listOf("v7"), ids(big))
        assertEquals(listOf("v12"), ids(LibraryFilter.view(many, ItemType.VIDEO, emptySet(), "title v12")))
        assertEquals(31, big.segmentSize)
    }

    @Test fun emptyReasonsTellLibrarySegmentAndNoMatchApart() {
        assertEquals(LibraryView.EmptyReason.LIBRARY, LibraryFilter.view(Library(), ItemType.CHANNEL, emptySet(), "").emptyReason)
        assertEquals(LibraryView.EmptyReason.SEGMENT, LibraryFilter.view(library, ItemType.PLAYLIST, emptySet(), "").emptyReason)
        val many = Library((1..31).map { item("v$it", ItemType.VIDEO, it.toLong()) })
        assertEquals(LibraryView.EmptyReason.NO_MATCH, LibraryFilter.view(many, ItemType.VIDEO, emptySet(), "zzz").emptyReason)
        assertNull(LibraryFilter.view(library, ItemType.VIDEO, emptySet(), "").emptyReason)
    }

    @Test fun tagNamesCountsAndMissingAvatars() {
        assertEquals(listOf("News", "Music"), LibraryFilter.tagNames(library.items.last(), library.tags))
        assertEquals(mapOf("music" to 3, "talks" to 1, "news" to 1), LibraryFilter.tagCounts(library))
        val withAvatar = library.copy(items = library.items.map { if (it.id == "c1") it.copy(thumbnail = "https://a") else it })
        assertEquals(listOf("c2"), LibraryFilter.missingAvatars(withAvatar))
    }

    // --- tag names ---

    @Test fun namesAreTrimmedCollapsedAndCut() {
        assertEquals("Lo-fi beats", TagNames.normalize("  Lo-fi \n  beats "))
        assertNull(TagNames.normalize(" \t "))
        assertEquals(TagNames.MAX_LENGTH, TagNames.normalize("x".repeat(100))!!.length)
    }

    @Test fun clashesIgnoreCaseAndTheTagItself() {
        assertEquals(music, TagNames.clash(" MUSIC ", library.tags))
        assertNull(TagNames.clash("music", library.tags, exceptId = "music"))
        assertNull(TagNames.clash("Jazz", library.tags))
        assertNull(TagNames.clash("  ", library.tags))
    }

    // --- tag sheet ---

    private val channelId = "UC" + "c".repeat(22)

    @Test fun aVideosSheetOffersItsChannel() {
        val video = StarredItem("vid", ItemType.VIDEO, "V", author = "Chan", url = "u", channelId = channelId)
        val sheet = TagSheet.of(Library(listOf(video), listOf(music)), "vid")!!
        assertEquals(video, sheet.item)
        assertEquals(listOf(music), sheet.tags)
        val channel = sheet.channel!!
        assertEquals(channelId, channel.id)
        assertEquals(ItemType.CHANNEL, channel.type)
        assertEquals("Chan", channel.title)
        assertFalse(sheet.channelStarred)

        val stored = channel.copy(thumbnail = "https://avatar", addedAt = 9)
        val starred = TagSheet.of(Library(listOf(video, stored)), "vid")!!
        assertTrue(starred.channelStarred)
        assertEquals("the stored channel, with its avatar", stored, starred.channel)
    }

    @Test fun noChannelSectionWithoutAChannelAndNoSheetOnceUnstarred() {
        val video = StarredItem("vid", ItemType.VIDEO, "V", author = "Chan", url = "u")
        assertNull(TagSheet.of(Library(listOf(video)), "vid")!!.channel)
        assertNull(TagSheet.of(Library(listOf(item("c1", ItemType.CHANNEL, 1))), "c1")!!.channel)
        assertNull(TagSheet.of(Library(), "vid"))
    }
}
