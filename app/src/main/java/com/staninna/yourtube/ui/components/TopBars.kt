package com.staninna.yourtube.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll

/** An entry of an [OverflowMenu]. */
data class MenuEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * A native tab's frame: a top bar with an emphasized title, the tab's own [actions], search and the
 * overflow menu (Settings). The bar tints as content scrolls under it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabScaffold(
    title: String,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    actions: @Composable () -> Unit = {},
    /**
     * The content's scroll position, for a screen whose list moves without a scroll gesture (a fresh
     * list, a jump to the top, pull to refresh): the bar then tints whenever it is off its start.
     * Without it the bar tints by adding up scroll gestures.
     */
    scrolled: ScrollableState? = null,
    /** A bar that stands in for the usual one while set, such as Library's selection bar. */
    contextualBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = if (scrolled != null) {
        TopAppBarDefaults.pinnedScrollBehavior(scrollableState = scrolled, canScroll = { false })
    } else {
        TopAppBarDefaults.pinnedScrollBehavior()
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (contextualBar != null) {
                contextualBar()
            } else {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLargeEmphasized) },
                    actions = {
                        actions()
                        SearchAction(onSearch)
                        OverflowMenu(listOf(MenuEntry("Settings", Icons.Filled.Settings, onSettings)))
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        // The shell's navigation bar already took the bottom inset.
        contentWindowInsets = WindowInsets.statusBars,
        content = content,
    )
}

/** The top bar of a screen pushed over the shell: back, and a large title that collapses on scroll. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubpageTopBar(title: String, onBack: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    LargeFlexibleTopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun SearchAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Filled.Search, contentDescription = "Search YouTube") }
}

/** The three-dot menu at the end of a top bar. */
@Composable
fun OverflowMenu(entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = { Icon(entry.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}
