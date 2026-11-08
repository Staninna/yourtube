package com.staninna.yourtube.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class TagsTest {

    private val music = Tag("music", "Music", 1)
    private val talks = Tag("talks", "Talks", 2)
    private val news = Tag("news", "News", 3)

    private fun item(id: String, vararg tags: Tag) = StarredItem(id, ItemType.VIDEO, id, url = "u", tagIds = tags.map { it.id }.toSet())

    // --- the filter ---

    @Test fun untaggedAndTagsExcludeEachOther() {
        val tagged = TagFilter().toggle("music").toggle("talks")
        assertEquals(setOf("music", "talks"), tagged.tags)
        val untagged = tagged.toggleUntagged()
        assertTrue(untagged.untagged)
        assertTrue("selecting Untagged clears the tags", untagged.tags.isEmpty())
        val back = untagged.toggle("news")
        assertFalse("selecting a tag clears Untagged", back.untagged)
        assertEquals(setOf("news"), back.tags)
        assertFalse(TagFilter(untagged = true).toggleUntagged().active)
    }

    @Test fun clearingKeepsTheMatchMode() {
        val cleared = TagFilter(setOf("music"), match = TagMatch.ALL).cleared()
        assertFalse(cleared.active)
        assertEquals(TagMatch.ALL, cleared.match)
        assertEquals(TagMatch.ALL, cleared.toggleUntagged().toggle("music").match)
    }

    @Test fun admitsByAnyAllOrUntagged() {
        val both = item("both", music, talks)
        val one = item("one", music)
        val none = item("none")
        val any = TagFilter(setOf("music", "talks"))
        assertTrue(any.admits(both) && any.admits(one) && !any.admits(none))
        val all = any.copy(match = TagMatch.ALL)
        assertTrue(all.admits(both))
        assertFalse(all.admits(one))
        val untagged = TagFilter(untagged = true)
        assertTrue(untagged.admits(none))
        assertFalse(untagged.admits(one))
        assertTrue("nothing selected lets all through", listOf(both, one, none).all { TagFilter(match = TagMatch.ALL).admits(it) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun untaggedWithTagsCannotBeBuilt() {
        TagFilter(setOf("music"), untagged = true)
    }

    @Test fun choicesCountAndKeepPoolOrder() {
        val items = listOf(item("a", talks, music), item("b", music), item("c"))
        val choices = TagChoices.of(items, listOf(news, music, talks), TagFilter(setOf("talks", "news")))
        assertEquals(listOf(music, talks), choices.tags)
        assertEquals(mapOf("music" to 2, "talks" to 1), choices.counts)
        assertEquals(1, choices.untagged)
        assertEquals("news is on no item, so it drops out", setOf("talks"), choices.filter.tags)
        assertEquals(listOf(talks), choices.selectedTags)
    }

    // --- bulk tagging ---

    @Test fun bulkStatesAreAllSomeOrNone() {
        val library = Library(listOf(item("a", music, talks), item("b", music), item("c", news)), listOf(music, talks, news))
        val sheet = BulkTagging.of(library, setOf("a", "b", "gone"))!!
        assertEquals(setOf("a", "b"), sheet.itemIds)
        assertEquals(listOf(music to TagState.ALL, talks to TagState.SOME, news to TagState.NONE), sheet.tags)
        assertNull(BulkTagging.of(library, setOf("gone")))
    }

    @Test fun tappingTakesOffATagAllCarryAndPutsOnAnyOther() {
        assertFalse(BulkTagging.attachOnTap(TagState.ALL))
        assertTrue(BulkTagging.attachOnTap(TagState.SOME))
        assertTrue(BulkTagging.attachOnTap(TagState.NONE))
    }

    // --- order ---

    @Test fun movingATagShiftsTheOthers() {
        val tags = listOf(music, talks, news)
        assertEquals(listOf(news, music, talks), TagOrder.move(tags, "news", 0))
        assertEquals(listOf(talks, news, music), TagOrder.move(tags, "music", 2))
        assertEquals("clamped", listOf(talks, news, music), TagOrder.move(tags, "music", 99))
        assertSame(tags, TagOrder.move(tags, "gone", 0))
        assertSame(tags, TagOrder.move(tags, "talks", 1))
    }

    // --- palette ---

    @Test fun thePaletteHas32NamedDistinctColorsAndKeepsTheFirstEight() {
        val colors = TagPalette.colors
        assertEquals(32, colors.size)
        assertEquals(32, colors.map { it.argb }.toSet().size)
        assertEquals(32, colors.map { it.name.lowercase() }.toSet().size)
        assertEquals(
            listOf("Red", "Blue", "Green", "Amber", "Purple", "Teal", "Pink", "Brown"),
            colors.take(8).map { it.name },
        )
        assertEquals(0xFFE57373.toInt(), colors.first().argb)
        assertEquals("Custom", TagPalette.nameOf(0xFF123456.toInt()))
        assertEquals(colors[8].argb, TagPalette.next(8))
        assertEquals(colors[0].argb, TagPalette.next(32))
    }

    @Test fun everyColorReadsAsADotOnLightAndDarkSurfaces() {
        // The M3 baseline light surface and the dark surface container, as the dots sit on them.
        val light = luminance(0xFFFEF7FF.toInt())
        val dark = luminance(0xFF211F26.toInt())
        TagPalette.colors.forEach {
            val l = luminance(it.argb)
            assertTrue("${it.name} on light", contrast(l, light) >= 1.6)
            assertTrue("${it.name} on dark", contrast(l, dark) >= 2.7)
        }
    }

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = (argb shr shift and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Double, b: Double): Double = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
