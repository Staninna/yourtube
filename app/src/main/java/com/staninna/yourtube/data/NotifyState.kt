package com.staninna.yourtube.data

import kotlinx.serialization.Serializable

/**
 * What the background new-video check has seen, kept apart from the feed cache so nothing notifies
 * twice whatever Feeds does with its lists. Backups leave it out.
 */
@Serializable
data class NotifyState(
    /** When this state started: notifications turned on, or the check found them blocked. */
    val since: Long = 0,
    /**
     * Per subscribed channel, what the check has seen. A channel without an entry (the first check
     * after a start, or a new subscription) is seeded without notifying.
     */
    val seen: Map<String, SeenChannel> = emptyMap(),
)

/** One channel's seen uploads, newest first, and when the check first read it (device clock). */
@Serializable
data class SeenChannel(val seededAt: Long, val ids: List<String>)
