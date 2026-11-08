package com.staninna.yourtube.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.staninna.yourtube.ui.components.SegmentedButtons

/** One row of a settings card, given its place in the card as [ListItemShapes]. */
typealias SettingRow = @Composable (ListItemShapes) -> Unit

/** A heading over rows that read as one rounded card, Android 16 style. */
@Composable
fun Section(title: String, rows: List<SettingRow>) {
    SectionHeading(title)
    Card(rows)
}

/** A section's heading alone, for a section made of several [Group]s. */
@Composable
fun SectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A named card inside a section, such as Content's Ad blocking. */
@Composable
fun Group(title: String, rows: List<SettingRow>) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
    )
    Card(rows)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Card(rows: List<SettingRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, rows.size)) }
    }
}

/** A setting that turns on and off; the whole row toggles, and TalkBack hears a switch. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwitchRow(title: String, summary: String, state: SwitchState, onChange: (Boolean) -> Unit, shapes: ListItemShapes) {
    SegmentedListItem(
        shapes = shapes,
        enabled = state.enabled,
        modifier = Modifier
            .clip(shapes.shape)
            .toggleable(value = state.checked, enabled = state.enabled, role = Role.Switch, onValueChange = onChange),
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = state.checked, onCheckedChange = null, enabled = state.enabled) },
    ) { Text(title) }
}

/**
 * A row that opens something or starts an action. [icon] is null in a card that mixes it with
 * switch rows, so every title in the card starts at the same edge.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionRow(
    title: String,
    summary: String,
    icon: ImageVector?,
    shapes: ListItemShapes,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        supportingContent = { Text(summary) },
    ) { Text(title) }
}

/** One choice among a few, as a connected button group under the setting's title. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChoiceRow(
    title: String,
    summary: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    shapes: ListItemShapes,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        shapes = shapes,
        enabled = enabled,
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(summary)
                SegmentedButtons(options, selectedIndex, onSelect, Modifier.padding(bottom = 4.dp), enabled)
            }
        },
    ) { Text(title, Modifier.semantics { heading() }) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InfoRow(label: String, value: String, shapes: ListItemShapes) {
    SegmentedListItem(
        shapes = shapes,
        modifier = Modifier.semantics(mergeDescendants = true) {},
        supportingContent = { Text(value) },
    ) { Text(label) }
}
