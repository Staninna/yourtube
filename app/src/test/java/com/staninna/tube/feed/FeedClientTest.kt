package com.staninna.tube.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * Parses a sample YouTube channel RSS feed. Runs under Robolectric because the parser uses
 * android.util.Xml, which has no pure-JVM implementation.
 */
@RunWith(RobolectricTestRunner::class)
class FeedClientTest {

    private fun parse(xml: String) = FeedClient.parse(ByteArrayInputStream(xml.toByteArray()))

    @Test fun parsesEntriesNewestNaturalOrder() {
        val videos = parse(SAMPLE)
        assertEquals(2, videos.size)

        val first = videos[0]
        assertEquals("VID1", first.videoId)
        assertEquals("First video", first.title)
        assertEquals("Example Channel", first.author)
        assertEquals("UC_channel", first.channelId)
        assertEquals("https://example.test/vid1.jpg", first.thumbnail)
        assertEquals("https://m.youtube.com/watch?v=VID1", first.url)
        assertTrue("published should be a real epoch ms", first.publishedAt > 0L)
    }

    @Test fun missingThumbnailFallsBackToYouTubeStill() {
        val second = parse(SAMPLE)[1]
        assertEquals("VID2", second.videoId)
        assertEquals("https://i.ytimg.com/vi/VID2/hqdefault.jpg", second.thumbnail)
    }

    @Test fun entriesWithoutVideoIdAreDropped() {
        val videos = parse(NO_ID)
        assertTrue("entries without a videoId should be skipped", videos.isEmpty())
    }

    @Test fun emptyFeedParsesToNothing() {
        assertTrue(parse("<feed></feed>").isEmpty())
    }

    private companion object {
        const val SAMPLE = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"
      xmlns:media="http://search.yahoo.com/mrss/"
      xmlns="http://www.w3.org/2005/Atom">
  <title>Example Channel</title>
  <entry>
    <id>yt:video:VID1</id>
    <yt:videoId>VID1</yt:videoId>
    <yt:channelId>UC_channel</yt:channelId>
    <title>First video</title>
    <author><name>Example Channel</name><uri>https://www.youtube.com/channel/UC_channel</uri></author>
    <published>2026-01-02T10:00:00+00:00</published>
    <media:group>
      <media:thumbnail url="https://example.test/vid1.jpg" width="480" height="360"/>
    </media:group>
  </entry>
  <entry>
    <id>yt:video:VID2</id>
    <yt:videoId>VID2</yt:videoId>
    <yt:channelId>UC_channel</yt:channelId>
    <title>Second video</title>
    <author><name>Example Channel</name></author>
    <published>2026-01-01T10:00:00+00:00</published>
  </entry>
</feed>"""

        const val NO_ID = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <title>No id here</title>
    <author><name>Example Channel</name></author>
  </entry>
</feed>"""
    }
}
