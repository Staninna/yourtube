package com.staninna.yourtube.web

/**
 * The theme flag inside YouTube's `PREF` cookie. `PREF` is `&`-joined `key=value` pairs; `f6` is a hex
 * bit field where `0x400` means dark and `0x80000` light (measured on m.youtube.com). YouTube sets
 * other `f6` bits and other keys too (`tz`, `f5`), and those are kept.
 */
object ThemePref {
    private const val DARK = 0x400L
    private const val LIGHT = 0x80000L

    /** [pref] (null for none) with its theme set to [dark] and everything else unchanged. */
    fun withTheme(pref: String?, dark: Boolean): String {
        val parts = pref?.split('&')?.filter { it.isNotEmpty() }.orEmpty()
        val index = parts.indexOfFirst { it.startsWith(F6) }
        // An unreadable f6 is YouTube's to fix on its next write; ours starts it over.
        val bits = parts.getOrNull(index)?.removePrefix(F6)?.toLongOrNull(16) ?: 0L
        val f6 = F6 + java.lang.Long.toHexString((bits and (DARK or LIGHT).inv()) or if (dark) DARK else LIGHT)
        val updated = if (index < 0) listOf(f6) + parts else parts.toMutableList().apply { set(index, f6) }
        return updated.joinToString("&")
    }

    /** The `PREF` value inside a `Cookie` header such as `CookieManager.getCookie` returns, if any. */
    fun read(cookieHeader: String?): String? = cookieHeader?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("PREF=") }
        ?.removePrefix("PREF=")

    private const val F6 = "f6="
}

/** What following a light/dark switch takes for the current identity and page. */
enum class ThemeSync {
    /** `PREF` already carries this theme. */
    NONE,

    /**
     * Rewrite `PREF`; the next page load shows it. For a page not loaded yet, or a video that has
     * started, playing or paused (see `NowPlaying.started`): a reload would play it again.
     */
    REWRITE,

    /** Rewrite `PREF` and reload, since YouTube themes the page on the server. */
    REWRITE_AND_RELOAD,
    ;

    companion object {
        fun decide(syncedDark: Boolean, dark: Boolean, pageLoaded: Boolean, videoStarted: Boolean): ThemeSync = when {
            syncedDark == dark -> NONE
            !pageLoaded || videoStarted -> REWRITE
            else -> REWRITE_AND_RELOAD
        }
    }
}
