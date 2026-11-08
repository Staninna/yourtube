package com.staninna.yourtube

import com.staninna.yourtube.data.HistoryEntry

/**
 * Keeps the playing video out of history once the viewer took it out (Clear all, or its own row swiped
 * away), so the next playback save does not bring it straight back. The hold ends when another page
 * shows, when the video is opened again, or when Undo brings back an entry for it.
 */
class HistoryHold {
    private var held: String? = null

    /** The video Watch plays as it leaves history; null when nothing plays. */
    fun hold(videoId: String?) {
        held = videoId
    }

    /** Watch shows [videoId] (null for a page without a video): another page ends the hold. */
    fun onPage(videoId: String?) {
        if (videoId != held) held = null
    }

    /** The viewer opens [videoId]: the held video opened again is recorded again. */
    fun onOpen(videoId: String?) {
        if (videoId == held) held = null
    }

    /** Undo put [entries] back; one of them for the held video ends the hold. */
    fun onRestore(entries: List<HistoryEntry>) {
        if (entries.any { it.videoId == held }) held = null
    }

    /** Whether playback of [videoId] goes into history. */
    fun records(videoId: String): Boolean = videoId != held
}
