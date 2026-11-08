package com.staninna.yourtube.ui

import com.staninna.yourtube.data.PipMode
import com.staninna.yourtube.data.Settings
import com.staninna.yourtube.playback.PipSupport

/** A switch as Settings shows it. */
data class SwitchState(val checked: Boolean, val enabled: Boolean)

/** What the Settings screen shows, derived from stored settings and what the device supports. */
object SettingsModel {
    /** Dynamic color shows as off and locked on a device without it, whatever is stored. */
    fun dynamicColor(settings: Settings, available: Boolean) = SwitchState(settings.dynamicColor && available, available)

    /** Picture-in-picture's choices, in [PipMode] order; the summary spells out the short ones. */
    val pipOptions = listOf("Automatic", "Fullscreen", "Manual")

    /** The picture-in-picture row's summary: what the chosen mode does, or why there is no choice. */
    fun pipSummary(mode: PipMode, support: PipSupport): String = when (support) {
        PipSupport.NO_FEATURE -> "This device has no picture-in-picture"
        PipSupport.TURNED_OFF -> "Turned off for YourTube in Android's settings"
        PipSupport.AVAILABLE -> when (mode) {
            PipMode.AUTO -> "A playing video moves to a small window when you leave the app"
            PipMode.FULLSCREEN -> "Only from fullscreen: a video playing fullscreen moves to a small window when you leave the app"
            PipMode.MANUAL -> "Watch's menu has it while a video plays. Leaving the app plays the sound alone."
        }
    }

    /** New video notifications shows as off while Android blocks the app's notifications, whatever is stored. */
    fun newVideos(settings: Settings, allowed: Boolean) = SwitchState(settings.newVideoNotifications && allowed, enabled = true)

    /** The New video notifications row's summary; [blocked] when Android keeps them from showing. */
    fun newVideoSummary(blocked: Boolean): String =
        if (blocked) {
            "Android keeps these notifications from showing. Allow them below, then turn this on."
        } else {
            "About once an hour, checks your subscriptions and tells you about new uploads"
        }

    /** The Manage tags row's summary. */
    fun tagCount(count: Int): String = if (count == 0) "No tags yet" else plural(count, "tag", "tags")

    /** The Diagnostics row's text: one line per event, most frequent first, ties by name. */
    fun diagnostics(counts: Map<String, Int>): String =
        if (counts.isEmpty()) {
            "Nothing unusual since the app started"
        } else {
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .joinToString("\n") { (event, count) -> "$event: " + if (count == 1) "once" else "$count times" }
        }
}
