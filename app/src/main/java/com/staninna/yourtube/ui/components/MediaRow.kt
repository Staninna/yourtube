package com.staninna.yourtube.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.staninna.yourtube.data.WatchProgress
import kotlin.math.roundToInt

/** What leads a [MediaRow]: a video still, or a channel avatar. */
sealed interface MediaLeading {
    /**
     * A 16:9 still. [url] null shows [fallbackIcon] on a tinted block. [progress] draws the watched
     * bar and dims finished videos; [badge] is the length (`12:34`) or what stands in for one (`LIVE`).
     */
    data class Thumbnail(
        val url: String?,
        val progress: WatchProgress? = null,
        val badge: String? = null,
        val fallbackIcon: ImageVector? = null,
    ) : MediaLeading

    /** A round channel avatar; [key] picks the placeholder color when there is no image. */
    data class Avatar(val name: String, val key: String, val url: String?) : MediaLeading
}

/**
 * The one list row for videos, channels and playlists across Feeds, Library and History: a leading
 * still or avatar, a two-line title, a one-line subtitle, an optional detail line under it (Feeds
 * only) and an optional trailing action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    title: String,
    subtitle: String?,
    leading: MediaLeading,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** What the long press does, as TalkBack offers it ("Edit tags"). */
    onLongClickLabel: String? = null,
    /** Something small before the subtitle, such as Feeds' channel avatar. */
    subtitleLeading: (@Composable () -> Unit)? = null,
    /** Text after the subtitle, as " · [subtitleEnd]", that a long subtitle never cuts (History's time left). */
    subtitleEnd: String? = null,
    /** A line under the subtitle, such as Feeds' views and age. */
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val progress = (leading as? MediaLeading.Thumbnail)?.progress
    val finished = progress?.finished == true
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { progress?.let { stateDescription = describe(it) } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (leading) {
            is MediaLeading.Thumbnail -> Thumbnail(leading, Modifier.width(LEADING_WIDTH))
            // Centered in the slot a still takes, as tall as a still, so titles line up with video rows.
            is MediaLeading.Avatar -> Box(Modifier.width(LEADING_WIDTH), contentAlignment = Alignment.Center) {
                ChannelAvatar(leading.name, leading.key, leading.url, size = LEADING_WIDTH * 9f / 16f)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val text = subtitle?.takeIf { it.isNotBlank() }
            if (text != null || subtitleLeading != null || subtitleEnd != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    subtitleLeading?.invoke()
                    SubtitleLine(text, subtitleEnd, Modifier.weight(1f))
                }
            }
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * One line of [text] (ellipsized) then " · [end]", which a long [text] never cuts: History's "12 min
 * left", a card's age. Either may be missing.
 */
@Composable
internal fun SubtitleLine(text: String?, end: String?, modifier: Modifier = Modifier) {
    Row(modifier) {
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        end?.let {
            Text(
                if (text != null) " · $it" else it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A 16:9 still with its badge and watched bar, for [MediaRow] and [MediaCard]; [modifier] sets its width. */
@Composable
internal fun Thumbnail(thumb: MediaLeading.Thumbnail, modifier: Modifier) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb.url == null) {
            thumb.fallbackIcon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            AsyncImage(
                model = thumb.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (thumb.progress?.finished == true) 0.5f else 1f),
            )
        }
        // The badge sits above the watched bar when there is one.
        val badgeBottom = if (thumb.progress != null) 16.dp else 6.dp
        thumb.badge?.let { Badge(it, Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = badgeBottom)) }
        thumb.progress?.let { WatchedBar(it.fraction, Modifier.align(Alignment.BottomCenter)) }
    }
}

/** A length or LIVE badge on a still, white on translucent black like YouTube's, legible on any image. */
@Composable
private fun Badge(text: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.75f), contentColor = Color.White) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** A rounded bar floating inside the still's corners, so the rounding never clips a short one. */
@Composable
private fun WatchedBar(fraction: Float, modifier: Modifier) {
    Box(
        modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.5f)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceAtLeast(0.04f)).clip(CircleShape).background(WATCHED_PURPLE))
    }
}

/** The leading slot's width: a 16:9 still, or an avatar as tall as one. */
private val LEADING_WIDTH = 144.dp

/** The app icon's purple, which also replaces YouTube's red progress bars in pages (PageConfig). */
private val WATCHED_PURPLE = Color(0xFF7C3AED)

/** What TalkBack hears about a still's watched bar. */
internal fun describe(progress: WatchProgress): String =
    if (progress.finished) "Watched" else "${(progress.fraction * 100).roundToInt()}% watched"
