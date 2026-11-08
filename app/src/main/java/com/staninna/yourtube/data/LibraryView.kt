package com.staninna.yourtube.data

import java.text.Collator
import java.util.Locale

/** What Library shows for one segment, after its tag filter and search. */
data class LibraryView(
    val segment: ItemType,
    /** The segment's items that pass the filter, in the chosen [sort]. */
    val items: List<StarredItem>,
    /** The tag filter sheet's choices over the segment, with the filter in effect. */
    val tags: TagChoices,
    val sort: LibrarySort,
    /** Items in the segment before any filter. */
    val segmentSize: Int,
    /** Items in the whole library. */
    val total: Int,
    /** Whether the segment is long enough for a search field; a query counts only then. */
    val searchable: Boolean,
    /** Whether a search query narrows [items]. */
    val searching: Boolean,
) {
    /** Why [items] is empty, when it is. */
    val emptyReason: EmptyReason?
        get() = when {
            items.isNotEmpty() -> null
            total == 0 -> EmptyReason.LIBRARY
            segmentSize == 0 -> EmptyReason.SEGMENT
            else -> EmptyReason.NO_MATCH
        }

    enum class EmptyReason { LIBRARY, SEGMENT, NO_MATCH }
}

/** Library's segment, tag filter, sort and search rules. */
object LibraryFilter {
    /** A segment longer than this gets a search field. */
    const val SEARCH_THRESHOLD = 30

    /**
     * [filter] is the stored tag filter for [segment]; a choice that left the segment drops out of it
     * (see [TagChoices.of]). [sort] orders the segment; by name it compares titles as [locale] does,
     * ignoring case, and keeps equal titles newest first.
     */
    fun view(
        library: Library,
        segment: ItemType,
        filter: TagFilter,
        query: String,
        sort: LibrarySort = LibrarySort.RECENT,
        locale: Locale = Locale.getDefault(),
    ): LibraryView {
        val inSegment = sorted(library.items.filter { it.type == segment }, sort, locale)
        val choices = TagChoices.of(inSegment, library.tags, filter)
        val searchable = inSegment.size > SEARCH_THRESHOLD
        val needle = query.trim().takeIf { searchable && it.isNotEmpty() }
        val items = inSegment.filter { item -> choices.filter.admits(item) && (needle == null || matches(item, needle)) }
        return LibraryView(segment, items, choices, sort, inSegment.size, library.items.size, searchable, searching = needle != null)
    }

    /**
     * The filter in effect for [segment] given the [stored] one: what the sheet shows now, so a tag that
     * left the segment and comes back later is not still selected unseen. Edits start from it.
     */
    fun inEffect(library: Library, segment: ItemType, stored: TagFilter): TagFilter =
        TagChoices.of(library.items.filter { it.type == segment }, library.tags, stored).filter

    private fun sorted(items: List<StarredItem>, sort: LibrarySort, locale: Locale): List<StarredItem> {
        val newest = items.sortedByDescending { it.addedAt }
        return when (sort) {
            LibrarySort.RECENT -> newest
            LibrarySort.NAME -> {
                // SECONDARY ignores case but not accents; the sort is stable, so equal titles stay newest first.
                val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
                newest.sortedWith { a, b -> collator.compare(a.title, b.title) }
            }
        }
    }

    /** [item]'s tag names in pool order. */
    fun tagNames(item: StarredItem, tags: List<Tag>): List<String> = tags.filter { it.id in item.tagIds }.map { it.name }

    /** How many items carry each tag; tags on no item are absent. */
    fun tagCounts(library: Library): Map<String, Int> =
        library.items.flatMap { it.tagIds }.groupingBy { it }.eachCount()

    /** Starred channels still without an avatar, by channel id. */
    fun missingAvatars(library: Library): List<String> =
        library.items.filter { it.type == ItemType.CHANNEL && it.thumbnail == null }.map { it.id }

    private fun matches(item: StarredItem, needle: String): Boolean =
        item.title.contains(needle, ignoreCase = true) || item.author?.contains(needle, ignoreCase = true) == true
}

/** Tag name rules shared by inline creation and renaming. */
object TagNames {
    const val MAX_LENGTH = 40

    /** [name] trimmed, inner whitespace collapsed and cut to [MAX_LENGTH]; null when nothing is left. */
    fun normalize(name: String): String? =
        name.trim().replace(WHITESPACE, " ").take(MAX_LENGTH).trim().takeIf { it.isNotEmpty() }

    /** The tag other than [exceptId] that already has [name] (ignoring case), if any. */
    fun clash(name: String, tags: List<Tag>, exceptId: String? = null): Tag? {
        val normalized = normalize(name) ?: return null
        return tags.firstOrNull { it.id != exceptId && it.name.equals(normalized, ignoreCase = true) }
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * The tag sheet for one starred item: the item as stored, the whole tag pool, and for a video the
 * channel it belongs to (as stored when starred, so with its avatar), with whether it is starred.
 */
data class TagSheet(val item: StarredItem, val tags: List<Tag>, val channel: StarredItem?, val channelStarred: Boolean) {
    companion object {
        /** The sheet for [itemId], or null once the item is no longer starred. */
        fun of(library: Library, itemId: String): TagSheet? {
            val item = library.items.firstOrNull { it.id == itemId } ?: return null
            val derived = item.channelItem()
            val stored = derived?.let { c -> library.items.firstOrNull { it.id == c.id } }
            return TagSheet(item, library.tags, stored ?: derived, channelStarred = stored != null)
        }
    }
}
