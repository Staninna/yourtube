package com.staninna.yourtube.feed

import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.VideoLength
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A channel's mobile pages: its videos page (`m.youtube.com/channel/UC.../videos`), the fallback when
 * its RSS feed does not answer, and both that and its live page (`/streams`) for the lengths RSS lacks.
 * Each page embeds `ytInitialData` as a JavaScript string literal. The videos grid holds the newest 30
 * uploads (no Shorts, no live streams) with their age as text, such as "3 days ago", their view count
 * and their length; the live grid holds streams, those on air marked LIVE and without a length.
 */
object ChannelPage {

    /** The channel pages Feeds reads, by their path. */
    enum class Tab(val path: String) { VIDEOS("videos"), LIVE("streams") }

    fun url(channelId: String, tab: Tab = Tab.VIDEOS) = "${YouTube.HOME}/channel/$channelId/${tab.path}"

    /**
     * The page's uploads, newest first, or null when [html] is not [channelId]'s videos page (a consent
     * page, another channel) or its data cannot be read. A video whose age cannot be read is skipped.
     * Ages count back from [now]; the page lists newest first, so each video sits a millisecond
     * behind the one before it, which keeps that order among videos of the same age.
     */
    fun parse(html: String, channelId: String, now: Long): List<FeedVideo>? {
        val data = channelData(html, channelId) ?: return null
        val name = data.obj("metadata")?.obj("channelMetadataRenderer")?.text("title") ?: return null
        val renderers = videoRenderers(data)
        if (renderers.isEmpty()) return emptyList()
        val videos = renderers.mapIndexedNotNull { index, video ->
            val id = videoId(video) ?: return@mapIndexedNotNull null
            val age = video.obj("publishedTimeText")?.let(::runsText)?.let(RelativeAge::parse) ?: return@mapIndexedNotNull null
            // A video without a title still plays; Feeds shows the channel's name in its place.
            val title = video.obj("title")?.let(::runsText).orEmpty()
            val views = video.obj("viewCountText")?.let(::runsText)?.let(::parseViews)
            FeedVideo(id, title, name, channelId, now - age - index, views = views, length = lengthOf(video, now))
        }
        // A grid with videos but not one readable age is markup drift, not an empty channel.
        return videos.ifEmpty { null }
    }

    /**
     * What [html], one of [channelId]'s pages, says about each listed video's length, by video id: a
     * length from its length text, or live (checked at [now]) for a stream on air. A video with
     * neither is left out. Null when [html] is not that channel's page or its data cannot be read.
     */
    fun lengths(html: String, channelId: String, now: Long): Map<String, VideoLength>? {
        val data = channelData(html, channelId) ?: return null
        return videoRenderers(data).mapNotNull { video ->
            val id = videoId(video) ?: return@mapNotNull null
            lengthOf(video, now)?.let { id to it }
        }.toMap()
    }

    /** A length as YouTube writes it ("4:05", "1:02:09", "20,843:43:51") in seconds, or null. */
    internal fun parseLength(text: String): Long? {
        val parts = text.trim().replace(",", "").split(':').map { it.toLongOrNull() ?: return null }
        if (parts.size !in 2..3 || parts.drop(1).any { it !in 0..59 } || parts[0] !in 0..MAX_HOURS) return null
        return parts.fold(0L) { total, part -> total * 60 + part }.takeIf { it > 0 }
    }

    /** "1,742,245 views", "1 view" or "No views" as a count; null for anything else ("68 watching"). */
    internal fun parseViews(text: String): Long? {
        if (text.trim() == "No views") return 0
        return VIEWS.matchEntire(text.trim())?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
    }

    /** A stream on air carries a LIVE overlay and no length text; anything else, its length if readable. */
    private fun lengthOf(video: JsonObject, now: Long): VideoLength? {
        val live = (video["thumbnailOverlays"] as? JsonArray).orEmpty().any {
            (it as? JsonObject)?.obj("thumbnailOverlayTimeStatusRenderer")?.text("style") == "LIVE"
        }
        if (live) return VideoLength.Live(now)
        return video.obj("lengthText")?.let(::runsText)?.let(::parseLength)?.let(VideoLength::Known)
    }

    private fun videoId(video: JsonObject): String? = video.text("videoId")?.takeIf { YouTube.VIDEO_ID.matches(it) }

    /** The page's data when it is [channelId]'s own page, else null. */
    private fun channelData(html: String, channelId: String): JsonObject? {
        val data = initialData(html) ?: return null
        val channel = data.obj("metadata")?.obj("channelMetadataRenderer") ?: return null
        return data.takeIf { channel.text("externalId") == channelId }
    }

    /** The decoded `ytInitialData` object, or null when the page has none or it does not parse. */
    internal fun initialData(html: String): JsonObject? {
        val start = html.indexOf(MARKER).takeIf { it >= 0 }?.plus(MARKER.length) ?: return null
        val literal = jsStringLiteral(html, start) ?: return null
        // The JSON reader recurses per level, so a hostile depth would overflow the stack.
        if (nestingDepth(literal) > MAX_DEPTH) return null
        return try {
            Json.parseToJsonElement(literal) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
    }

    /** How deep [json]'s arrays and objects nest, brackets inside strings aside. */
    internal fun nestingDepth(json: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in json) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> deepest = maxOf(deepest, ++depth)
                c == '}' || c == ']' -> depth--
            }
        }
        return deepest
    }

    /**
     * The value of the single-quoted JavaScript string that starts at [start], escapes decoded, or null
     * when it never ends. YouTube writes `\xHH` for quotes and braces and `\\` for a JSON backslash.
     */
    internal fun jsStringLiteral(source: String, start: Int): String? {
        val out = StringBuilder()
        var i = start
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\'' -> return out.toString()
                c != '\\' -> {
                    out.append(c)
                    i++
                }
                i + 1 >= source.length -> return null
                else -> i = escape(source, i + 1, out) ?: return null
            }
        }
        return null
    }

    /** Appends the escape whose letter is at [at] and returns where the text goes on; null if malformed. */
    private fun escape(source: String, at: Int, out: StringBuilder): Int? {
        val hexDigits = when (source[at]) {
            'x' -> 2
            'u' -> 4
            else -> {
                out.append(SIMPLE_ESCAPES[source[at]] ?: source[at])
                return at + 1
            }
        }
        val end = at + 1 + hexDigits
        if (end > source.length) return null
        out.append(source.substring(at + 1, end).toIntOrNull(16)?.toChar() ?: return null)
        return end
    }

    /**
     * Every video renderer in the tree, in document order. Walked with an explicit stack, not
     * recursion: the page decides how deep the tree goes, and a hostile depth must not overflow ours.
     */
    private fun videoRenderers(root: JsonElement): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        // Each entry is a node and whether it is a video renderer, which is taken whole.
        val stack = ArrayDeque<Pair<JsonElement, Boolean>>().apply { addLast(root to false) }
        while (stack.isNotEmpty()) {
            val (element, isVideo) = stack.removeLast()
            if (isVideo) {
                out += element as JsonObject
                continue
            }
            val children: List<Pair<JsonElement, Boolean>> = when (element) {
                is JsonObject -> element.map { (key, value) -> value to (key in VIDEO_RENDERERS && value is JsonObject) }
                is JsonArray -> element.map { it to false }
                is JsonPrimitive -> emptyList()
            }
            // Pushed last to first, so the first child is walked next and document order holds.
            children.asReversed().forEach(stack::addLast)
        }
        return out
    }

    /** A YouTube text object, either `{"runs":[{"text":…}]}` or `{"simpleText":…}`. */
    private fun runsText(text: JsonObject): String? =
        text.text("simpleText") ?: (text["runs"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.text("text") }
            ?.joinToString("")
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val MARKER = "var ytInitialData = '"

    /** Streams that ran for years show over 20,000 hours; ten times that is not a length. */
    private const val MAX_HOURS = 200_000L

    private val VIEWS = Regex("""(\d[\d,]*) views?""")

    /** Real pages nest 26 levels (mobile) to 41 (desktop); far past that is not a page. */
    private const val MAX_DEPTH = 128

    /** The mobile grid uses `compactVideoRenderer`; `videoRenderer` is its desktop twin. */
    private val VIDEO_RENDERERS = setOf("compactVideoRenderer", "videoRenderer")

    private val SIMPLE_ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b', 'f' to '\u000C', '0' to '\u0000')
}
