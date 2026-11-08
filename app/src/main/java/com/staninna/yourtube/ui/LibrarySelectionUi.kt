package com.staninna.yourtube.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.LibrarySelection
import com.staninna.yourtube.data.BulkTagging
import com.staninna.yourtube.data.TagState
import com.staninna.yourtube.ui.components.TagChip
import kotlinx.coroutines.launch

/** Library's top bar in selection mode: close, how many are selected, Select all, Tag and Remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, selection: LibrarySelection) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = selection::clear) { Icon(Icons.Filled.Close, contentDescription = "Exit selection") }
        },
        title = { Text("$count selected", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
        actions = {
            IconButton(onClick = selection::selectAll) { Icon(Icons.Filled.SelectAll, contentDescription = "Select all") }
            IconButton(onClick = selection::tag) { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "Tag selected") }
            IconButton(onClick = selection::remove) { Icon(Icons.Outlined.Delete, contentDescription = "Remove selected from library") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/**
 * The bulk tag sheet, while selection mode has one open: every tag with whether all, some or none of
 * the selected rows carry it. Tapping puts a tag on all of them, or takes off one they all carry;
 * a new tag goes on all of them. Changes apply as they are tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkTagSheetHost(selection: LibrarySelection) {
    val sheet by selection.bulkTags.collectAsStateWithLifecycle()
    val shown = sheet ?: return
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = selection::closeBulkTags, sheetState = state) {
        BulkTagContent(
            shown,
            selection,
            onDone = { scope.launch { state.hide() }.invokeOnCompletion { selection.closeBulkTags() } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkTagContent(sheet: BulkTagging, selection: LibrarySelection, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
    ) {
        Text(
            "Tag ${plural(sheet.itemIds.size, "item", "items")}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "A dash means some of them carry the tag. Tap to put it on all of them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        if (sheet.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sheet.tags.forEach { (tag, state) ->
                    TagChip(
                        tag.name,
                        Color(tag.color),
                        selected = state == TagState.ALL,
                        partial = state == TagState.SOME,
                        onClick = { selection.setTag(tag.id, BulkTagging.attachOnTap(state)) },
                        modifier = Modifier.semantics { stateDescription = describe(state) },
                    )
                }
            }
        }
        NewTagField(sheet.tags.map { it.first }, onCreate = selection::createTag)
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

private fun describe(state: TagState): String = when (state) {
    TagState.ALL -> "On all"
    TagState.SOME -> "On some"
    TagState.NONE -> "Off"
}
