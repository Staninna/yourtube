package com.staninna.yourtube.home

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.WatchProgress

/**
 * What Watch's home screen shows under its search field: three rows, each newest first and at most
 * [ROW_SIZE] long. An empty row is left out; with all three empty the home greets a new user instead.
 */
data class Dashboard(
    /** Videos stopped partway, most recently watched first. */
    val continueWatching: List<HistoryEntry> = emptyList(),
    /** Subscribed channels' newest uploads, as `FeedRules.newest` picks them. */
    val newFromSubs: List<FeedVideo> = emptyList(),
    /** Starred videos, most recently starred first. */
    val saved: List<StarredItem> = emptyList(),
) {
    /** Nothing in any row: nothing watched partway, no uploads from subscriptions, no starred video. */
    val empty: Boolean get() = continueWatching.isEmpty() && newFromSubs.isEmpty() && saved.isEmpty()

    companion object {
        /** Cards per row; the row's › opens the full list. */
        const val ROW_SIZE = 10

        /** The rows from [history], the subscriptions' [newest] uploads and the [library]. */
        fun of(history: List<HistoryEntry>, newest: List<FeedVideo>, library: Library): Dashboard = Dashboard(
            continueWatching = history.filter(::partlyWatched).sortedByDescending { it.watchedAt }.take(ROW_SIZE),
            newFromSubs = newest.take(ROW_SIZE),
            saved = library.items.filter { it.type == ItemType.VIDEO }.sortedByDescending { it.addedAt }.take(ROW_SIZE),
        )

        /**
         * Stopped partway: past the point where it would resume, short of the end, and with a length, so
         * there is time left to show. A stream on air has none and a finished video starts over.
         */
        private fun partlyWatched(entry: HistoryEntry): Boolean = entry.length != null && WatchProgress.of(entry)?.finished == false
    }
}
