package com.staninna.yourtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.staninna.yourtube.page.NowPlaying

/**
 * The video loaded in Watch, shown above the navigation bar on the native tabs: still, title, channel
 * and a play/pause button; tapping the rest returns to Watch. The progress line waves while the video
 * plays and lies flat when it is paused.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NowPlayingBar(
    nowPlaying: NowPlaying,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = nowPlaying.title ?: "Video"
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            Modifier
                .clickable(onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull("Now playing", title, nowPlaying.author).joinToString(", ")
                    onClick(label = "Open Watch") { onOpen(); true }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = nowPlaying.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(48.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    nowPlaying.author?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FilledTonalIconButton(onClick = onPlayPause) {
                    if (nowPlaying.playing) Icon(Icons.Filled.Pause, "Pause") else Icon(Icons.Filled.PlayArrow, "Play")
                }
            }
            ProgressLine(nowPlaying)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressLine(nowPlaying: NowPlaying) {
    val stroke = with(LocalDensity.current) { Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round) }
    val progress = nowPlaying.progress ?: 0f
    LinearWavyProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        stroke = stroke,
        trackStroke = stroke,
        amplitude = { if (nowPlaying.playing) WavyProgressIndicatorDefaults.indicatorAmplitude(it) else 0f },
        wavelength = 20.dp,
    )
}
