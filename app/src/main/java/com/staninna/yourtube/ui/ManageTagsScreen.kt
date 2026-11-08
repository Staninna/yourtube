package com.staninna.yourtube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import com.staninna.yourtube.ui.components.dragHandle
import com.staninna.yourtube.ui.components.rememberReorderState
import com.staninna.yourtube.ui.components.reorderItem
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.LibraryModel
import com.staninna.yourtube.data.LibraryFilter
import com.staninna.yourtube.data.Tag
import com.staninna.yourtube.data.TagNames
import com.staninna.yourtube.data.TagPalette
import com.staninna.yourtube.ui.components.EmptyState
import com.staninna.yourtube.ui.components.SubpageTopBar
import com.staninna.yourtube.ui.components.TagDot

/** The one dialog Manage tags can have open. */
private sealed interface TagDialog {
    data class Rename(val tag: Tag) : TagDialog
    data class Recolor(val tag: Tag) : TagDialog
    data class Delete(val tag: Tag, val count: Int) : TagDialog
}

/**
 * Settings > Library > Manage tags: every tag with how many items carry it. Tap the dot to recolor,
 * the pencil to rename, the bin to delete (after a confirm; the items stay starred). Drag the handle to
 * reorder; TalkBack offers Move up and Move down. This order is the one every list of tags follows.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ManageTagsScreen(model: LibraryModel, onBack: () -> Unit) {
    val library by model.state.collectAsStateWithLifecycle()
    val counts = remember(library) { LibraryFilter.tagCounts(library) }
    var dialog by remember { mutableStateOf<TagDialog?>(null) }
    // The order on screen; a drag reorders it as it goes and stores the result when the finger lifts.
    var order by remember(library.tags) { mutableStateOf(library.tags) }
    val list = rememberLazyListState()
    val reorder = rememberReorderState(
        list,
        onMove = { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
        onDrop = { key, to -> model.moveTag(key as String, to) },
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubpageTopBar("Manage tags", onBack, scrollBehavior) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        if (library.tags.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.Label,
                title = "No tags yet",
                body = "Star something in Watch and name a tag in the sheet that opens. Your tags show up here.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Drag a tag by its handle to reorder. Library, Feeds and the tag sheets list tags in this order.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                itemsIndexed(order, key = { _, tag -> tag.id }) { index, tag ->
                    val count = counts[tag.id] ?: 0
                    val dragged = reorder.dragging == tag.id
                    val shapes = ListItemDefaults.segmentedShapes(index, order.size)
                    TagRow(
                        tag,
                        count,
                        shapes,
                        onRecolor = { dialog = TagDialog.Recolor(tag) },
                        onRename = { dialog = TagDialog.Rename(tag) },
                        onDelete = { dialog = TagDialog.Delete(tag, count) },
                        moves = listOfNotNull(
                            if (index > 0) CustomAccessibilityAction("Move up") { model.moveTag(tag.id, index - 1); true } else null,
                            if (index < order.lastIndex) CustomAccessibilityAction("Move down") { model.moveTag(tag.id, index + 1); true } else null,
                        ),
                        handle = Modifier.dragHandle(reorder, tag.id),
                        modifier = (if (dragged) Modifier else Modifier.animateItem()).reorderItem(reorder, tag.id, shapes.shape),
                    )
                }
            }
        }
    }

    val close = { dialog = null }
    when (val open = dialog) {
        is TagDialog.Rename -> RenameDialog(open.tag, library.tags, onDismiss = close, onSave = { model.renameTag(open.tag.id, it) })
        is TagDialog.Recolor -> ColorDialog(open.tag, onDismiss = close, onPick = { model.recolorTag(open.tag.id, it) })
        is TagDialog.Delete -> DeleteDialog(open.tag, open.count, onDismiss = close, onConfirm = { model.deleteTag(open.tag.id) })
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TagRow(
    tag: Tag,
    count: Int,
    shapes: ListItemShapes,
    onRecolor: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    moves: List<CustomAccessibilityAction>,
    handle: Modifier,
    modifier: Modifier,
) {
    SegmentedListItem(
        shapes = shapes,
        modifier = modifier.semantics { customActions = moves },
        leadingContent = {
            val describe = "Color of ${tag.name}: ${TagPalette.nameOf(tag.color)}. Change"
            IconButton(onClick = onRecolor, modifier = Modifier.semantics { contentDescription = describe }) {
                TagDot(Color(tag.color), size = 24.dp)
            }
        },
        supportingContent = { Text(itemCount(count)) },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, contentDescription = "Rename ${tag.name}") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${tag.name}") }
                // TalkBack moves the row with its Move up and Move down actions instead.
                Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) { Text(tag.name) }
}

/** Renames [tag]; Save stays off for a blank name, an unchanged one, or one another tag has. */
@Composable
private fun RenameDialog(tag: Tag, tags: List<Tag>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(tag.name, selection = TextRange(0, tag.name.length))) }
    val normalized = TagNames.normalize(field.text)
    val clash = TagNames.clash(field.text, tags, exceptId = tag.id)
    val canSave = normalized != null && clash == null && normalized != tag.name
    val save = {
        if (canSave) {
            onSave(field.text)
            onDismiss()
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename tag") },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it.copy(text = it.text.take(TagNames.MAX_LENGTH)) },
                modifier = Modifier.focusRequester(focus),
                label = { Text("Name") },
                singleLine = true,
                isError = clash != null,
                supportingText = clash?.let { { Text("You already have a tag called \"${it.name}\"") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        },
        confirmButton = { TextButton(onClick = save, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The palette as a grid of 40 dp swatches, each in a 48 dp touch target, as many to a row as fit. It
 * scrolls when the dialog is short (landscape). TalkBack reads each as a radio button with its name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(tag: Tag, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Color for \"${tag.name}\"") },
        text = {
            FlowRow(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TagPalette.colors.forEach { color ->
                    val selected = color.argb == tag.color
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                onPick(color.argb)
                                onDismiss()
                            }
                            .semantics { contentDescription = color.name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(color.argb)), contentAlignment = Alignment.Center) {
                            // Black on a light swatch, white on a deep one, so the check always shows.
                            val check = if (Color(color.argb).luminance() > 0.3f) Color.Black.copy(alpha = 0.8f) else Color.White
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = check)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(tag: Tag, count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text("Delete \"${tag.name}\"?") },
        text = {
            Text(
                if (count == 0) {
                    "No items carry this tag."
                } else {
                    "It comes off ${itemCount(count).lowercase()}. " +
                        if (count == 1) "The item stays in your library." else "The items stay in your library."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun itemCount(count: Int): String = if (count == 0) "No items" else plural(count, "item", "items")
