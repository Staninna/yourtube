package com.staninna.yourtube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Full-screen YouTube search: a focused field with the keyboard up, starting from the query of the
 * results page Watch shows (if any). Submitting hands the text to [onSearch], which loads the
 * results in Watch (the caller closes the overlay when a search happened); back closes it. Once
 * [open] turns false the overlay only fades out, and Back is the shell's again.
 */
@Composable
fun SearchOverlay(initialQuery: String?, open: Boolean, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    val start = initialQuery.orEmpty()
    // Selected, like a browser's address bar: typing replaces it, a tap on the field edits it.
    val text = rememberTextFieldState(start, TextRange(0, start.length))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(enabled = open, onBack = onDismiss)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                TextField(
                    state = text,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Search YouTube") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    onKeyboardAction = { onSearch(text.text.toString()) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (text.text.isNotEmpty()) {
                    IconButton(onClick = { text.clearText() }) { Icon(Icons.Filled.Close, "Clear") }
                }
            }
            HorizontalDivider()
            val query = text.text.toString().trim()
            if (query.isNotEmpty()) {
                ListItem(
                    onClick = { onSearch(query) },
                    leadingContent = { Icon(Icons.Filled.Search, contentDescription = null) },
                    supportingContent = { Text("Search YouTube") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(query, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            } else {
                Text(
                    "Results open in Watch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }
}
