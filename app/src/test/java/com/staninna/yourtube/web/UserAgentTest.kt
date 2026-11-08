package com.staninna.yourtube.web

import androidx.webkit.UserAgentMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserAgentTest {

    private val webView = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.F3; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    @Test fun theDeviceLeavesTheUserAgent() {
        val major = UserAgent.majorVersion(webView)
        assertEquals("133", major)
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.0.0 Mobile Safari/537.36",
            UserAgent.of(major!!),
        )
        assertNull(UserAgent.majorVersion("Mozilla/5.0 (X11; Linux x86_64)"))
    }

    @Test fun clientHintsSayTheSame() {
        val brand = { name: String, major: String -> UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(major).setFullVersion("$major.0.6943.137").build() }
        val defaults = UserAgentMetadata.Builder()
            .setBrandVersionList(listOf(brand("Not(A:Brand", "99"), brand("Android WebView", "133"), brand("Chromium", "133")))
            .setFullVersion("133.0.6943.137").setPlatform("Android").setPlatformVersion("16").setModel("sdk_gphone64_x86_64")
            .setArchitecture("x86").setMobile(true).build()
        val reduced = UserAgent.metadata("133", defaults)
        assertEquals(listOf("Not(A:Brand" to "99.0.0.0", "Android WebView" to "133.0.0.0", "Chromium" to "133.0.0.0"),
            reduced.brandVersionList.map { it.brand to it.fullVersion })
        assertEquals("133.0.0.0", reduced.fullVersion)
        assertEquals("10.0.0", reduced.platformVersion)
        assertEquals("", reduced.model)
        assertEquals("", reduced.architecture)
        assertEquals("Android", reduced.platform)
    }
}
