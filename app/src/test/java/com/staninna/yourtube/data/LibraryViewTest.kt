package com.staninna.yourtube.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    private fun view(segment: ItemType, filter: TagFilter = TagFilter(), query: String = "", lib: Library = library) =
        LibraryFilter.view(lib, segment, filter, query)

    @Test fun aSegmentIsOneTypeNewestFirst() {
        assertEquals(listOf("v2", "v3", "v1"), ids(view(ItemType.VIDEO)))
        assertEquals(listOf("c2", "c1"), ids(view(ItemType.CHANNEL)))
    }

    @Test fun theSheetOffersTheSegmentsTagsInPoolOrderWithCounts() {
        val videos = view(ItemType.VIDEO).tags
        assertEquals(listOf(music, talks), videos.tags)
        assertEquals(mapOf("music" to 1, "talks" to 1), videos.counts)
        assertEquals(1, videos.untagged)
        assertEquals(listOf(news, music), view(ItemType.CHANNEL).tags.tags)
        assertTrue(view(ItemType.PLAYLIST).tags.tags.isEmpty())
    }

    @Test fun anyMatchesOneSelectedTag() {
        assertEquals(listOf("v1"), ids(view(ItemType.VIDEO, TagFilter(setOf("music")))))
        assertEquals(listOf("v2", "v1"), ids(view(ItemType.VIDEO, TagFilter(setOf("music", "talks")))))
    }

    @Test fun allNeedsEverySelectedTag() {
        val both = TagFilter(setOf("music", "news"), match = TagMatch.ALL)
        assertEquals(listOf("c2"), ids(view(ItemType.CHANNEL, both)))
        val none = view(ItemType.VIDEO, TagFilter(setOf("music", "talks"), match = TagMatch.ALL))
        assertTrue(none.items.isEmpty())
        assertEquals(LibraryView.EmptyReason.NO_MATCH, none.emptyReason)
        assertFalse("tags alone, no search", none.searching)
    }

    @Test fun untaggedShowsOnlyItemsWithoutTags() {
        assertEquals(listOf("v3"), ids(view(ItemType.VIDEO, TagFilter(untagged = true))))
        // Every channel has a tag, so Untagged drops out and the segment shows whole.
        val channels = view(ItemType.CHANNEL, TagFilter(untagged = true))
        assertFalse(channels.tags.filter.untagged)
        assertEquals(2, channels.items.size)
    }

    @Test fun aTagThatLeftTheSegmentFallsBackToAll() {
        val v = view(ItemType.VIDEO, TagFilter(setOf("news")))
        assertFalse(v.tags.filter.active)
        assertEquals(3, v.items.size)
        // One gone, one still there: only the present one filters.
        assertEquals(setOf("talks"), view(ItemType.VIDEO, TagFilter(setOf("news", "talks"))).tags.filter.tags)
    }

    @Test fun aTagThatLeftAndCameBackIsNotSecretlySelected() {
        // Music is selected among videos, then its only video loses the tag.
        val withoutMusic = library.copy(items = library.items.map { if (it.id == "v1") it.copy(tagIds = emptySet()) else it })
        val stored = LibraryFilter.inEffect(withoutMusic, ItemType.VIDEO, TagFilter(setOf("music"))).toggle("talks")
        assertEquals(setOf("talks"), stored.tags)
        // Music comes back on a video: it shows unselected, and only talks filters.
        assertEquals(setOf("talks"), view(ItemType.VIDEO, stored).tags.filter.tags)
    }

    @Test fun nameSortIsCaseInsensitiveAndLocaleAwareWithTiesNewestFirst() {
        fun titled(id: String, title: String, at: Long) = StarredItem(id, ItemType.VIDEO, title, url = "u", addedAt = at)
        val lib = Library(
            listOf(
                titled("zed", "zebra", 1), titled("eve", "Eve", 2), titled("emile", "Émile", 3),
                titled("apple1", "apple", 4), titled("apple2", "Apple", 5), titled("banana", "Banana", 6),
            ),
        )
        val byName = LibraryFilter.view(lib, ItemType.VIDEO, TagFilter(), "", LibrarySort.NAME, Locale.ENGLISH)
        assertEquals(listOf("apple2", "apple1", "banana", "emile", "eve", "zed"), ids(byName))
        assertEquals(LibrarySort.NAME, byName.sort)
        val recent = LibraryFilter.view(lib, ItemType.VIDEO, TagFilter(), "", LibrarySort.RECENT, Locale.ENGLISH)
        assertEquals(listOf("banana", "apple2", "apple1", "emile", "eve", "zed"), ids(recent))
    }

    @Test fun theSortOrdersTheFilteredRows() {
        val byName = LibraryFilter.view(library, ItemType.VIDEO, TagFilter(setOf("music", "talks")), "", LibrarySort.NAME, Locale.ENGLISH)
        assertEquals(listOf("v1", "v2"), ids(byName))
    }

    @Test fun searchAppearsPastThirtyAndOnlyThenFilters() {
        val small = view(ItemType.VIDEO, query = "nothing like this")
        assertFalse(small.searchable)
        assertEquals("a query on a short segment is ignored", 3, small.items.size)

        val many = Library(
            (1..31).map { item("v$it", ItemType.VIDEO, it.toLong(), author = if (it == 7) "Lofi Girl" else "Someone") },
        )
        val big = view(ItemType.VIDEO, query = "  LOFI ", lib = many)
        assertTrue(big.searchable)
        assertEquals(listOf("v7"), ids(big))
        assertEquals(listOf("v12"), ids(view(ItemType.VIDEO, query = "title v12", lib = many)))
        assertEquals(31, big.segmentSize)
    }

    @Test fun emptyReasonsTellLibrarySegmentAndNoMatchApart() {
        assertEquals(LibraryView.EmptyReason.LIBRARY, view(ItemType.CHANNEL, lib = Library()).emptyReason)
        assertEquals(LibraryView.EmptyReason.SEGMENT, view(ItemType.PLAYLIST).emptyReason)
        val many = Library((1..31).map { item("v$it", ItemType.VIDEO, it.toLong()) })
        val searched = view(ItemType.VIDEO, query = "zzz", lib = many)
        assertEquals(LibraryView.EmptyReason.NO_MATCH, searched.emptyReason)
        assertTrue(searched.searching)
        assertNull(view(ItemType.VIDEO).emptyReason)
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
