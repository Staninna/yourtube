package com.staninna.yourtube.web

import android.webkit.WebViewClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadErrorTest {

    @Test fun noConnectionCountsAsOffline() {
        assertTrue(LoadError.isOffline(WebViewClient.ERROR_HOST_LOOKUP))
        assertTrue(LoadError.isOffline(WebViewClient.ERROR_CONNECT))
    }

    @Test fun otherFailuresAreNotOffline() {
        assertFalse(LoadError.isOffline(WebViewClient.ERROR_TIMEOUT))
        assertFalse(LoadError.isOffline(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE))
        assertFalse(LoadError.isOffline(WebViewClient.ERROR_UNKNOWN))
    }
}
