package com.staninna.yourtube

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A request that can never be sent is a failed request, never an exception in the caller. */
class HttpTest {

    @Test fun aMalformedOrNonHttpUrlFailsWithoutThrowing() = runTest {
        for (url in listOf("not a url", "", "ftp://example.com/x", "file:///etc/hosts")) {
            assertEquals(url, HttpResult.Failed(offline = false), Http.get(url, emptyMap()))
        }
    }
}
