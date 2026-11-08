package com.staninna.yourtube.ui

import com.staninna.yourtube.data.LibraryView
import com.staninna.yourtube.data.TagChoices
import com.staninna.yourtube.data.TagMatch
import com.staninna.yourtube.feed.FeedView

/** An empty state's words: its title, its body and what its button does. */
data class EmptyCopy(val title: String, val body: String, val action: EmptyAction)

/** What an empty state's button does, with its label. */
enum class EmptyAction(val label: String) {
    CLEAR_TAGS("Clear tags"),

    /** Clears the tags too; Feeds says it this way. */
    SHOW_ALL("Show all"),
    CLEAR_SEARCH("Clear search"),
}

/** What the tag filter bar and the empty states say about a tag filter. */
object TagFilterText {
    /**
     * The Tags button: "All tags", "Untagged", one name, two names ("Music, Gaming"; "Music & Gaming"
     * when all must match), or the first and how many more ("Music +2"; "Music & 2 more").
     */
    fun label(choices: TagChoices): String {
        val filter = choices.filter
        val names = choices.selectedTags.map { it.name }
        val all = filter.match == TagMatch.ALL
        return when {
            filter.untagged -> "Untagged"
            names.isEmpty() -> "All tags"
            names.size == 1 -> names.single()
            names.size == 2 -> names.joinToString(if (all) " & " else ", ")
            all -> "${names.first()} & ${names.size - 1} more"
            else -> "${names.first()} +${names.size - 1}"
        }
    }

    /** What TalkBack reads for the Tags button, every selected name included. */
    fun description(choices: TagChoices): String {
        val filter = choices.filter
        val names = choices.selectedTags.map { it.name }
        return "Tags: " + when {
            filter.untagged -> "untagged only"
            names.isEmpty() -> "all"
            names.size == 1 -> names.single()
            filter.match == TagMatch.ALL -> "all of " + naturalJoin(names, "and")
            else -> "any of " + naturalJoin(names, "or")
        }
    }

    /**
     * Library's empty state when a segment has items but none pass: [what] is the segment's plural
     * ("videos"). The button clears the tags when they narrow the list, else the search.
     */
    fun libraryNoMatch(view: LibraryView, what: String): EmptyCopy {
        val filter = view.tags.filter
        val names = view.tags.selectedTags.map { it.name }
        if (!filter.active) return EmptyCopy("Nothing matches", "No $what have that in their name or channel.", EmptyAction.CLEAR_SEARCH)
        val body = when {
            view.searching && filter.untagged -> "No untagged $what have that in their name or channel."
            view.searching -> "No $what under these tags have that in their name or channel."
            filter.match == TagMatch.ALL -> "No $what carry ${naturalJoin(names, "and")} together."
            else -> "No $what match these tags."
        }
        val title = if (!view.searching && filter.match == TagMatch.ALL) "Nothing has all these tags" else "Nothing matches"
        return EmptyCopy(title, body, EmptyAction.CLEAR_TAGS)
    }

    /** Feeds' empty state when channels have videos but none pass the tag filter. */
    fun feedNoMatch(view: FeedView): EmptyCopy {
        val filter = view.tags.filter
        val names = view.tags.selectedTags.map { it.name }
        return when {
            view.channels == 0 -> EmptyCopy(
                "No channel has all these tags",
                "None of your channels carries ${naturalJoin(names, "and")} together.",
                EmptyAction.SHOW_ALL,
            )
            filter.untagged -> EmptyCopy("Nothing untagged", "Your untagged channels have no videos to show.", EmptyAction.SHOW_ALL)
            else -> EmptyCopy("Nothing under these tags", "The channels with these tags have no videos to show.", EmptyAction.SHOW_ALL)
        }
    }

    /** "Music", "Music and Gaming", "Music, Gaming and Retro" (with [last] as the final joiner). */
    private fun naturalJoin(names: List<String>, last: String): String =
        if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " $last " + names.last()
}
