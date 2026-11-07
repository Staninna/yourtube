package com.staninna.yourtube.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.YouTube
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * One anonymous YouTube session: the cookie jar and web storage a WebView uses. An identity lives as
 * long as its WebView and is cleared once that WebView is destroyed.
 */
class Identity internal constructor(
    /** The androidx.webkit profile name, or null for the default profile. */
    val profileName: String?,
    val cookies: CookieManager,
    internal val storage: WebStorage,
) {
    /** Binds [webView] to this identity. Must be the very first call on a new WebView. */
    fun bind(webView: WebView) {
        if (profileName != null) WebViewCompat.setProfile(webView, profileName)
    }

    /** Sets [seed]'s cookies and returns once WebView has stored all of them. */
    suspend fun seed(seed: IdentitySeed) {
        for (cookie in seed.cookies()) {
            suspendCancellableCoroutine { done -> cookies.setCookie(YouTube.HOME, cookie) { done.resume(Unit) } }
        }
    }
}

/**
 * How this device gets a fresh identity inside a running process, chosen once from WebView feature
 * support. Process start is always fresh: `WebViewStartup` gives every process an empty WebView data
 * directory, which also disposes of the profiles earlier processes created.
 */
sealed class IdentityStrategy {
    /** A fresh identity, or null when only a process restart can give one. */
    abstract fun create(): Identity?

    /**
     * Clears [identity]'s cookies and storage. Call it only after the WebView that used it is
     * destroyed. [done] runs on the main thread when WebView reports the data gone.
     */
    abstract fun clear(identity: Identity, done: () -> Unit = {})

    /**
     * `MULTI_PROFILE`: every identity is a new named profile, and an abandoned one is wiped. The
     * profile itself stays registered: `deleteProfile` refuses a profile loaded in this process, and
     * the next process start removes its directory anyway.
     */
    class Profiles : IdentityStrategy() {
        override fun create(): Identity {
            val name = "id-" + UUID.randomUUID().toString().take(12)
            val profile = ProfileStore.getInstance().getOrCreateProfile(name)
            return Identity(name, profile.cookieManager, profile.webStorage)
        }

        override fun clear(identity: Identity, done: () -> Unit) {
            val clearCookies = { identity.cookies.removeAllCookies { done() } }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                WebStorageCompat.deleteBrowsingData(identity.storage) { clearCookies() }
            } else {
                identity.storage.deleteAllData()
                clearCookies()
            }
        }
    }

    /**
     * No `MULTI_PROFILE`: the default profile cannot be wiped reliably in a running process, so it
     * serves one WebView per process and the next identity restarts the process.
     */
    class RestartProcess : IdentityStrategy() {
        private var used = false

        override fun create(): Identity? {
            if (used) return null
            used = true
            return Identity(null, CookieManager.getInstance(), WebStorage.getInstance())
        }

        override fun clear(identity: Identity, done: () -> Unit) = done()
    }

    companion object {
        /** `DELETE_BROWSING_DATA` arrived after `MULTI_PROFILE`, so it never needs its own strategy. */
        fun choose(multiProfile: Boolean): IdentityStrategy = if (multiProfile) Profiles() else RestartProcess()

        /** The process-wide strategy for this device's WebView. */
        val process: IdentityStrategy by lazy {
            choose(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        }
    }
}

/** The cookies a new identity starts with, so YouTube skips consent and matches the device. */
data class IdentitySeed(val dark: Boolean, val locale: Locale) {
    fun cookies(): List<String> = listOf("SOCS=${YouTube.SOCS_REJECT_ALL}", "PREF=${pref()}")
        .map { "$it; Domain=.youtube.com; Path=/; Secure; Max-Age=$MAX_AGE_SECONDS" }

    /** `f6=400` is YouTube's dark theme flag and `f6=80000` its light one (measured on m.youtube.com). */
    internal fun pref(): String = buildList {
        add("f6=" + if (dark) "400" else "80000")
        locale.language.takeIf { it.isNotEmpty() }?.let { add("hl=$it") }
        locale.country.takeIf { it.length == 2 }?.let { add("gl=$it") }
    }.joinToString("&")

    private companion object {
        const val MAX_AGE_SECONDS = 400L * 24 * 60 * 60
    }
}
