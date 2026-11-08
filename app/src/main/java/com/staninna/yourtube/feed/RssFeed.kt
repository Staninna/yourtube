package com.staninna.yourtube.feed

import android.util.Xml
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.FeedVideo
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** A channel's public Atom feed: its newest 15 uploads with exact publish times. No login, no key. */
object RssFeed {

    fun url(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    /**
     * [channelId]'s entries in feed order (newest first), or null when [xml] is not a whole, well-formed
     * feed, such as the HTML error page the feed server sends with its 404s or a body cut short. An
     * entry without a valid video id or publish time, or from another channel, is skipped. A missing
     * title or channel name stays blank; Feeds shows the subscription's title there.
     */
    fun parse(xml: String, channelId: String): List<FeedVideo>? = try {
        read(xml, channelId)
    } catch (e: XmlPullParserException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun read(xml: String, channelId: String): List<FeedVideo>? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val out = mutableListOf<FeedVideo>()
        var closed = false
        var entry: EntryFields? = null
        var inAuthor = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "entry" -> entry = EntryFields()
                    name == "author" -> inAuthor = true
                    entry == null -> Unit
                    name == "yt:videoId" -> entry.videoId = parser.nextText().trim()
                    name == "yt:channelId" -> entry.channelId = parser.nextText().trim()
                    name == "title" -> entry.title = parser.nextText().trim()
                    name == "name" && inAuthor -> entry.author = parser.nextText().trim()
                    name == "published" -> entry.published = parseInstant(parser.nextText().trim())
                }
                XmlPullParser.END_TAG -> when (name) {
                    "feed" -> closed = true
                    "author" -> inAuthor = false
                    "entry" -> {
                        entry?.toVideo()?.takeIf { it.channelId == channelId }?.let(out::add)
                        entry = null
                    }
                }
            }
            event = parser.next()
        }
        // Not every pull parser fails on a cut-off body, so a feed counts only once it is closed.
        return if (closed) out else null
    }

    /** The feed writes `+00:00` offsets, which a Java 8 `Instant.parse` (older Androids) rejects. */
    private fun parseInstant(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    /** One `<entry>` while it is read. */
    private class EntryFields {
        var videoId = ""
        var channelId = ""
        var title = "" // blank when missing, see parse
        var author = ""
        var published: Long? = null

        fun toVideo(): FeedVideo? {
            val at = published ?: return null
            if (!YouTube.VIDEO_ID.matches(videoId) || !YouTube.CHANNEL_ID.matches(channelId)) return null
            return FeedVideo(videoId, title, author, channelId, at)
        }
    }
}
