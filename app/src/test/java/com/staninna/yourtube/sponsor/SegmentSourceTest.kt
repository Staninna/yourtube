package com.staninna.yourtube.sponsor

import com.staninna.yourtube.Http
import com.staninna.yourtube.HttpResult
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.SponsorBlockSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cached client: what it sends, what it caches, and how it reads failures. */
class SegmentSourceTest {

    private val server = SponsorBlockSettings.DEFAULT_SERVER

    /** Answers by video id (the one in the body), recording each request. */
    private class FakeHttp(val answer: (url: String) -> HttpResult) {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        suspend fun get(url: String, headers: Map<String, String>): HttpResult {
            requests += url to headers
            return answer(url)
        }
    }

    private fun body(videoId: String) = """[{"videoID":"$videoId","segments":[{"category":"sponsor","segment":[10,20]}]}]"""

    @Test fun anAnswerIsCachedAndTheRequestCarriesNothingButAGenericAgent() = runTest {
        val http = FakeHttp { HttpResult.Ok(body("v1v1v1v1v1v")) }
        val client = SponsorBlockClient(http::get)
        val expected = listOf(Segment(SegmentCategory.SPONSOR, 10.0, 20.0))
        assertEquals(expected, client.segments(server, "v1v1v1v1v1v"))
        assertEquals(expected, client.segments(server, "v1v1v1v1v1v"))
        assertEquals(1, http.requests.size)
        assertEquals(mapOf("User-Agent" to Http.CHROME_ANDROID_UA), http.requests.single().second)
    }

    @Test fun notFoundMeansNoSegmentsAndIsCached() = runTest {
        val http = FakeHttp { HttpResult.Status(404) }
        val client = SponsorBlockClient(http::get)
        assertEquals(emptyList<Segment>(), client.segments(server, "v"))
        assertEquals(emptyList<Segment>(), client.segments(server, "v"))
        assertEquals(1, http.requests.size)
    }

    @Test fun failuresAreNotCached() = runTest {
        for (failure in listOf(HttpResult.Status(503), HttpResult.Failed(offline = true), HttpResult.Ok("<html>"))) {
            val http = FakeHttp { failure }
            val client = SponsorBlockClient(http::get)
            assertNull(client.segments(server, "v"))
            assertNull(client.segments(server, "v"))
            assertEquals(failure.toString(), 2, http.requests.size)
        }
    }

    @Test fun theCacheKeepsTheMostRecentlyUsedAndIsPerServer() = runTest {
        val http = FakeHttp { HttpResult.Ok(body("any")) }
        val client = SponsorBlockClient(http::get, capacity = 2)
        client.segments(server, "a")
        client.segments(server, "b")
        client.segments(server, "a") // a is now the most recent
        client.segments(server, "c") // evicts b
        client.segments(server, "a")
        assertEquals(3, http.requests.size)
        client.segments(server, "b")
        assertEquals(4, http.requests.size)
        client.segments("https://mirror.example", "a")
        assertEquals(5, http.requests.size)
    }
}
