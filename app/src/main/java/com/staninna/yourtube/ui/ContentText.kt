package com.staninna.yourtube.ui

import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.ServerProblem
import com.staninna.yourtube.data.SponsorBlockSettings
import java.net.URI

/** A setting's title and the one line under it. */
data class SettingText(val title: String, val summary: String)

/** What Settings > Content and the skip snackbar say. */
object ContentText {

    fun layer(layer: AdLayer): SettingText = when (layer) {
        AdLayer.PLAYER -> SettingText("Video ads", "Removes ad breaks from a video's data before the player sees them")
        AdLayer.NETWORK -> SettingText("Ad servers", "Blocks requests to ad and ad-tracking servers")
        AdLayer.DISPLAY -> SettingText("Ads in pages", "Hides promoted videos, ad cards and banners")
        AdLayer.SAFETY_NET -> SettingText("Skip leaked ads", "Mutes an ad that still gets through and tries to skip it")
    }

    fun category(category: SegmentCategory): SettingText = when (category) {
        SegmentCategory.SPONSOR -> SettingText("Sponsor", "Paid promotion and sponsor reads")
        SegmentCategory.SELF_PROMO -> SettingText("Self-promotion", "Unpaid plugs: merch, Patreon, the creator's other channels")
        SegmentCategory.INTERACTION -> SettingText("Interaction reminder", "Asks to like, subscribe or follow")
        SegmentCategory.INTRO -> SettingText("Intro", "An intro animation, pause or title card with nothing in it")
        SegmentCategory.OUTRO -> SettingText("Outro", "End cards and credits")
        SegmentCategory.PREVIEW -> SettingText("Preview", "Recaps of earlier episodes and previews of what's coming")
        SegmentCategory.FILLER -> SettingText("Filler", "Tangents and jokes the video works without")
        SegmentCategory.MUSIC_OFFTOPIC -> SettingText("Non-music section", "The talking parts of a music video")
    }

    /** The action choices in the category sheet, in [SegmentAction] order. */
    val actions: List<String> = SegmentAction.entries.map(::action)

    fun action(action: SegmentAction): String = when (action) {
        SegmentAction.SKIP -> "Skip"
        SegmentAction.MUTE -> "Mute"
        SegmentAction.SHOW -> "Show"
        SegmentAction.OFF -> "Off"
    }

    /** The snackbar after a skip: "Skipped sponsor". */
    fun skipped(category: SegmentCategory): String = "Skipped " + category(category).title.lowercase()

    /**
     * The Segments row's summary: what gets skipped and muted, by name while it is one or two
     * categories, and how many are only marked. "Skips sponsor and self-promotion. Marks 6 more."
     */
    fun segments(settings: SponsorBlockSettings): String {
        val by = SegmentCategory.entries.groupBy(settings::action)
        val parts = listOfNotNull(
            by[SegmentAction.SKIP]?.let { "Skips " + names(it) },
            by[SegmentAction.MUTE]?.let { "Mutes " + names(it) },
            by[SegmentAction.SHOW]?.let { if (it.size == SegmentCategory.entries.size) "Marks every category" else "Marks ${names(it)}" },
        )
        return if (parts.isEmpty()) "Every category is off" else parts.joinToString(". ") + "."
    }

    /** The Server row's summary: the server's host, with "(default)" for the public server. */
    fun server(settings: SponsorBlockSettings): String {
        // Stored servers passed ServerInput.parse; one from a hand-edited file shows as written.
        val host = runCatching { URI(settings.server).host }.getOrNull() ?: settings.server
        return if (settings.server == SponsorBlockSettings.DEFAULT_SERVER) "$host (default)" else host
    }

    fun serverProblem(problem: ServerProblem): String = when (problem) {
        ServerProblem.BLANK -> "Enter the server's address"
        ServerProblem.NOT_HTTPS -> "The address must start with https://"
        ServerProblem.NO_HOST -> "That isn't a web address"
        ServerProblem.NOT_A_BASE -> "Leave out anything after ? or #, and any user name"
    }

    private fun names(categories: List<SegmentCategory>): String = when (categories.size) {
        1 -> category(categories[0]).title.lowercase()
        2 -> categories.joinToString(" and ") { category(it).title.lowercase() }
        else -> "${categories.size} categories"
    }
}
