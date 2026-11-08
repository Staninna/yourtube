package com.staninna.yourtube.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.ServerInput
import com.staninna.yourtube.data.Settings
import com.staninna.yourtube.data.SponsorBlockSettings
import com.staninna.yourtube.ui.components.SegmentedButtons
import com.staninna.yourtube.ui.components.TagDot

/**
 * Settings > Content: one card per concern. Ad blocking has a switch per layer; SponsorBlock has its
 * switch, the per-category actions in a bottom sheet and the server in a dialog (both locked while
 * SponsorBlock is off); Shorts has its one switch. Every change applies to the page on screen.
 */
@Composable
fun ContentSettings(settings: Settings, vm: MainViewModel) {
    var segmentsOpen by rememberSaveable { mutableStateOf(false) }
    var serverOpen by rememberSaveable { mutableStateOf(false) }
    val sponsor = settings.sponsorBlock

    SectionHeading("Content")
    Group(
        "Ad blocking",
        AdLayer.entries.map { layer ->
            { shapes ->
                val text = ContentText.layer(layer)
                SwitchRow(text.title, text.summary, SwitchState(settings.adBlocking.isOn(layer), enabled = true), { vm.setAdLayer(layer, it) }, shapes)
            }
        },
    )
    Group(
        "SponsorBlock",
        listOf(
            { shapes ->
                SwitchRow(
                    "Use SponsorBlock",
                    "Skips or marks the parts viewers flagged, like sponsor reads. Lookups never name the video.",
                    SwitchState(sponsor.enabled, enabled = true),
                    vm::setSponsorBlock,
                    shapes,
                )
            },
            { shapes ->
                ActionRow("Segments", ContentText.segments(sponsor), icon = null, shapes, enabled = sponsor.enabled) {
                    segmentsOpen = true
                }
            },
            { shapes ->
                ActionRow("Server", ContentText.server(sponsor), icon = null, shapes, enabled = sponsor.enabled) {
                    serverOpen = true
                }
            },
        ),
    )
    Group(
        "Shorts",
        listOf { shapes ->
            SwitchRow(
                "Remove all Shorts",
                "Hides Shorts everywhere, opens Shorts links as normal videos, and leaves Shorts out of Feeds",
                SwitchState(settings.removeShorts, enabled = true),
                vm::setRemoveShorts,
                shapes,
            )
        },
    )

    if (segmentsOpen) SegmentSheet(sponsor, onAction = vm::setSegmentAction, onDismiss = { segmentsOpen = false })
    if (serverOpen) {
        ServerDialog(
            current = sponsor.server,
            onSave = {
                vm.setSponsorServer(it)
                serverOpen = false
            },
            onDismiss = { serverOpen = false },
        )
    }
}

/**
 * Every category with its color (as on the progress bar), what it covers, and its action as a
 * connected button group. Each category is one TalkBack group, so its name comes before its choices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentSheet(settings: SponsorBlockSettings, onAction: (SegmentCategory, SegmentAction) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text("Segments", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                "What happens when a video reaches a part viewers marked. Show marks it on the progress bar and plays it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            SegmentCategory.entries.forEach { category ->
                val text = ContentText.category(category)
                Column(Modifier.padding(top = 20.dp).semantics { isTraversalGroup = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TagDot(Color(category.color), size = 12.dp)
                        Column {
                            Text(text.title, style = MaterialTheme.typography.titleSmall)
                            Text(text.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SegmentedButtons(
                        ContentText.actions,
                        selectedIndex = settings.action(category).ordinal,
                        onSelect = { onAction(category, SegmentAction.entries[it]) },
                        modifier = Modifier.padding(top = 8.dp).selectableGroup(),
                    )
                }
            }
        }
    }
}

/** The server address, checked as it is typed; Save stays off until it is a usable https address. */
@Composable
private fun ServerDialog(current: String, onSave: (ServerInput.Valid) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val input = ServerInput.parse(text)
    val save = { (input as? ServerInput.Valid)?.let(onSave) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SponsorBlock server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Segments come from this server. Change it only to use a mirror you trust.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Address") },
                    singleLine = true,
                    isError = input is ServerInput.Invalid,
                    supportingText = (input as? ServerInput.Invalid)?.let { invalid -> { Text(ContentText.serverProblem(invalid.problem)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.trim() != SponsorBlockSettings.DEFAULT_SERVER) {
                    TextButton(onClick = { text = SponsorBlockSettings.DEFAULT_SERVER }) { Text("Use the default server") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save() }, enabled = input is ServerInput.Valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
