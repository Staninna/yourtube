package com.staninna.yourtube.web

import androidx.webkit.UserAgentMetadata

/**
 * The user agent YouTube sees, with the device left out the way Chrome's own UA reduction leaves it
 * out on Android: Android 10, device model `K`, the Chrome version as `<major>.0.0.0`, and
 * `navigator.platform` as [PLATFORM]. It still says WebView (`; wv`, `Version/4.0`), as WebView's own
 * Client Hints brand does, and the hints carry the same versions and no device.
 */
object UserAgent {
    /** Chrome's frozen `navigator.platform` on Android. */
    const val PLATFORM = "Linux armv81"

    const val ANDROID_VERSION = "10"

    private val CHROME = Regex("""Chrome/(\d+)\.""")

    /** The reduced UA of a WebView on Chrome [major]. YouTube spares a WebView UA its consent page (stage 0). */
    fun of(major: String): String =
        "Mozilla/5.0 (Linux; Android $ANDROID_VERSION; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/$major.0.0.0 Mobile Safari/537.36"

    /** The Chrome major version in [ua], or null without one. */
    fun majorVersion(ua: String): String? = CHROME.find(ua)?.groupValues?.get(1)

    /**
     * Client Hints matching [of]: [defaults]' brands (WebView's own "Android WebView", GREASE
     * included) with reduced full versions, Android [ANDROID_VERSION], no model, no architecture.
     */
    fun metadata(major: String, defaults: UserAgentMetadata): UserAgentMetadata {
        val brands = defaults.brandVersionList.map {
            UserAgentMetadata.BrandVersion.Builder(it).setFullVersion("${it.majorVersion}.0.0.0").build()
        }
        return UserAgentMetadata.Builder(defaults)
            .setBrandVersionList(brands)
            .setFullVersion("$major.0.0.0")
            .setPlatform("Android")
            .setPlatformVersion("$ANDROID_VERSION.0.0")
            .setModel("")
            .setArchitecture("")
            .setMobile(true)
            .setWow64(false)
            .build()
    }
}
