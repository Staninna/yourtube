package com.staninna.yourtube.ui

import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.LibraryFilter
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Tag
import com.staninna.yourtube.data.TagChoices
import com.staninna.yourtube.data.TagFilter
import com.staninna.yourtube.data.TagMatch
import com.staninna.yourtube.feed.FeedControls
import com.staninna.yourtube.feed.FeedResults
import com.staninna.yourtube.feed.FeedRules
import com.staninna.yourtube.data.FeedPageSize
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.feed.ChannelFetch
import com.staninna.yourtube.feed.FeedSource
import org.junit.Assert.assertEquals
import org.junit.Test

class TagFilterTextTest {

    private val music = Tag("music", "Music", 1)
    private val gaming = Tag("gaming", "Gaming", 2)
    private val retro = Tag("retro", "Retro", 3)
    private val pool = listOf(music, gaming, retro)

    private fun item(id: String, type: ItemType, vararg tags: Tag) =
        StarredItem(id, type, "Title $id", url = "u", channelId = id, tagIds = tags.map { it.id }.toSet())

    private val items = listOf(item("a", ItemType.VIDEO, music, gaming, retro), item("b", ItemType.VIDEO))

    private fun choices(filter: TagFilter) = TagChoices.of(items, pool, filter)

    @Test fun theButtonNamesWhatIsSelected() {
        assertEquals("All tags", TagFilterText.label(choices(TagFilter())))
        assertEquals("Untagged", TagFilterText.label(choices(TagFilter(untagged = true))))
        assertEquals("Music", TagFilterText.label(choices(TagFilter(setOf("music")))))
        // Pool order, whatever order they were picked in.
        assertEquals("Music, Gaming", TagFilterText.label(choices(TagFilter(setOf("gaming", "music")))))
        assertEquals("Music +2", TagFilterText.label(choices(TagFilter(setOf("music", "gaming", "retro")))))
        assertEquals("Music & Gaming", TagFilterText.label(choices(TagFilter(setOf("music", "gaming"), match = TagMatch.ALL))))
        assertEquals("Music & 2 more", TagFilterText.label(choices(TagFilter(setOf("music", "gaming", "retro"), match = TagMatch.ALL))))
    }

    @Test fun talkBackHearsEveryName() {
        assertEquals("Tags: all", TagFilterText.description(choices(TagFilter())))
        assertEquals("Tags: untagged only", TagFilterText.description(choices(TagFilter(untagged = true))))
        assertEquals("Tags: any of Music, Gaming or Retro", TagFilterText.description(choices(TagFilter(setOf("music", "gaming", "retro")))))
        assertEquals("Tags: all of Music and Gaming", TagFilterText.description(choices(TagFilter(setOf("music", "gaming"), match = TagMatch.ALL))))
    }

    @Test fun libraryEmptyStatesSayWhichFilterLeftNothing() {
        val lib = Library(listOf(item("a", ItemType.VIDEO, music), item("b", ItemType.VIDEO, gaming)), pool)
        val all = LibraryFilter.view(lib, ItemType.VIDEO, TagFilter(setOf("music", "gaming"), match = TagMatch.ALL), "")
        assertEquals(
            EmptyCopy("Nothing has all these tags", "No videos carry Music and Gaming together.", EmptyAction.CLEAR_TAGS),
            TagFilterText.libraryNoMatch(all, "videos"),
        )

        val many = Library((1..31).map { item("v$it", ItemType.VIDEO) } + item("t", ItemType.VIDEO, music), pool)
        val search = LibraryFilter.view(many, ItemType.VIDEO, TagFilter(), "zzz")
        assertEquals(
            EmptyCopy("Nothing matches", "No videos have that in their name or channel.", EmptyAction.CLEAR_SEARCH),
            TagFilterText.libraryNoMatch(search, "videos"),
        )
        val untaggedSearch = LibraryFilter.view(many, ItemType.VIDEO, TagFilter(untagged = true), "zzz")
        assertEquals("No untagged videos have that in their name or channel.", TagFilterText.libraryNoMatch(untaggedSearch, "videos").body)
        val taggedSearch = LibraryFilter.view(many, ItemType.VIDEO, TagFilter(setOf("music")), "zzz")
        assertEquals("No videos under these tags have that in their name or channel.", TagFilterText.libraryNoMatch(taggedSearch, "videos").body)
    }

    @Test fun feedsEmptyStatesSayWhichFilterLeftNothing() {
        val lib = Library(listOf(item("A", ItemType.CHANNEL, music), item("B", ItemType.CHANNEL, gaming), item("C", ItemType.CHANNEL)), pool)
        val results = FeedResults().finish("A", ChannelFetch.Ok(listOf(FeedVideo("aaaaaaaaaaa", "V", "A", "A", 1)), FeedSource.RSS))
        fun copy(filter: TagFilter) = TagFilterText.feedNoMatch(FeedRules.view(lib, results, FeedControls(filter), FeedPageSize.P50, false))

        assertEquals(
            EmptyCopy("No channel has all these tags", "None of your channels carries Music and Gaming together.", EmptyAction.SHOW_ALL),
            copy(TagFilter(setOf("music", "gaming"), match = TagMatch.ALL)),
        )
        assertEquals("Nothing untagged", copy(TagFilter(untagged = true)).title)
        assertEquals("Nothing under these tags", copy(TagFilter(setOf("gaming"))).title)
    }
}
