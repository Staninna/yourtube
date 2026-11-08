package com.staninna.yourtube.feed

import com.staninna.yourtube.HttpResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryPolicyTest {

    private val ok = HttpResult.Ok("body")
    private val offline = HttpResult.Failed(offline = true)
    private val reset = HttpResult.Failed(offline = false)

    @Test fun rssRetries404AndServerErrorsOnce() {
        val rss = RetryPolicy.RSS
        assertEquals(400L, rss.nextDelay(1, HttpResult.Status(404)))
        assertEquals(400L, rss.nextDelay(1, HttpResult.Status(500)))
        assertEquals(400L, rss.nextDelay(1, reset))
        assertNull("two tries at most", rss.nextDelay(2, HttpResult.Status(404)))
    }

    @Test fun thePageRetriesOverloadTwiceWithBackoffButNot404() {
        val page = RetryPolicy.PAGE
        assertEquals(1_000L, page.nextDelay(1, HttpResult.Status(503)))
        assertEquals(2_000L, page.nextDelay(2, HttpResult.Status(500)))
        assertNull(page.nextDelay(3, HttpResult.Status(503)))
        assertNull("no channel behind it", page.nextDelay(1, HttpResult.Status(404)))
    }

    @Test fun successAMissingConnectionAndTooManyRequestsStopAtOnce() {
        listOf(RetryPolicy.RSS, RetryPolicy.PAGE).forEach { policy ->
            assertNull(policy.nextDelay(1, ok))
            assertNull(policy.nextDelay(1, offline))
            assertNull("429 asks us to slow down", policy.nextDelay(1, HttpResult.Status(429)))
        }
    }

    @Test fun runWaitsBetweenTriesAndReturnsTheLastResult() = runTest {
        val answers = ArrayDeque(listOf<HttpResult>(HttpResult.Status(500), HttpResult.Status(502), ok))
        var tries = 0
        val result = RetryPolicy.PAGE.run { tries++; answers.removeFirst() }
        assertEquals(ok, result)
        assertEquals(3, tries)
        assertEquals("1 s then 2 s", 3_000L, currentTime)
    }

    @Test fun runGivesUpWithTheLastFailure() = runTest {
        var tries = 0
        val result = RetryPolicy.RSS.run { tries++; HttpResult.Status(404) }
        assertEquals(HttpResult.Status(404), result)
        assertEquals(2, tries)
    }
}
