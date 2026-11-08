package com.staninna.yourtube.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.BackStep
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.Screen
import com.staninna.yourtube.Tab
import com.staninna.yourtube.UiEvent
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.ui.components.NowPlayingBar
import com.staninna.yourtube.web.WebViewHost
import kotlinx.coroutines.flow.collectLatest

/**
 * The app shell. Watch (the WebView) is always composed at the bottom so playback never stops; the
 * native screens (Watch's home and the other tabs) lie over it with the now-playing bar, the
 * navigation bar runs along the bottom, and Settings and search cover everything when open. In
 * picture-in-picture only Watch's page shows.
 */
@Composable
fun TubeRoot(vm: MainViewModel, host: WebViewHost) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val nav by host.nav.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val overlayOpen = settingsOpen || searchOpen
    val inPip by vm.pip.inPip.collectAsStateWithLifecycle()
    val watchShown = screen == Screen.PAGE && !overlayOpen
    LaunchedEffect(watchShown) { vm.pip.setWatchShown(watchShown) }
    val snackbars = remember { Snackbars(SnackbarHostState(), SnackbarHostState()) }
    val backup = rememberBackupActions(vm)
    val corrupt by vm.corruptNotice.collectAsStateWithLifecycle()
    // The now-playing bar's height as it animates, so notices rise above it instead of covering it.
    var nowPlayingHeight by remember { mutableIntStateOf(0) }

    // The newest notice replaces the one showing; its Undo goes with it, as in any Material app.
    LaunchedEffect(vm) {
        vm.events.collectLatest { event ->
            val notice = Notices.of(event)
            val result = snackbars.events.showSnackbar(
                notice.message,
                actionLabel = notice.action,
                duration = if (notice.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && event is UiEvent.Undoable) vm.undo(event)
        }
    }

    // The damaged-store notice is state, not an event: it waits in its own queue until answered.
    LaunchedEffect(corrupt) {
        if (corrupt == null) return@LaunchedEffect
        val notice = Notices.unreadableStore
        val result = snackbars.notice.showSnackbar(
            notice.message,
            actionLabel = notice.action,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) backup.export()
        vm.dismissCorruptNotice()
    }

    // Settings and search handle their own Back; with no step, Back leaves the app (see Screen.back).
    val fromLink by vm.fromLink.collectAsStateWithLifecycle()
    val back = screen.back(canGoBack = nav.canGoBack, pageLoaded = nav.url != null, fromLink = fromLink)
    val activity = LocalContext.current as? Activity
    BackHandler(enabled = !overlayOpen && back != null) {
        // Decided at the press from the live state, not the drawn one: a page that just gained or lost
        // its history (a load in progress) may not have redrawn yet, and the drawn step would skip or
        // swallow a page. A press that finds no step left leaves the app, as the system Back would.
        val live = host.nav.value
        when (vm.backStep(canGoBack = live.canGoBack, pageLoaded = live.url != null)) {
            BackStep.PAGE_HISTORY -> host.goBack()
            BackStep.HOME -> vm.showHome()
            BackStep.WATCH -> vm.selectTab(Tab.WATCH)
            null -> activity?.moveTaskToBack(true)
        }
    }

    val watchBar = watchBarColor(page)
    val surfaceIsLight = !isSystemInDarkTheme()
    SystemBarIcons(
        lightStatusBar = if (watchShown) watchBar.luminance() > 0.5f else surfaceIsLight,
        lightNavigationBar = surfaceIsLight,
    )

    val focus = LocalFocusManager.current
    val openSearch = { searchOpen = true }
    // Drops the field's focus too, so it does not land on (and highlight) a top bar button.
    val closeSearch = {
        focus.clearFocus(force = true)
        searchOpen = false
    }
    val openSettings = { settingsOpen = true }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (overlayOpen) Modifier.clearAndSetSemantics {} else Modifier,
            bottomBar = { if (!inPip) TubeNavigationBar(tab, vm::selectTab) },
            // While Settings is open its own host shows the notices.
            snackbarHost = {
                // Watch's page hides the native screens, so the bar's last measured height is stale there.
                val lift = with(LocalDensity.current) { (if (screen != Screen.PAGE) nowPlayingHeight else 0).toDp() }
                if (!settingsOpen && !inPip) SnackbarStack(snackbars, Modifier.padding(bottom = lift))
            },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                WatchScreen(
                    vm, host, nav,
                    covered = !watchShown,
                    chrome = !inPip,
                    onSearch = openSearch,
                    onSettings = openSettings,
                    modifier = if (screen != Screen.PAGE) Modifier.clearAndSetSemantics {} else Modifier,
                )
                if (!inPip) NativeScreens(screen, vm, nowPlaying, openSearch, openSettings, overlayOpen) { nowPlayingHeight = it }
            }
        }
        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 3 } + fadeIn(),
            exit = slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 3 } + fadeOut(),
        ) {
            SettingsScreen(vm, snackbars, backup, onBack = { settingsOpen = false })
        }
        AnimatedVisibility(visible = searchOpen, enter = fadeIn(), exit = fadeOut()) {
            SearchOverlay(
                // From the home or a native tab, not the query of a page nobody sees.
                initialQuery = YouTube.searchQuery(nav.url).takeIf { screen == Screen.PAGE },
                open = searchOpen,
                onSearch = { if (vm.search(it)) closeSearch() },
                onDismiss = closeSearch,
            )
        }
        // Settings and search are closed whenever picture-in-picture starts, as Watch shows then; the
        // tag sheets are windows of their own and would cover the video.
        if (!inPip) {
            TagSheetHost(vm.library)
            BulkTagSheetHost(vm.library.selection)
        }
    }
}

/** Watch's home, Feeds, Library and History over Watch's page, with the now-playing bar under them. */
@Composable
private fun NativeScreens(
    screen: Screen,
    vm: MainViewModel,
    nowPlaying: NowPlaying?,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    overlayOpen: Boolean,
    onNowPlayingHeight: (Int) -> Unit,
) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    AnimatedVisibility(screen != Screen.PAGE, enter = fadeIn(effects), exit = fadeOut(effects)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Lists and the bar keep clear of a camera cutout at the side in landscape.
            Column(Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                // Fade through: the new tab fades in while growing slightly, the old one just fades.
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { (fadeIn(effects) + scaleIn(spatial, initialScale = 0.96f)) togetherWith fadeOut(effects) },
                    modifier = Modifier.weight(1f),
                    label = "tab",
                ) { shown ->
                    when (shown) {
                        Screen.HOME -> HomeScreen(vm, onSearch, onSettings)
                        Screen.FEEDS -> FeedsScreen(vm, onSearch, onSettings)
                        Screen.LIBRARY -> LibraryScreen(vm, onSearch, onSettings)
                        Screen.HISTORY -> HistoryScreen(vm, onSearch, onSettings)
                        // Leaving for the page: keep the last frame blank while the layer fades.
                        Screen.PAGE -> Box(Modifier.fillMaxSize())
                    }
                }
                // Measured around the bar, since a hidden AnimatedVisibility leaves no node to report its 0 height.
                Column(Modifier.onSizeChanged { onNowPlayingHeight(it.height) }) {
                    AnimatedVisibility(
                        // Native screens only: the page shows the video itself, and Settings and search cover the shell.
                        visible = nowPlaying != null && screen != Screen.PAGE && !overlayOpen,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        nowPlaying?.let { NowPlayingBar(it, onOpen = vm::showPage, onPlayPause = vm::playPause) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TubeNavigationBar(current: Tab, onSelect: (Tab) -> Unit) {
    ShortNavigationBar {
        Tab.entries.forEach { tab ->
            val destination = destinations.getValue(tab)
            val selected = tab == current
            ShortNavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                // The label names the tab; the icon stays silent so TalkBack does not say it twice.
                icon = { Icon(if (selected) destination.selected else destination.unselected, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

private class Destination(val label: String, val selected: ImageVector, val unselected: ImageVector)

private val destinations = mapOf(
    Tab.WATCH to Destination("Watch", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle),
    Tab.FEEDS to Destination("Feeds", Icons.Filled.Subscriptions, Icons.Outlined.Subscriptions),
    Tab.LIBRARY to Destination("Library", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    Tab.HISTORY to Destination("History", Icons.Filled.History, Icons.Outlined.History),
)

/**
 * System bar icon tones: dark icons over light content and light ones over dark. The status bar
 * follows whatever lies under it; the navigation bar follows the theme. Set on every change, since
 * the Activity absorbs theme changes and is never recreated to pick them up.
 */
@Composable
private fun SystemBarIcons(lightStatusBar: Boolean, lightNavigationBar: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightStatusBar
            isAppearanceLightNavigationBars = lightNavigationBar
        }
    }
}
