package com.staninna.yourtube

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.webkit.Profile
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.ui.WebViewSlot
import com.staninna.yourtube.web.PageListener
import com.staninna.yourtube.web.PageReport
import com.staninna.yourtube.web.PlaybackEvent
import com.staninna.yourtube.web.TubeWebView
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * A replaced identity is wiped once its WebView is gone. Runs the real [WebViewHost] in Compose,
 * keeps a handle on the first WebView's profile, stores a cookie and localStorage in it, asks the
 * host for a new identity, and then checks that the OLD profile holds nothing. Pages are local HTML
 * with a YouTube base URL, so no network is needed but YouTube's storage partition is used.
 */
class IdentityTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private object NoPage : PageListener {
        override fun onNavigated(url: String) = Unit
        override fun onPageReport(report: PageReport) = Unit
        override fun onPlayback(event: PlaybackEvent) = Unit
        override fun resumePosition(videoId: String): Double? = null
    }

    @Test fun replacedIdentityIsWipedAfterItsWebViewIsDestroyed() = runBlocking {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val dir = File(compose.activity.cacheDir, "store-test-" + UUID.randomUUID()).apply { mkdirs() }
        val store = Store(dir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
        lateinit var host: WebViewHost
        compose.runOnUiThread { host = WebViewHost(compose.activity, store.settings, NoPage, onRestartNeeded = {}) }
        compose.setContent { WebViewSlot(host, Modifier.fillMaxSize()) }

        withTimeout(30_000) {
            val first = awaitWebView(previous = null)
            val old: Profile = onMain { WebViewCompat.getProfile(first) }
            assertTrue("seeded SOCS", cookies(old).contains("SOCS="))

            openLocalPage(first)
            assertEquals("stored", first.eval("localStorage.setItem('probe', 'stored'); localStorage.getItem('probe')"))
            onMain {
                suspendCancellableCoroutine { done ->
                    old.cookieManager.setCookie(BASE, "probe=stored; Path=/; Secure") { done.resume(Unit) }
                }
            }
            assertTrue(cookies(old).contains("probe=stored"))

            onMain { host.newIdentity(url = null) }
            val second = awaitWebView(previous = first)
            val fresh: Profile = onMain { WebViewCompat.getProfile(second) }
            assertNotEquals(old.name, fresh.name)
            assertTrue("new identity is seeded", cookies(fresh).contains("SOCS="))

            // The old profile's cookie jar empties once the host has released its WebView.
            while (cookies(old).isNotEmpty()) delay(100)

            // And its localStorage is gone: a WebView bound to the old profile finds nothing.
            val probe = onMain { WebView(compose.activity).also { WebViewCompat.setProfile(it, old.name) } }
            onMain { probe.settings.javaScriptEnabled = true; probe.settings.domStorageEnabled = true }
            openLocalPage(probe)
            assertEquals("null", probe.eval("String(localStorage.getItem('probe'))"))
            onMain { probe.destroy() }
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun cookies(profile: Profile): String = onMain { profile.cookieManager.getCookie(BASE).orEmpty() }

    /**
     * Waits for the host's WebView, other than [previous], to be composed into the Activity.
     * `waitUntil` also drives the test frame clock, so Compose gets to recompose.
     */
    private fun awaitWebView(previous: TubeWebView?): TubeWebView {
        var found: TubeWebView? = null
        compose.waitUntil(timeoutMillis = 15_000) {
            found = compose.runOnUiThread { find(compose.activity.window.decorView) }?.takeIf { it !== previous }
            found != null
        }
        return checkNotNull(found)
    }

    private fun find(view: View): TubeWebView? = when (view) {
        is TubeWebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        else -> null
    }

    private suspend fun openLocalPage(view: WebView) {
        onMain { view.loadDataWithBaseURL(BASE, "<html><body>probe</body></html>", "text/html", "utf-8", null) }
        while (view.eval("location.href + '|' + document.readyState") != "$BASE|complete") delay(50)
    }

    /** Evaluates [script] and returns its JSON result with the quotes of a string result removed. */
    private suspend fun WebView.eval(script: String): String = onMain {
        suspendCancellableCoroutine { done -> evaluateJavascript(script) { done.resume(it.removeSurrounding("\"")) } }
    }

    private companion object {
        const val BASE = "https://m.youtube.com/"
    }
}
