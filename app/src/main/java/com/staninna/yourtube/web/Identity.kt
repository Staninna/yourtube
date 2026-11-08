package com.staninna.yourtube.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.YouTube
import kotlinx.coroutines.CompletableDeferred
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
    /** Completes once this identity's data is deleted. */
    internal val wiped = CompletableDeferred<Unit>()

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
    abstract suspend fun create(): Identity?

    /**
     * Clears [identity]'s cookies and storage. Call it only after the WebView that used it is
     * destroyed. [done] runs on the main thread when WebView reports the data gone.
     */
    abstract fun clear(identity: Identity, done: () -> Unit = {})

    /** Deletes everything [identity] stored, then marks it [Identity.wiped] and calls [done]. */
    protected fun wipe(identity: Identity, done: () -> Unit) {
        val clearCookies = {
            identity.cookies.removeAllCookies {
                identity.wiped.complete(Unit)
                done()
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            WebStorageCompat.deleteBrowsingData(identity.storage) { clearCookies() }
        } else {
            identity.storage.deleteAllData()
            clearCookies()
        }
    }

    /**
     * `MULTI_PROFILE`: every identity is a new named profile, and an abandoned one is wiped. The
     * profile itself stays registered: `deleteProfile` refuses a profile loaded in this process, and
     * the next process start removes its directory anyway.
     */
    class Profiles : IdentityStrategy() {
        override suspend fun create(): Identity {
            val name = "id-" + UUID.randomUUID().toString().take(12)
            val profile = ProfileStore.getInstance().getOrCreateProfile(name)
            return Identity(name, profile.cookieManager, profile.webStorage)
        }

        override fun clear(identity: Identity, done: () -> Unit) = wipe(identity, done)
    }

    /**
     * No `MULTI_PROFILE`, but `DELETE_BROWSING_DATA`: every identity is the default profile, wiped in
     * between. A new identity waits until the one before it is wiped, so the wipe never takes the new
     * seed with it.
     */
    class WipeDefault : IdentityStrategy() {
        private var last: Identity? = null

        override suspend fun create(): Identity {
            last?.wiped?.await()
            return Identity(null, CookieManager.getInstance(), WebStorage.getInstance()).also { last = it }
        }

        override fun clear(identity: Identity, done: () -> Unit) = wipe(identity, done)
    }

    /**
     * Neither: the default profile cannot be wiped reliably in a running process, so it serves one
     * WebView per process and the next identity restarts the process.
     */
    class RestartProcess : IdentityStrategy() {
        private var used = false

        override suspend fun create(): Identity? {
            if (used) return null
            used = true
            return Identity(null, CookieManager.getInstance(), WebStorage.getInstance())
        }

        override fun clear(identity: Identity, done: () -> Unit) = done()
    }

    companion object {
        fun choose(multiProfile: Boolean, deleteBrowsingData: Boolean): IdentityStrategy = when {
            multiProfile -> Profiles()
            deleteBrowsingData -> WipeDefault()
            else -> RestartProcess()
        }

        /** The process-wide strategy for this device's WebView. */
        val process: IdentityStrategy by lazy {
            choose(
                multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
                deleteBrowsingData = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA),
            )
        }
    }
}

/** The cookies a new identity starts with, so YouTube skips consent and matches the device. */
data class IdentitySeed(val dark: Boolean, val locale: Locale) {
    fun cookies(): List<String> = listOf(cookie("SOCS", YouTube.SOCS_REJECT_ALL), cookie("PREF", pref()))

    /** The theme flag (see [ThemePref]), then language and region. */
    internal fun pref(): String = buildList {
        add(ThemePref.withTheme(null, dark))
        locale.language.takeIf { it.isNotEmpty() }?.let { add("hl=$it") }
        locale.country.takeIf { it.length == 2 }?.let { add("gl=$it") }
    }.joinToString("&")

    companion object {
        private const val MAX_AGE_SECONDS = 400L * 24 * 60 * 60

        /** A `Set-Cookie` string for YouTube's whole domain. */
        fun cookie(name: String, value: String) = "$name=$value; Domain=.youtube.com; Path=/; Secure; Max-Age=$MAX_AGE_SECONDS"
    }
}
