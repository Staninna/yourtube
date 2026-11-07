package com.staninna.yourtube.feed

import android.util.Xml
import com.staninna.yourtube.data.FeedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Fetches and parses YouTube channel RSS feeds. No login, no API key: the feeds are public. */
object FeedClient {

    private fun feedUrl(channelId: String) =
        "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    /** Newest uploads across the given channels, merged and sorted newest first. */
    suspend fun latest(channelIds: List<String>): List<FeedVideo> = coroutineScope {
        channelIds.filter { it.isNotBlank() }.distinct()
            .map { async(Dispatchers.IO) { runCatching { fetch(it) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .sortedByDescending { it.publishedAt }
    }

    private suspend fun fetch(channelId: String): List<FeedVideo> = withContext(Dispatchers.IO) {
        val conn = (URL(feedUrl(channelId)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Tube")
        }
        try {
            if (conn.responseCode != 200) return@withContext emptyList()
            conn.inputStream.use { parse(it) }
        } finally {
            conn.disconnect()
        }
    }

    // internal (not private) so the parser can be unit-tested with a fixed RSS payload.
    internal fun parse(input: java.io.InputStream): List<FeedVideo> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val out = mutableListOf<FeedVideo>()
        var videoId = ""
        var channelId = ""
        var title = ""
        var author = ""
        var thumb = ""
        var published = 0L
        var inEntry = false
        var inAuthor = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "entry" -> { inEntry = true; videoId = ""; channelId = ""; title = ""; author = ""; thumb = ""; published = 0L }
                    name == "author" -> inAuthor = true
                    inEntry && name.endsWith("videoId") -> videoId = parser.nextText().trim()
                    inEntry && name.endsWith("channelId") -> channelId = parser.nextText().trim()
                    inEntry && name == "title" -> title = parser.nextText().trim()
                    inEntry && inAuthor && name == "name" -> author = parser.nextText().trim()
                    inEntry && name.endsWith("thumbnail") -> {
                        val url = parser.getAttributeValue(null, "url")
                        if (!url.isNullOrBlank()) thumb = url
                    }
                    inEntry && name == "published" -> published =
                        runCatching { Instant.parse(parser.nextText().trim()).toEpochMilli() }.getOrDefault(0L)
                }
                XmlPullParser.END_TAG -> when (name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        if (videoId.isNotBlank()) {
                            out += FeedVideo(
                                videoId = videoId,
                                title = title,
                                author = author,
                                channelId = channelId,
                                thumbnail = thumb.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                                publishedAt = published,
                                url = "https://m.youtube.com/watch?v=$videoId",
                            )
                        }
                        inEntry = false
                    }
                }
            }
            event = parser.next()
        }
        return out
    }
}
