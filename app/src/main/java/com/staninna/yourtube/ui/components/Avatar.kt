package com.staninna.yourtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * A round channel avatar. Until [url] loads, or without one, it shows [name]'s initial on a color
 * picked from [key], so a channel keeps its color everywhere. It is decoration to TalkBack: the row
 * around it names the channel, and the placeholder letter would only add noise.
 */
@Composable
fun ChannelAvatar(name: String, key: String, url: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val (container, content) = placeholderColors(key)
    Box(modifier.size(size).clip(CircleShape).background(container).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(
            Placeholder.initial(name),
            color = content,
            fontWeight = FontWeight.SemiBold,
            // Scales with the avatar, not the font scale: the letter must fit the circle.
            fontSize = (size.value * 0.42f).sp,
            style = MaterialTheme.typography.titleMedium,
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Tone 90 containers with tone 10 letters in light mode, tone 30 with tone 90 in dark. */
@Composable
private fun placeholderColors(key: String): Pair<Color, Color> {
    val (light, dark) = PALETTE[Placeholder.colorSlot(key, PALETTE.size)]
    return if (isSystemInDarkTheme()) dark else light
}

private fun pair(container: Long, content: Long) = Color(container) to Color(content)

// Red, orange, amber, green, teal, blue, violet, pink.
private val PALETTE = listOf(
    pair(0xFFFFDAD6, 0xFF410002) to pair(0xFF73332F, 0xFFFFDAD6),
    pair(0xFFFFDCC2, 0xFF2E1500) to pair(0xFF6B3B0B, 0xFFFFDCC2),
    pair(0xFFFFDF9E, 0xFF261A00) to pair(0xFF5B4300, 0xFFFFDF9E),
    pair(0xFFC2EFAE, 0xFF072100) to pair(0xFF2A5020, 0xFFC2EFAE),
    pair(0xFFA6F2E4, 0xFF00201C) to pair(0xFF00504A, 0xFFA6F2E4),
    pair(0xFFD6E3FF, 0xFF001B3E) to pair(0xFF2A4677, 0xFFD6E3FF),
    pair(0xFFE9DDFF, 0xFF22005D) to pair(0xFF4F378B, 0xFFE9DDFF),
    pair(0xFFFFD8EB, 0xFF3A0729) to pair(0xFF6E2C55, 0xFFFFD8EB),
)
