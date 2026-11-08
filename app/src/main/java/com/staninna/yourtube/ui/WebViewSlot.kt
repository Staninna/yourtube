package com.staninna.yourtube.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.web.WebViewHost

/**
 * The host's WebView. Keyed on the host's generation, so a new identity disposes the old WebView
 * (through [WebViewHost.release]) and composes a new one (through [WebViewHost.create]).
 *
 * [covered] is true while native UI lies over the WebView: it keeps playing, but accessibility
 * services skip it so TalkBack never lands on a page nobody can see.
 */
@Composable
fun WebViewSlot(host: WebViewHost, modifier: Modifier = Modifier, covered: Boolean = false) {
    val generation by host.generation.collectAsStateWithLifecycle()
    val current = generation ?: return
    key(current) {
        AndroidView(
            factory = host::create,
            onRelease = host::release,
            update = {
                it.importantForAccessibility =
                    if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            },
            modifier = modifier,
        )
    }
}
