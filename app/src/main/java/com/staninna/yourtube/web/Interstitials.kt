package com.staninna.yourtube.web

import com.staninna.yourtube.YouTube

/** A page YouTube puts between the viewer and the video. */
enum class Interstitial {
    /** consent.youtube.com, the cookie choice. `consent.js` rejects all by itself. */
    CONSENT,

    /** google.com/sorry or "Sign in to confirm you're not a bot": only a fresh identity gets past it. */
    BOT_CHECK,
}

/** Tells consent and bot-check pages apart from YouTube content, and where to go after one. */
object Interstitials {
    /** The interstitial [url] shows by its address alone, or null. */
    fun of(url: String?): Interstitial? {
        val uri = url?.let(YouTube::uri) ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        return when (uri.host?.lowercase()) {
            "consent.youtube.com" -> Interstitial.CONSENT
            "www.google.com" -> Interstitial.BOT_CHECK.takeIf { uri.rawPath.orEmpty().startsWith("/sorry") }
            else -> null
        }
    }

    /**
     * Whether [report] is a watch page whose player refuses to play until the viewer proves they are
     * not a bot. YouTube words the reason in the page's language and only the English one is known,
     * so this sees English pages only; the captcha page ([of]) is caught in any language.
     */
    fun isBotCheck(report: PageReport): Boolean {
        val playability = report.playability ?: return false
        if (playability.status != "LOGIN_REQUIRED" || playability.reason?.contains(BOT) != true) return false
        return playability.videoId == null || playability.videoId == YouTube.queryParam(report.url, "v")
    }

    /**
     * Where a new identity should start instead of [url]: an interstitial's `continue` target when it
     * is a YouTube page, YouTube's home for an interstitial without one, else [url] itself.
     */
    fun reloadUrl(url: String): String {
        if (of(url) == null) return url
        val target = YouTube.queryParam(url, "continue") ?: return YouTube.HOME
        val uri = YouTube.uri(target) ?: return YouTube.HOME
        val content = uri.scheme?.lowercase() == "https" && uri.host?.lowercase() in YouTube.CONTENT_HOSTS
        return if (content) target else YouTube.HOME
    }

    private val BOT = Regex("""\bbot\b""", RegexOption.IGNORE_CASE)
}
