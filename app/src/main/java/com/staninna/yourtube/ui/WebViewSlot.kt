package com.staninna.yourtube.ui

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
 */
@Composable
fun WebViewSlot(host: WebViewHost, modifier: Modifier = Modifier) {
    val generation by host.generation.collectAsStateWithLifecycle()
    val current = generation ?: return
    key(current) {
        AndroidView(factory = host::create, onRelease = host::release, modifier = modifier)
    }
}
