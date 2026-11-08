package com.staninna.yourtube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.data.HistoryDays
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.WatchProgress
import com.staninna.yourtube.ui.components.EmptyState
import com.staninna.yourtube.ui.components.MediaLeading
import com.staninna.yourtube.ui.components.MediaRow
import com.staninna.yourtube.ui.components.TabScaffold
import java.time.LocalDate
import java.time.ZoneId

/**
 * Watched videos grouped by day (Today, Yesterday, then dates), most recent first, with how far each
 * got. Swiping a row away removes it and its resume position, with Undo. Clear all asks first, then
 * offers Undo too.
 */
@Composable
fun HistoryScreen(vm: MainViewModel, onSearch: () -> Unit, onSettings: () -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    TabScaffold(
        title = "History",
        onSearch = onSearch,
        onSettings = onSettings,
        actions = {
            if (history.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.DeleteSweep, "Clear history") }
        },
    ) { padding ->
        if (history.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.History,
                title = "No history yet",
                body = "Videos you watch show up here and pick up where you left off.",
                modifier = Modifier.padding(padding),
            )
            return@TabScaffold
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Keyed on the date too, so the first recomposition after midnight regroups the days.
        val groups = remember(history, today) { HistoryDays.group(history, System.currentTimeMillis(), zone) }
        val locale = LocalConfiguration.current.locales[0]
        // Padded rather than content-padded, so day headers stick below the top bar, not under it.
        LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = 8.dp)) {
            groups.forEach { group ->
                stickyHeader(key = "day:${group.day}", contentType = "day") {
                    DayHeader(historyDayLabel(group.day, today, locale))
                }
                items(group.entries, key = { it.videoId }, contentType = { "video" }) { entry ->
                    HistoryRow(entry, onOpen = { vm.open(entry.url) }, onRemove = { vm.removeHistory(entry.videoId) }, Modifier.animateItem())
                }
            }
        }
    }
    if (confirmClear) ConfirmClearDialog(history.size, onDismiss = { confirmClear = false }, onConfirm = vm::clearHistory)
}

/** Clear all asks first; Undo still follows in the snackbar. */
@Composable
private fun ConfirmClearDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
        title = { Text("Clear all history?") },
        text = { Text(if (count == 1) "This removes 1 video and where it stopped." else "This removes $count videos and where each one stopped.") },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DayHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A history row that swipes away toward the start; TalkBack offers the same as an action. */
@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier) {
    // Not rememberSwipeToDismissBoxState: that is saveable, and LazyColumn hands saved state back to a
    // key that returns. A row brought back by Undo would come back dismissed and remove itself again.
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it * DISMISS_FRACTION }) }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = { onRemove() },
        backgroundContent = { RemoveBackground(state) },
    ) {
        MediaRow(
            title = entry.meta?.title ?: "Untitled video",
            subtitle = entry.meta?.author,
            leading = MediaLeading.Thumbnail(entry.thumbnail, WatchProgress.of(entry), formatDuration(entry.duration)),
            onClick = onOpen,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Remove from history") {
                            onRemove()
                            true
                        },
                    )
                },
        )
    }
}

/** How far, as a share of the row's width, a swipe must go to remove it. */
private const val DISMISS_FRACTION = 0.4f

/** What a swipe uncovers: the error container with a delete icon at the end, only while swiping. */
@Composable
private fun RemoveBackground(state: SwipeToDismissBoxState) {
    val swiping = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
    Box(
        Modifier
            .fillMaxSize()
            .background(if (swiping) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (swiping) Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
    }
}
