package com.staninna.yourtube.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.page.PageIdentity
import com.staninna.yourtube.ui.components.ErrorState
import com.staninna.yourtube.ui.components.MenuEntry
import com.staninna.yourtube.ui.components.OverflowMenu
import com.staninna.yourtube.ui.components.SearchAction
import com.staninna.yourtube.ui.components.StateAction
import com.staninna.yourtube.web.LoadError
import com.staninna.yourtube.web.NavState
import com.staninna.yourtube.web.WebViewHost

/** The color behind the Watch top bar: YouTube's own header color for the page below it. */
@Composable
fun watchBarColor(page: PageIdentity): Color =
    Color(YouTube.pageBackground(isSystemInDarkTheme() || page is PageIdentity.Video))

/**
 * The Watch tab: YouTube in the WebView under a top bar colored like YouTube's own header, so the two
 * read as one surface. The bar has no title; it carries back, star, search, Home and the overflow.
 * A thin line shows load progress, and a failed main-frame load shows a native error with Retry.
 */
@Composable
fun WatchScreen(
    vm: MainViewModel,
    host: WebViewHost,
    nav: NavState,
    covered: Boolean,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val page by vm.page.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        WatchTopBar(vm, host, nav, page, onSearch, onSettings)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The error state covers the page too, so TalkBack skips it then.
            WebViewSlot(host, Modifier.fillMaxSize(), covered = covered || nav.error != null)
            LoadProgress(nav, Modifier.align(Alignment.TopCenter))
            nav.error?.let { LoadErrorState(it, onRetry = host::retry) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchTopBar(
    vm: MainViewModel,
    host: WebViewHost,
    nav: NavState,
    page: PageIdentity,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container by animateColorAsState(watchBarColor(page), MaterialTheme.motionScheme.defaultEffectsSpec(), label = "bar")
    val content = if (container.luminance() < 0.5f) Color.White else Color(YouTube.PAGE_DARK)
    TopAppBar(
        title = {},
        navigationIcon = {
            if (nav.canGoBack) IconButton(onClick = host::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = {
            if (page != PageIdentity.Other) StarToggle(vm, onDarkBar = container.luminance() < 0.5f)
            SearchAction(onSearch)
            IconButton(onClick = { vm.open(YouTube.HOME) }) { Icon(Icons.Filled.Home, "YouTube home") }
            OverflowMenu(
                listOfNotNull(
                    nav.url?.let { url -> MenuEntry("Share", Icons.Filled.Share) { share(context, url, page.title) } },
                    MenuEntry("Settings", Icons.Filled.Settings, onSettings),
                ),
            )
        },
        expandedHeight = 56.dp,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = container,
            navigationIconContentColor = content,
            actionIconContentColor = content,
        ),
    )
}

/**
 * Stars or unstars what Watch shows. Disabled while the page is not identified yet (a `/@handle`
 * channel resolving); TalkBack hears "Starred" or "Not starred".
 */
@Composable
private fun StarToggle(vm: MainViewModel, onDarkBar: Boolean) {
    val target by vm.starTarget.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val starred = target?.let { t -> library.items.any { it.id == t.id } } == true
    // The theme's primary is tuned for its own background; on a bar of the other tone use the inverse.
    val tint = if (onDarkBar == isSystemInDarkTheme()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inversePrimary
    IconToggleButton(
        checked = starred,
        onCheckedChange = { vm.toggleStarCurrent() },
        enabled = target != null,
        colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = tint),
        modifier = Modifier.semantics { stateDescription = if (starred) "Starred" else "Not starred" },
    ) {
        Icon(if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = "Star")
    }
}

/** A 3 dp line under the bar while a page loads; it eases between WebView's coarse progress steps. */
@Composable
private fun LoadProgress(nav: NavState, modifier: Modifier) {
    val loading = nav.progress < 100 && nav.error == null
    val progress by animateFloatAsState(nav.progress / 100f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "load")
    AnimatedVisibility(loading, modifier, enter = fadeIn(), exit = fadeOut()) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            trackColor = Color.Transparent,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** Covers Chromium's error page with a native one, on YouTube's background so the bar stays seamless. */
@Composable
private fun LoadErrorState(error: LoadError, onRetry: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Surface(
        Modifier.fillMaxSize(),
        color = Color(YouTube.pageBackground(dark)),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        if (error.offline) {
            ErrorState(
                icon = Icons.Filled.CloudOff,
                title = "You're offline",
                body = "Check your connection, then try again.",
                action = StateAction("Retry", onRetry),
            )
        } else {
            ErrorState(
                icon = Icons.Filled.ErrorOutline,
                title = "This page didn't load",
                body = "YouTube didn't answer (${error.description.removePrefix("net::")}).",
                action = StateAction("Retry", onRetry),
            )
        }
    }
}

/** Hands the current page to the Android share sheet, titled when Watch knows the video's title. */
private fun share(context: Context, url: String, title: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, YouTube.shareUrl(url))
        title?.let { putExtra(Intent.EXTRA_TITLE, it) }
    }
    context.startActivity(Intent.createChooser(send, null))
}
