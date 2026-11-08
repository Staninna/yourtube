package com.staninna.yourtube.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A video as a card in a horizontal row: the still with its badge and watched bar, a two-line title,
 * and a line with [subtitle] and then [subtitleEnd] ("12 min left", "3 days ago"), which a long
 * subtitle never cuts. TalkBack reads the card as one item, with its watched share.
 */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    thumbnail: MediaLeading.Thumbnail,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleEnd: String? = null,
) {
    Column(
        modifier
            .width(CARD_WIDTH)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp)
            .semantics(mergeDescendants = true) { thumbnail.progress?.let { stateDescription = describe(it) } },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Thumbnail(thumbnail, Modifier.fillMaxWidth().padding(bottom = 4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        val text = subtitle?.takeIf { it.isNotBlank() }
        if (text != null || subtitleEnd != null) SubtitleLine(text, subtitleEnd, Modifier.padding(horizontal = 4.dp))
    }
}

/** Wide enough for a legible still and two lines of title; a phone shows about two cards and a half. */
private val CARD_WIDTH = 176.dp
