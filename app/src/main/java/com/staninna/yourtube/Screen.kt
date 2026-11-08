package com.staninna.yourtube

/** What fills the shell over the navigation bar. Watch shows either its home or its page. */
enum class Screen {
    /** Watch's home: search, Continue watching, New from your subs and Saved videos, over the page. */
    HOME,

    /** Watch's page, YouTube in the WebView. */
    PAGE,
    FEEDS,
    LIBRARY,
    HISTORY,
    ;

    /**
     * Where Back goes from here, or null when it leaves the app. Watch's page goes back through its
     * history ([canGoBack]) and then to Watch's home, the root; a page that came as a link from another
     * app ([fromLink]) leaves the app instead, so one Back returns to that app. A native tab goes to
     * Watch once Watch has loaded a page ([pageLoaded]); before that a cold start's tab is the root.
     */
    fun back(canGoBack: Boolean, pageLoaded: Boolean, fromLink: Boolean = false): BackStep? = when (this) {
        HOME -> null
        PAGE -> when {
            canGoBack -> BackStep.PAGE_HISTORY
            fromLink -> null
            else -> BackStep.HOME
        }
        FEEDS, LIBRARY, HISTORY -> if (pageLoaded) BackStep.WATCH else null
    }

    companion object {
        fun of(tab: Tab, home: Boolean): Screen = when (tab) {
            Tab.WATCH -> if (home) HOME else PAGE
            Tab.FEEDS -> FEEDS
            Tab.LIBRARY -> LIBRARY
            Tab.HISTORY -> HISTORY
        }
    }
}

/** What Back does in the app; see [Screen.back]. */
enum class BackStep {
    /** The page's previous history entry. */
    PAGE_HISTORY,

    /** Watch's home, over the page. */
    HOME,

    /** The Watch tab, as it was left. */
    WATCH,
}

/**
 * A URL for the WebView. [freshHistory]: it opens over Watch's home, so the page starts a new history
 * and Back from it returns to the home instead of to the page that was there before.
 */
data class OpenRequest(val url: String, val freshHistory: Boolean)
