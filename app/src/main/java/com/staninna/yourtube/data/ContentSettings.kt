package com.staninna.yourtube.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The independent layers of ad blocking. Each is a switch in Settings > Content, all on by default. */
@Serializable
enum class AdLayer {
    /** Ad data is pruned from player responses before YouTube's player reads them (`early.js`). */
    @SerialName("player") PLAYER,

    /** Requests to ad and ad-tracking servers get an empty answer (`AdBlocklist`). */
    @SerialName("network") NETWORK,

    /** Promoted videos, ad slots and companion ads in pages are hidden by CSS. */
    @SerialName("display") DISPLAY,

    /** An ad that still plays is muted, played at 16x, skipped once it can be, and counted (`main.js`). */
    @SerialName("safetyNet") SAFETY_NET,
}

/**
 * Ad blocking as the user set it. Stores the layers switched off, so a layer added later starts on
 * for everyone.
 */
@Serializable
data class AdBlocking(val off: Set<AdLayer> = emptySet()) {
    fun isOn(layer: AdLayer): Boolean = layer !in off

    fun with(layer: AdLayer, on: Boolean): AdBlocking = AdBlocking(if (on) off - layer else off + layer)
}

/** What happens when playback reaches a SponsorBlock segment. */
@Serializable
enum class SegmentAction {
    /** Seek past it. */
    @SerialName("skip") SKIP,

    /** Mute while it plays. */
    @SerialName("mute") MUTE,

    /** Mark it on the progress bar and play it. */
    @SerialName("show") SHOW,

    /** Ignore it. */
    @SerialName("off") OFF,
}

/**
 * SponsorBlock's segment categories the app handles, under their API names, each with the action it
 * starts with (sponsor and self-promotion skip, the rest show only) and SponsorBlock's own color for
 * it, which marks it on the progress bar and in Settings.
 */
@Serializable
enum class SegmentCategory(val apiName: String, val defaultAction: SegmentAction, val color: Int) {
    @SerialName("sponsor") SPONSOR("sponsor", SegmentAction.SKIP, 0xFF00D400.toInt()),
    @SerialName("selfpromo") SELF_PROMO("selfpromo", SegmentAction.SKIP, 0xFFFFFF00.toInt()),
    @SerialName("interaction") INTERACTION("interaction", SegmentAction.SHOW, 0xFFCC00FF.toInt()),
    @SerialName("intro") INTRO("intro", SegmentAction.SHOW, 0xFF00FFFF.toInt()),
    @SerialName("outro") OUTRO("outro", SegmentAction.SHOW, 0xFF0202ED.toInt()),
    @SerialName("preview") PREVIEW("preview", SegmentAction.SHOW, 0xFF008FD6.toInt()),
    @SerialName("filler") FILLER("filler", SegmentAction.SHOW, 0xFF7300FF.toInt()),
    @SerialName("music_offtopic") MUSIC_OFFTOPIC("music_offtopic", SegmentAction.SHOW, 0xFFFF9900.toInt()),
    ;

    companion object {
        fun fromApi(name: String): SegmentCategory? = entries.firstOrNull { it.apiName == name }
    }
}

/**
 * SponsorBlock as the user set it. [actions] holds only the categories the user changed; the rest
 * follow [SegmentCategory.defaultAction].
 */
@Serializable
data class SponsorBlockSettings(
    val enabled: Boolean = true,
    val server: String = DEFAULT_SERVER,
    val actions: Map<SegmentCategory, SegmentAction> = emptyMap(),
) {
    fun action(category: SegmentCategory): SegmentAction = actions[category] ?: category.defaultAction

    /** Whether there is anything to look up: on, with at least one category not off. */
    val acts: Boolean get() = enabled && SegmentCategory.entries.any { action(it) != SegmentAction.OFF }

    fun with(category: SegmentCategory, action: SegmentAction): SponsorBlockSettings =
        copy(actions = if (action == category.defaultAction) actions - category else actions + (category to action))

    companion object {
        const val DEFAULT_SERVER = "https://sponsor.ajay.app"
    }
}

/** Why a SponsorBlock server address was refused. */
enum class ServerProblem { BLANK, NOT_HTTPS, NO_HOST, NOT_A_BASE }

/** The outcome of checking a server address the user typed. */
sealed interface ServerInput {
    /** [url] is the address normalized: lowercase scheme and host, no trailing slash. */
    data class Valid(val url: String) : ServerInput

    data class Invalid(val problem: ServerProblem) : ServerInput

    companion object {
        /**
         * Checks [text] as a SponsorBlock server base URL. Only `https`: the app allows no cleartext
         * traffic, and a lookup over plain HTTP would show every network on the way what is watched.
         * A path is kept (a server may live under one). A query, fragment or user name is refused,
         * since the API path is appended to the address.
         */
        fun parse(text: String): ServerInput {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return Invalid(ServerProblem.BLANK)
            val uri = try {
                java.net.URI(trimmed)
            } catch (_: java.net.URISyntaxException) {
                return Invalid(ServerProblem.NO_HOST)
            }
            if (!uri.isAbsolute || uri.scheme.lowercase() != "https") return Invalid(ServerProblem.NOT_HTTPS)
            val host = uri.host?.lowercase() ?: return Invalid(ServerProblem.NO_HOST)
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return Invalid(ServerProblem.NOT_A_BASE)
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return Valid("https://$host$port" + uri.rawPath.orEmpty().trimEnd('/'))
        }
    }
}
