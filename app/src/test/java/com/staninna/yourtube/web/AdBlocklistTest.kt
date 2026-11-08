package com.staninna.yourtube.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The network layer's matcher, with the URLs m.youtube.com was seen requesting. */
class AdBlocklistTest {

    @Test fun adAndTrackingRequestsAreBlocked() {
        for (url in listOf(
            "https://googleads.g.doubleclick.net/pagead/id?slf_rd=1",
            "https://static.doubleclick.net/instream/ad_status.js",
            "https://doubleclick.net/x",
            "https://pagead2.googlesyndication.com/ytcs/adview?xi=abc",
            "https://www.googleadservices.com/pagead/conversion/1/",
            "https://www.googletagservices.com/tag/js/gpt.js",
            "https://fcmatch.youtube.com/match",
            "https://m.youtube.com/pagead/viewthroughconversion/962985656/?backend=innertube",
            "https://www.youtube.com/pagead/viewthroughconversion/962985656/",
            "https://m.youtube.com/api/stats/ads?ver=2&ns=1",
            "https://m.youtube.com/ptracking?html5=1&video_id=x",
            "https://m.youtube.com/get_midroll_info?ei=x&m_pos=159&token=y",
            "https://m.youtube.com/youtubei/v1/player/ad_break?prettyPrint=false",
            "https://www.youtube.com/pcs/activeview?xai=x",
            "https://www.google.com/pagead/1p-user-list/962985656/?backend=innertube",
            "https://www.google.nl/pagead/1p-user-list/962985656/",
            "https://www.google.co.uk/pagead/1p-conversion/1/",
            "HTTPS://GOOGLEADS.G.DOUBLECLICK.NET/pagead/id",
        )) assertTrue(url, AdBlocklist.blocks(url))
    }

    @Test fun theVideoAndEverythingThePageNeedsLoad() {
        for (url in listOf(
            // The video's own streams and its data.
            "https://rr4---sn-oj5hn5-5g.googlevideo.com/videoplayback?expire=1&ctier=L&source=youtube",
            "https://rr9---sn-bvvbaxivnuxqabp-5hne.googlevideo.com/generate_204",
            "https://redirector.googlevideo.com/videoplayback?pagead=1",
            "https://m.youtube.com/youtubei/v1/player?prettyPrint=false",
            "https://m.youtube.com/youtubei/v1/player",
            "https://m.youtube.com/youtubei/v1/get_watch?prettyPrint=false",
            "https://m.youtube.com/youtubei/v1/next",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&pagead=1",
            // Paths that only start like a blocked one.
            "https://m.youtube.com/ptrackingx",
            "https://m.youtube.com/api/stats/adsense-is-not-this",
            "https://m.youtube.com/api/stats/qoe?fmt=243",
            "https://m.youtube.com/youtubei/v1/player_ad_break",
            // Blocked paths on hosts they do not belong to.
            "https://i.ytimg.com/pagead/x.jpg",
            "https://example.com/get_midroll_info",
            "https://www.google.com/search?q=pagead",
            "https://accounts.google.com/pagead/",
            // Lookalike hosts.
            "https://notdoubleclick.net/x",
            "https://doubleclick.net.example.com/x",
            "https://www.youtube.com/@doubleclick",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            "https://yt3.ggpht.com/avatar.jpg",
            "https://sponsor.ajay.app/api/skipSegments/6973",
            "not a url",
            "data:text/html,pagead",
        )) assertFalse(url, AdBlocklist.blocks(url))
    }
}
