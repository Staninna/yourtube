package com.staninna.yourtube

import android.content.Intent
import java.net.URLEncoder

/**
 * Turns a link another app hands over (a tapped link, shared text) into the m.youtube.com page Watch
 * opens: a video, a Short, a channel by handle or id, or a playlist.
 */
object DeepLinks {
    private val LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** What to play and from where, in this order. The rest goes, `si` (which ties a share to its sender) included. */
    private val KEPT_PARAMS = listOf("v", "t", "list", "index", "start")

    private val HANDLE = Regex("""/@[\w.\-]+(/.*)?""")
    private val CHANNEL = Regex("""/channel/${YouTube.CHANNEL_ID.pattern}(/.*)?""")
    private val SHORT = Regex("""/shorts/${YouTube.VIDEO_ID.pattern}/?""")

    /**
     * The page an Intent asks for: a VIEW link, or the first YouTube link in SEND text. Null for
     * anything else, and for an Intent replayed from recents, which opened its page before.
     */
    fun fromIntent(action: String?, flags: Int, data: String?, text: String?): String? {
        if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val given = when (action) {
            Intent.ACTION_VIEW -> data
            Intent.ACTION_SEND -> text
            else -> null
        }
        return given?.let(::extract)
    }

    /** The page for the first YouTube link in [text], or null when it has none. */
    fun extract(text: String): String? =
        LINK.findAll(text).firstNotNullOfOrNull { page(it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')) }

    private fun page(link: String): String? {
        val uri = YouTube.uri(link)?.normalize() ?: return null
        // No user info or port: neither has a place in a YouTube link, and both can make one look like another.
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null || uri.port != -1) return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty()
        val params = KEPT_PARAMS.mapNotNull { name -> YouTube.queryParam(link, name)?.let { name to it } }
        fun param(name: String) = params.firstOrNull { it.first == name }?.second
        if (host == "youtu.be" || host == "www.youtu.be") {
            val id = path.removePrefix("/").takeIf { YouTube.VIDEO_ID.matches(it) } ?: return null
            return build("/watch", listOf("v" to id) + params.filter { it.first != "v" })
        }
        if (host !in YouTube.CONTENT_HOSTS) return null
        val valid = when (path) {
            "/watch" -> param("v")?.let(YouTube.VIDEO_ID::matches) == true
            "/playlist" -> param("list")?.let(YouTube.PLAYLIST_ID::matches) == true
            else -> SHORT.matches(path) || HANDLE.matches(path) || CHANNEL.matches(path)
        }
        return if (valid) build(path, params) else null
    }

    private fun build(path: String, params: List<Pair<String, String>>): String =
        YouTube.HOME + path +
            if (params.isEmpty()) "" else "?" + params.joinToString("&") { (name, value) -> "$name=" + URLEncoder.encode(value, "UTF-8") }
}
