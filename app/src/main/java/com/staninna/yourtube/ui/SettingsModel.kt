package com.staninna.yourtube.ui

import com.staninna.yourtube.data.Settings

/** A switch as Settings shows it. */
data class SwitchState(val checked: Boolean, val enabled: Boolean)

/** What the Settings screen shows, derived from stored settings and what the device supports. */
object SettingsModel {
    /** Dynamic color shows as off and locked on a device without it, whatever is stored. */
    fun dynamicColor(settings: Settings, available: Boolean) = SwitchState(settings.dynamicColor && available, available)

    /** The Manage tags row's summary. */
    fun tagCount(count: Int): String = if (count == 0) "No tags yet" else plural(count, "tag", "tags")

    /** Diagnostics events, most frequent first, ties by name. */
    fun diagnostics(counts: Map<String, Int>): List<Pair<String, Int>> =
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
}
