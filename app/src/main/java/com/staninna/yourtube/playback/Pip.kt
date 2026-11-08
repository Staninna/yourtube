package com.staninna.yourtube.playback

import com.staninna.yourtube.data.PipMode
import kotlin.math.roundToInt

/** Whether the app may use picture-in-picture on this device. */
enum class PipSupport {
    AVAILABLE,

    /** The device has no picture-in-picture (`FEATURE_PICTURE_IN_PICTURE`). */
    NO_FEATURE,

    /** The user turned picture-in-picture off for the app in Android's settings. */
    TURNED_OFF,
}

/**
 * D4's rows for entering picture-in-picture. What closing and expanding the window do to the session
 * is [BackgroundPlayback]'s.
 */
object PipPolicy {
    /**
     * Whether leaving the app now moves the video into picture-in-picture: it plays, Watch shows it
     * (YouTube's fullscreen always does), and [mode] and [support] allow it from there. Otherwise
     * leaving keeps the background audio with its notification.
     */
    fun autoEnter(mode: PipMode, support: PipSupport, playing: Boolean, watchShown: Boolean, fullscreen: Boolean): Boolean =
        support == PipSupport.AVAILABLE && playing && when (mode) {
            PipMode.AUTO -> watchShown || fullscreen
            PipMode.FULLSCREEN -> fullscreen
            PipMode.MANUAL -> false
        }

    /**
     * Whether Watch's menu offers picture-in-picture: in Manual mode, while the video plays. A paused
     * video has no media session yet, so its window would have no play button.
     */
    fun offersEntry(mode: PipMode, support: PipSupport, playing: Boolean): Boolean =
        mode == PipMode.MANUAL && support == PipSupport.AVAILABLE && playing
}

/** A rectangle in window pixels. */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(other: PxRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

/**
 * The picture-in-picture window's shape: the video's aspect ratio within Android's limits, and the
 * part of the app window that shows the picture, which the enter animation grows from.
 */
data class PipShape(val aspectWidth: Int, val aspectHeight: Int, val sourceRect: PxRect?) {
    companion object {
        /** `PictureInPictureParams` refuses anything wider than 2.39:1 or taller than 1:2.39. */
        private const val LIMIT_LONG = 239
        private const val LIMIT_SHORT = 100

        /** [width]:[height], clamped to Android's limits. */
        fun aspect(width: Int, height: Int): Pair<Int, Int> = when {
            width.toLong() * LIMIT_SHORT > height.toLong() * LIMIT_LONG -> LIMIT_LONG to LIMIT_SHORT
            height.toLong() * LIMIT_SHORT > width.toLong() * LIMIT_LONG -> LIMIT_SHORT to LIMIT_LONG
            else -> width to height
        }

        /**
         * A box the page measured in CSS pixels, in window pixels: [scale] pixels per CSS pixel,
         * counted from the WebView's [originX], [originY].
         */
        fun toWindow(left: Double, top: Double, right: Double, bottom: Double, scale: Double, originX: Int, originY: Int): PxRect =
            PxRect(
                originX + (left * scale).roundToInt(),
                originY + (top * scale).roundToInt(),
                originX + (right * scale).roundToInt(),
                originY + (bottom * scale).roundToInt(),
            )

        /**
         * The shape for a [videoWidth] by [videoHeight] video shown in [box]. The picture sits in the
         * middle of its box at its own aspect ratio, cropped to the clamped one. The source rect is
         * null when that picture is not wholly inside [window] (scrolled partly away).
         */
        fun of(videoWidth: Int, videoHeight: Int, box: PxRect, window: PxRect): PipShape? {
            if (videoWidth <= 0 || videoHeight <= 0) return null
            val (aspectWidth, aspectHeight) = aspect(videoWidth, videoHeight)
            val whole = fit(videoWidth, videoHeight, box)
            val clamped = aspectWidth != videoWidth || aspectHeight != videoHeight
            val picture = if (clamped) fit(aspectWidth, aspectHeight, whole) else whole
            val source = picture.takeIf { it.width > 0 && it.height > 0 && window.contains(it) }
            return PipShape(aspectWidth, aspectHeight, source)
        }

        /** The largest [width]:[height] rect centered in [box]. */
        private fun fit(width: Int, height: Int, box: PxRect): PxRect {
            val wide = width.toLong() * box.height > height.toLong() * box.width
            return if (wide) {
                val h = (box.width.toLong() * height / width).toInt()
                val top = box.top + (box.height - h) / 2
                PxRect(box.left, top, box.right, top + h)
            } else {
                val w = (box.height.toLong() * width / height).toInt()
                val left = box.left + (box.width - w) / 2
                PxRect(left, box.top, left + w, box.bottom)
            }
        }
    }
}
