package com.staninna.yourtube.data

/** What Library shows for one segment, after its tag chips and search. */
data class LibraryView(
    val segment: ItemType,
    /** The segment's items that pass the filter, newest first. */
    val items: List<StarredItem>,
    /** The tags present on the segment's items, in pool order. */
    val chips: List<Tag>,
    /** The chips in effect: the stored selection minus chips no longer shown. Empty means all. */
    val selected: Set<String>,
    /** Items in the segment before any filter. */
    val segmentSize: Int,
    /** Items in the whole library. */
    val total: Int,
    /** Whether the segment is long enough for a search field; a query counts only then. */
    val searchable: Boolean,
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

/** Library's segment, tag-chip and search rules. */
object LibraryFilter {
    /** A segment longer than this gets a search field. */
    const val SEARCH_THRESHOLD = 30

    /**
     * [selection] is the stored chip selection for [segment]. Chips are OR: an item shows when it
     * carries any selected tag. A selected chip that left the segment is dropped, and with none left
     * the segment shows everything.
     */
    fun view(library: Library, segment: ItemType, selection: Set<String>, query: String): LibraryView {
        val inSegment = library.items.filter { it.type == segment }.sortedByDescending { it.addedAt }
        val present = presentTags(inSegment)
        val chips = library.tags.filter { it.id in present }
        val selected = selection intersect present
        val searchable = inSegment.size > SEARCH_THRESHOLD
        val needle = query.trim().takeIf { searchable && it.isNotEmpty() }
        val items = inSegment.filter { item ->
            (selected.isEmpty() || item.tagIds.any { it in selected }) && (needle == null || matches(item, needle))
        }
        return LibraryView(segment, items, chips, selected, inSegment.size, library.items.size, searchable)
    }

    /**
     * The stored selection for [segment] after tapping [tagId]. It starts from the chips the segment
     * shows now, so a chip that left the segment and comes back later is not still selected unseen.
     */
    fun toggleChip(library: Library, segment: ItemType, stored: Set<String>, tagId: String): Set<String> {
        val shown = stored intersect presentTags(library.items.filter { it.type == segment })
        return if (tagId in shown) shown - tagId else shown + tagId
    }

    private fun presentTags(items: List<StarredItem>): Set<String> = items.flatMapTo(HashSet()) { it.tagIds }

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
