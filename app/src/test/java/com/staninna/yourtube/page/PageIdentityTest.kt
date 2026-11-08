package com.staninna.yourtube.page

import com.staninna.yourtube.data.ChannelInfo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.VideoMeta
import com.staninna.yourtube.page.PageIdentity.Channel
import com.staninna.yourtube.page.PageIdentity.Other
import com.staninna.yourtube.page.PageIdentity.Playlist
import com.staninna.yourtube.page.PageIdentity.Video
import com.staninna.yourtube.web.PageReport
import com.staninna.yourtube.web.PageReport.Reason
import com.staninna.yourtube.web.PlayerFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageIdentityTest {
    private val v1 = "dQw4w9WgXcQ"
    private val v2 = "9bZkp7q19f0"
    private val uc = "UCXuqSBlHAE6Xw-yeJA0Tunw"
    private val watch1 = "https://m.youtube.com/watch?v=$v1"
    private val watch2 = "https://m.youtube.com/watch?v=$v2"

    private fun facts(videoId: String?, channelId: String? = uc, title: String? = "Title of $videoId") =
        PlayerFacts(videoId = videoId, channelId = channelId, title = title, author = "Author")

    private fun report(
        url: String,
        player: PlayerFacts? = null,
        canonical: String? = null,
        title: String = "",
        reason: Reason = Reason.NAVIGATE,
    ) = PageReport(url = url, reason = reason, canonical = canonical, title = title, player = player)

    // --- type and id from the URL ---

    @Test fun videoUrls() {
        assertEquals(Video(v1, null), PageIdentity.fromUrl(watch1))
        assertEquals(Video(v1, null), PageIdentity.fromUrl("https://www.youtube.com/watch?list=PLx&v=$v1&t=30"))
        assertEquals(Video(v1, null), PageIdentity.fromUrl("https://m.youtube.com/shorts/$v1"))
        assertEquals(Video(v1, null), PageIdentity.fromUrl("https://m.youtube.com/live/$v1?si=x"))
    }

    @Test fun channelUrls() {
        assertEquals(Channel("/channel/$uc", uc, null), PageIdentity.fromUrl("https://m.youtube.com/channel/$uc/videos"))
        assertEquals(Channel("/@linustechtips", null, null), PageIdentity.fromUrl("https://m.youtube.com/@LinusTechTips"))
        assertEquals(Channel("/@linustechtips", null, null), PageIdentity.fromUrl("https://m.youtube.com/@LinusTechTips/shorts"))
        assertEquals(Channel("/c/name", null, null), PageIdentity.fromUrl("https://m.youtube.com/c/Name"))
        assertEquals(Channel("/user/name", null, null), PageIdentity.fromUrl("https://m.youtube.com/user/Name"))
    }

    @Test fun playlistUrl() =
        assertEquals(Playlist("PLabc123", null), PageIdentity.fromUrl("https://m.youtube.com/playlist?list=PLabc123"))

    @Test fun everythingElseIsOther() {
        for (url in listOf(
            "https://m.youtube.com/",
            "https://m.youtube.com/results?search_query=cats",
            "https://m.youtube.com/watch",
            "https://m.youtube.com/watch?v=short",
            "https://m.youtube.com/channel/not-a-uc-id",
            "https://m.youtube.com/@",
            "https://consent.youtube.com/m?continue=x",
            "https://example.com/watch?v=$v1",
            "not a url",
        )) assertEquals(url, Other, PageIdentity.fromUrl(url))
    }

    // --- video facts come only from a matching player response ---

    @Test fun matchingPlayerFactsCompleteTheVideo() {
        val page = PageIdentity.derive(watch1, report(watch1, facts(v1)))
        assertEquals(Video(v1, VideoMeta(v1, "Title of $v1", "Author", uc)), page)
    }

    @Test fun aStreamOnAirIsMarkedLive() {
        val page = PageIdentity.derive(watch1, report(watch1, facts(v1).copy(live = true)))
        assertEquals(true, (page as Video).meta?.live)
    }

    @Test fun playerFactsForAnotherVideoAreIgnored() {
        // After SPA navigation the URL moves first; the player still describes the previous video.
        assertEquals(Video(v2, null), PageIdentity.derive(watch2, report(watch2, facts(v1))))
    }

    @Test fun missingPlayerResponseLeavesTheVideoUnconfirmed() =
        assertEquals(Video(v1, null), PageIdentity.derive(watch1, report(watch1, null)))

    @Test fun malformedOrMissingChannelIdIsAbsentButTheVideoStands() {
        for (channelId in listOf("x", null)) {
            val page = PageIdentity.derive(watch1, report(watch1, facts(v1, channelId = channelId)))
            assertNull((page as Video).meta!!.channelId)
        }
    }

    @Test fun blankOrMissingPlayerTitleLeavesTheVideoUnconfirmed() {
        assertEquals(Video(v1, null), PageIdentity.derive(watch1, report(watch1, facts(v1, title = " "))))
        assertEquals(Video(v1, null), PageIdentity.derive(watch1, report(watch1, facts(v1, title = null))))
    }

    // --- stale reports ---

    @Test fun reportForAnotherUrlIsDropped() {
        // A late report from the previous page must not complete the current one.
        assertEquals(Video(v2, null), PageIdentity.derive(watch2, report(watch1, facts(v2))))
    }

    @Test fun reportDifferingOnlyInFragmentIsCurrent() =
        assertEquals(Video(v1, VideoMeta(v1, "Title of $v1", "Author", uc)), PageIdentity.derive("$watch1#x", report(watch1, facts(v1))))

    // --- channel and playlist titles need a current canonical ---

    @Test fun channelTitleIsTrustedWhenCanonicalMatches() {
        val url = "https://m.youtube.com/@LinusTechTips/videos"
        val page = PageIdentity.derive(url, report(url, canonical = "https://www.youtube.com/@LinusTechTips", title = "Linus Tech Tips - YouTube"))
        assertEquals(Channel("/@linustechtips", null, "Linus Tech Tips"), page)
    }

    @Test fun channelTitleIsIgnoredWhenCanonicalIsStale() {
        val url = "https://m.youtube.com/@Other"
        val page = PageIdentity.derive(url, report(url, canonical = "https://www.youtube.com/@LinusTechTips", title = "Linus Tech Tips - YouTube"))
        assertEquals(Channel("/@other", null, null), page)
    }

    @Test fun playlistTitleNeedsCanonicalToo() {
        val url = "https://m.youtube.com/playlist?list=PLabc123"
        assertEquals(Playlist("PLabc123", "Mix"), PageIdentity.derive(url, report(url, canonical = url, title = "Mix - YouTube")))
        assertEquals(Playlist("PLabc123", null), PageIdentity.derive(url, report(url, canonical = null, title = "Mix - YouTube")))
    }

    // --- markup drift ---

    @Test fun driftWhenThePlayerShouldHaveCaughtUpButHasNot() {
        assertEquals("video.no-player-response", PageIdentity.drift(report(watch1, null, reason = Reason.METADATA)))
        assertEquals("video.no-player-response", PageIdentity.drift(report(watch1, facts(null), reason = Reason.METADATA)))
        assertEquals("video.player-behind-url", PageIdentity.drift(report(watch2, facts(v1), reason = Reason.METADATA)))
    }

    @Test fun noDriftWhileNavigationIsStillUnderway() {
        assertNull(PageIdentity.drift(report(watch1, null, reason = Reason.NAVIGATE)))
        assertNull(PageIdentity.drift(report(watch2, facts(v1), reason = Reason.LOAD)))
    }

    @Test fun driftWhenMatchingFactsLackTitleOrChannel() {
        assertEquals("video.no-title", PageIdentity.drift(report(watch1, facts(v1, title = null))))
        assertEquals("video.no-channel-id", PageIdentity.drift(report(watch1, facts(v1, channelId = "x"))))
        assertEquals("video.no-channel-id", PageIdentity.drift(report(watch1, facts(v1, channelId = null))))
        assertNull(PageIdentity.drift(report(watch1, facts(v1))))
        assertNull(PageIdentity.drift(report("https://m.youtube.com/", null, reason = Reason.METADATA)))
    }

    // --- star target ---

    @Test fun unconfirmedPagesCannotBeStarred() {
        assertNull(starItem(Video(v1, null), null))
        assertNull(starItem(Channel("/@x", null, "X"), null))
        assertNull(starItem(Playlist("PLabc123", null), null))
        assertNull(starItem(Other, null))
    }

    @Test fun confirmedVideoStarsWithItsChannel() {
        val item = starItem(Video(v1, VideoMeta(v1, "T", "A", uc)), null)!!
        assertEquals(ItemType.VIDEO, item.type)
        assertEquals(v1, item.id)
        assertEquals(uc, item.channelId)
        assertEquals("https://i.ytimg.com/vi/$v1/hqdefault.jpg", item.thumbnail)
    }

    @Test fun handleChannelStarsWithTheResolvedId() {
        val info = ChannelInfo(uc, "Linus Tech Tips", "https://yt3/avatar")
        val item = starItem(Channel("/@linustechtips", null, null), info)!!
        assertEquals(uc, item.id)
        assertEquals(uc, item.channelId)
        assertEquals("Linus Tech Tips", item.title)
        assertEquals("https://yt3/avatar", item.thumbnail)
        assertEquals("https://m.youtube.com/channel/$uc", item.url)
    }

    @Test fun channelPathWithIdStarsWithThePageTitleBeforeResolution() {
        val item = starItem(Channel("/channel/$uc", uc, "From page"), null)!!
        assertEquals("From page", item.title)
        assertNull(item.thumbnail)
    }
}
