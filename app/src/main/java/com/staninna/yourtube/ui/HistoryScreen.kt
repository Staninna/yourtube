package com.staninna.yourtube.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.ui.components.EmptyState
import com.staninna.yourtube.ui.components.MediaLeading
import com.staninna.yourtube.ui.components.MediaRow
import com.staninna.yourtube.ui.components.TabScaffold

/** Watched videos, most recent first, with how far each got. Day groups and swipe arrive in stage 4. */
@Composable
fun HistoryScreen(vm: MainViewModel, onSearch: () -> Unit, onSettings: () -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    TabScaffold(
        title = "History",
        onSearch = onSearch,
        onSettings = onSettings,
        actions = {
            if (history.isNotEmpty()) IconButton(onClick = vm::clearHistory) { Icon(Icons.Filled.DeleteSweep, "Clear history") }
        },
    ) { padding ->
        if (history.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.History,
                title = "No history yet",
                body = "Videos you watch show up here and pick up where you left off.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 8.dp)) {
                items(history, key = { it.videoId }) { h ->
                    MediaRow(
                        title = h.meta?.title ?: "Untitled video",
                        subtitle = h.meta?.author,
                        leading = MediaLeading.Thumbnail(h.thumbnail, WatchProgress.of(h), formatDuration(h.duration)),
                        onClick = { vm.open(h.url) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}
