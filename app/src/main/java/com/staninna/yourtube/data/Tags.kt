package com.staninna.yourtube.data

/** How the selected tags of a [TagFilter] combine. */
enum class TagMatch {
    /** An item shows when it carries at least one selected tag. */
    ANY,

    /** An item shows only when it carries every selected tag. */
    ALL,
}

/**
 * Which items a tag filter lets through: those carrying [tags] (combined by [match]), or with
 * [untagged] only the items without any tag. Untagged and tags exclude each other; nothing selected
 * lets everything through. [match] is kept while the selection changes.
 */
data class TagFilter(
    val tags: Set<String> = emptySet(),
    val untagged: Boolean = false,
    val match: TagMatch = TagMatch.ANY,
) {
    init {
        require(!(untagged && tags.isNotEmpty())) { "Untagged excludes tags" }
    }

    /** Whether anything is selected, so the filter hides something. */
    val active: Boolean get() = untagged || tags.isNotEmpty()

    fun admits(item: StarredItem): Boolean = when {
        untagged -> item.tagIds.isEmpty()
        tags.isEmpty() -> true
        match == TagMatch.ANY -> item.tagIds.any { it in tags }
        else -> item.tagIds.containsAll(tags)
    }

    /** [tagId] flipped; selecting a tag turns Untagged off. */
    fun toggle(tagId: String): TagFilter = copy(tags = if (tagId in tags) tags - tagId else tags + tagId, untagged = false)

    /** Untagged flipped; turning it on clears the tags. */
    fun toggleUntagged(): TagFilter = copy(tags = emptySet(), untagged = !untagged)

    /** Nothing selected; the match mode stays. */
    fun cleared(): TagFilter = copy(tags = emptySet(), untagged = false)
}

/**
 * What a tag filter sheet offers over a set of items: the tags at least one of them carries, in pool
 * order, how many items carry each, how many carry none, and the filter in effect.
 */
data class TagChoices(
    val tags: List<Tag>,
    val counts: Map<String, Int>,
    val untagged: Int,
    /** The stored filter minus what no item offers any more; with nothing left it lets all through. */
    val filter: TagFilter,
) {
    /** The selected tags, in pool order. */
    val selectedTags: List<Tag> get() = tags.filter { it.id in filter.tags }

    companion object {
        /**
         * The choices over [items] with [pool] as the tag pool. A selected tag no item carries any more
         * drops out of [stored], and so does Untagged once every item has a tag, so a choice the sheet
         * no longer shows never filters unseen. Library and Feeds store the result back, so the choice
         * stays out when an item brings it back later.
         */
        fun of(items: List<StarredItem>, pool: List<Tag>, stored: TagFilter): TagChoices {
            val counts = items.flatMap { it.tagIds }.groupingBy { it }.eachCount()
            val untagged = items.count { it.tagIds.isEmpty() }
            val filter = stored.copy(tags = stored.tags intersect counts.keys, untagged = stored.untagged && untagged > 0)
            return TagChoices(pool.filter { it.id in counts }, counts, untagged, filter)
        }
    }
}

/** A screen whose list a tag filter sheet narrows: Library's segment, or Feeds. */
interface TagFiltering {
    fun toggleTagFilter(tagId: String)
    fun toggleUntaggedFilter()
    fun setTagMatch(match: TagMatch)
    fun clearTagFilter()
}

/** Whether a tag is on all, some or none of the items a bulk tag sheet edits. */
enum class TagState { ALL, SOME, NONE }

/** The bulk tag sheet for several selected items: the whole tag pool, each tag with its [TagState]. */
data class BulkTagging(val itemIds: Set<String>, val tags: List<Pair<Tag, TagState>>) {
    companion object {
        /** The sheet for the starred items among [itemIds]; null when none is starred. */
        fun of(library: Library, itemIds: Set<String>): BulkTagging? {
            val items = library.items.filter { it.id in itemIds }
            if (items.isEmpty()) return null
            val states = library.tags.map { tag ->
                val carrying = items.count { tag.id in it.tagIds }
                tag to when (carrying) {
                    items.size -> TagState.ALL
                    0 -> TagState.NONE
                    else -> TagState.SOME
                }
            }
            return BulkTagging(items.mapTo(LinkedHashSet()) { it.id }, states)
        }

        /** What tapping a tag in [state] does: a tag on every item comes off all of them, else it goes on all. */
        fun attachOnTap(state: TagState): Boolean = state != TagState.ALL
    }
}

/** The tag pool order, which every list of tags follows. */
object TagOrder {
    /** [tags] with [tagId] moved to [toIndex] (clamped to the list); an unknown id changes nothing. */
    fun move(tags: List<Tag>, tagId: String, toIndex: Int): List<Tag> {
        val from = tags.indexOfFirst { it.id == tagId }
        if (from < 0) return tags
        val to = toIndex.coerceIn(0, tags.lastIndex)
        if (from == to) return tags
        return tags.toMutableList().apply { add(to, removeAt(from)) }
    }
}
