package com.staninna.yourtube.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.staninna.yourtube.data.LibrarySort
import com.staninna.yourtube.data.TagChoices
import com.staninna.yourtube.data.TagFiltering
import com.staninna.yourtube.data.TagMatch
import com.staninna.yourtube.ui.components.SegmentedButtons
import com.staninna.yourtube.ui.components.TagChip
import com.staninna.yourtube.ui.components.TagDot
import kotlinx.coroutines.launch

/** How a filter sheet counts what carries a tag: "1 video", "12 videos". */
data class Noun(val one: String, val many: String)

/**
 * The compact row over a filtered list: the Tags button, which says what is selected and opens the tag
 * filter sheet, a clear button while a filter is on, and [end] (Library's Sort) at the far end. The
 * Tags button shows once there is a tag to pick.
 */
@Composable
fun TagFilterBar(
    choices: TagChoices,
    filtering: TagFiltering,
    noun: Noun,
    modifier: Modifier = Modifier,
    end: @Composable () -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (choices.tags.isNotEmpty()) {
                TagsButton(choices, onClick = { open = true }, Modifier.weight(1f, fill = false))
                if (choices.filter.active) {
                    IconButton(onClick = filtering::clearTagFilter) { Icon(Icons.Filled.Close, contentDescription = "Clear tag filter") }
                }
            }
        }
        end()
    }
    if (open) TagFilterSheet(choices, filtering, noun, onDismiss = { open = false })
}

/**
 * "All tags", or what is selected with the selected tags' dots (up to three). TalkBack hears every
 * selected name as one button.
 */
@Composable
private fun TagsButton(choices: TagChoices, onClick: () -> Unit, modifier: Modifier) {
    val description = TagFilterText.description(choices)
    FilterChip(
        selected = choices.filter.active,
        onClick = onClick,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick(label = "Choose tags") {
                onClick()
                true
            }
        },
        label = { Text(TagFilterText.label(choices), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            val dots = choices.selectedTags.take(3)
            if (dots.isEmpty()) {
                Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { dots.forEach { TagDot(Color(it.color)) } }
            }
        },
        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

/**
 * Every tag the list's items carry, with how many carry it, plus Untagged, Any or All, Clear and Done.
 * Taps apply at once, so the list behind the sheet follows them and dismissing keeps them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagFilterSheet(choices: TagChoices, filtering: TagFiltering, noun: Noun, onDismiss: () -> Unit) {
    // Hidden to fully expanded, no half step, as the tag sheet does.
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val filter = choices.filter
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            Text("Filter by tags", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            // Untagged has no tags to combine, so the match mode waits while it is on.
            SegmentedButtons(
                listOf("Match any", "Match all"),
                selectedIndex = if (filter.match == TagMatch.ALL) 1 else 0,
                onSelect = { filtering.setTagMatch(if (it == 1) TagMatch.ALL else TagMatch.ANY) },
                enabled = !filter.untagged,
                modifier = Modifier.padding(top = 16.dp),
            )
            FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.tags.forEach { tag ->
                    val count = choices.counts[tag.id] ?: 0
                    TagChip(
                        tag.name,
                        Color(tag.color),
                        selected = tag.id in filter.tags,
                        onClick = { filtering.toggleTagFilter(tag.id) },
                        count = count.toString(),
                        countDescription = plural(count, noun.one, noun.many),
                    )
                }
                if (choices.untagged > 0) {
                    TagChip(
                        "Untagged",
                        color = null,
                        selected = filter.untagged,
                        onClick = filtering::toggleUntaggedFilter,
                        count = choices.untagged.toString(),
                        countDescription = plural(choices.untagged, noun.one, noun.many),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = filtering::clearTagFilter, enabled = filter.active) { Text("Clear") }
                Button(onClick = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }) { Text("Done") }
            }
        }
    }
}

/** Library's order as a chip with a small menu of [options]: Recently added, Name A to Z, and in Subs Latest upload. */
@Composable
fun SortButton(sort: LibrarySort, options: List<LibrarySort>, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(sortLabel(sort)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.semantics { contentDescription = "Sort: ${sortLabel(sort)}" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            options.forEach { option ->
                val chosen = option == sort
                DropdownMenuItem(
                    text = { Text(sortLabel(option)) },
                    leadingIcon = { if (chosen) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                    modifier = Modifier.semantics {
                        selected = chosen
                        role = Role.RadioButton
                    },
                )
            }
        }
    }
}

fun sortLabel(sort: LibrarySort): String = when (sort) {
    LibrarySort.RECENT -> "Recently added"
    LibrarySort.NAME -> "Name A to Z"
    LibrarySort.LATEST_UPLOAD -> "Latest upload"
}
