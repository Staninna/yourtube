package com.staninna.yourtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A button under a state message. */
data class StateAction(val label: String, val onClick: () -> Unit)

/** Nothing to show yet: an icon in an expressive shape, a title, a hint, and maybe a way forward. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: StateAction? = null) {
    StateLayout(modifier, icon, title, body, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) {
        action?.let { FilledTonalButton(onClick = it.onClick) { Text(it.label) } }
    }
}

/** Something failed: the same layout in error colors, with a primary action such as Retry. */
@Composable
fun ErrorState(icon: ImageVector, title: String, body: String, action: StateAction, modifier: Modifier = Modifier) {
    StateLayout(modifier, icon, title, body, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) {
        Button(onClick = action.onClick) { Text(action.label) }
    }
}

/** First load of a screen: the expressive morphing loading indicator with a label for TalkBack. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().semantics(mergeDescendants = true) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LoadingIndicator(Modifier.size(64.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StateLayout(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    body: String,
    container: Color,
    content: Color,
    action: @Composable () -> Unit,
) {
    val shape = MaterialShapes.Cookie9Sided.toShape()
    // Scrolls so a large font scale or a landscape phone never clips the action.
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 360.dp).padding(horizontal = 32.dp, vertical = 48.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(96.dp).clip(shape).background(container), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(40.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Box(Modifier.padding(top = 12.dp)) { action() }
            }
        }
    }
}
