package com.staninna.yourtube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.staninna.yourtube.LibraryModel
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.TagNames
import com.staninna.yourtube.data.TagSheet
import com.staninna.yourtube.ui.components.ChannelAvatar
import com.staninna.yourtube.ui.components.TagChip
import kotlinx.coroutines.launch

/**
 * The tag sheet, over everything while the view model has one open: opened by the star in Watch and
 * by long-pressing a Library row. Tags apply as they are tapped, so dismissing it any way keeps the
 * star and every change. Remove from library takes the item out, with Undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSheetHost(library: LibraryModel) {
    val sheet by library.tagSheet.collectAsStateWithLifecycle()
    val shown = sheet ?: return
    // Hidden to fully expanded, no half step: the sheet is short enough to show whole.
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = library::closeTagSheet, sheetState = state) {
        TagSheetContent(
            shown,
            library,
            onDone = { scope.launch { state.hide() }.invokeOnCompletion { library.closeTagSheet() } },
            onRemove = { scope.launch { state.hide() }.invokeOnCompletion { library.removeFromLibrary(shown.item.id) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSheetContent(sheet: TagSheet, library: LibraryModel, onDone: () -> Unit, onRemove: () -> Unit) {
    val item = sheet.item
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
    ) {
        Header(item)
        SectionLabel("Tags")
        if (sheet.tags.isEmpty()) {
            Text(
                "No tags yet. Name one below to start sorting your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sheet.tags.forEach { tag ->
                    val on = tag.id in item.tagIds
                    TagChip(tag.name, Color(tag.color), selected = on, onClick = { library.setItemTag(item.id, tag.id, !on) })
                }
            }
        }
        NewTagField(sheet, onCreate = { library.createTag(item.id, it) })
        sheet.channel?.let { channel ->
            HorizontalDivider(Modifier.padding(top = 24.dp))
            SectionLabel("Channel")
            SubscribeRow(channel, sheet.channelStarred, onChange = { library.setSubscribed(channel, it) })
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No confirm: the snackbar's Undo brings the item back with its tags.
            TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Remove from library", Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

/** A filled star and the item's title: what was just starred, or what the tags are for. */
@Composable
private fun Header(item: StarredItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text("In your library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * Names a new tag and attaches it. A name the pool already has attaches that tag instead, and the
 * field says so before the user commits.
 */
@Composable
private fun NewTagField(sheet: TagSheet, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = TagNames.normalize(name) != null
    val create = {
        if (valid) onCreate(name)
        name = ""
    }
    val existing = TagNames.clash(name, sheet.tags)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(TagNames.MAX_LENGTH) },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        label = { Text("New tag") },
        supportingText = existing?.let { { Text("Adds your \"${it.name}\" tag") } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { create() }),
        trailingIcon = {
            IconButton(onClick = create, enabled = valid) { Icon(Icons.Filled.Add, contentDescription = "Add tag") }
        },
    )
}

/** Subscribe to a video's channel from its sheet: a whole-row switch, read by TalkBack as one. */
@Composable
private fun SubscribeRow(channel: StarredItem, subscribed: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = subscribed, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChannelAvatar(channel.title, channel.id, channel.thumbnail, size = 40.dp)
        Text(
            "Subscribe to ${channel.title}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Switch(checked = subscribed, onCheckedChange = null)
    }
}
