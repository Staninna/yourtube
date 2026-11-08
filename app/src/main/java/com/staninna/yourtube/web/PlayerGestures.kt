package com.staninna.yourtube.web

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.math.roundToInt

/**
 * Turns the page's vertical swipes on the video ([Swipe], see `main.js`) into the music volume (right
 * half) and this window's screen brightness (left half), with a small label over everything, the
 * fullscreen video included, saying where each stands. The brightness holds for this window until
 * [reset]; Android's own setting is never touched.
 */
internal class PlayerGestures(private val activity: ComponentActivity) {
    private val audio: AudioManager? = activity.getSystemService(AudioManager::class.java)
    private var base = 0.0
    private var label: TextView? = null
    private val hide = Runnable { label?.animate()?.alpha(0f)?.setDuration(FADE_MS) }

    fun on(swipe: Swipe) {
        when (swipe.phase) {
            Swipe.Phase.START -> {
                base = current(swipe.control)
                show(swipe.control, base)
            }
            Swipe.Phase.MOVE -> show(swipe.control, set(swipe.control, SwipeLevel.of(base, swipe.amount)))
            Swipe.Phase.END -> label?.run {
                removeCallbacks(hide)
                postDelayed(hide, LINGER_MS)
            }
        }
    }

    /** Gives the screen's brightness back to Android. */
    fun reset() {
        val window = activity.window
        if (window.attributes.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) return
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    /** Where [control] stands now, 0 to 1. */
    private fun current(control: Swipe.Control): Double = when (control) {
        Swipe.Control.VOLUME -> audio?.let {
            val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) it.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max else 0.0
        } ?: 0.0
        Swipe.Control.BRIGHTNESS -> activity.window.attributes.screenBrightness.takeIf { it >= 0f }?.toDouble()
            ?: (systemBrightness() / 255.0).coerceIn(0.0, 1.0)
    }

    /** Sets [control] to [level] as closely as it goes, and returns where it ended up. */
    private fun set(control: Swipe.Control, level: Double): Double = when (control) {
        Swipe.Control.VOLUME -> audio?.let {
            val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val step = (level * max).roundToInt().coerceIn(0, max)
            if (step != it.getStreamVolume(AudioManager.STREAM_MUSIC)) it.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
            if (max > 0) step.toDouble() / max else 0.0
        } ?: 0.0
        Swipe.Control.BRIGHTNESS -> {
            val window = activity.window
            // Never quite 0: some devices turn the backlight off there.
            val brightness = level.coerceIn(MIN_BRIGHTNESS, 1.0)
            window.attributes = window.attributes.apply { screenBrightness = brightness.toFloat() }
            level
        }
    }

    private fun systemBrightness(): Int =
        try {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Settings.SettingNotFoundException) {
            128
        }

    private fun show(control: Swipe.Control, level: Double) {
        val view = label ?: create().also { label = it }
        view.text = SwipeLevel.label(control, level)
        // Over the fullscreen video too, which is added to the content view after the label.
        if (view.parent == null) content().addView(view, layoutParams())
        view.bringToFront()
        view.removeCallbacks(hide)
        view.animate().cancel()
        view.alpha = 1f
    }

    private fun content(): FrameLayout = activity.findViewById(android.R.id.content)

    private fun create(): TextView = TextView(activity).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        val padH = dp(16)
        val padV = dp(8)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = dp(20).toFloat()
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun layoutParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
    )

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val LINGER_MS = 600L
        const val FADE_MS = 200L
        const val MIN_BRIGHTNESS = 0.01
    }
}

/** The arithmetic of a swipe, kept apart from Android for tests. */
internal object SwipeLevel {
    /** The level a swipe of [amount] (a share of the player's height, up is positive) gives from [base]. */
    fun of(base: Double, amount: Double): Double = (base + amount).coerceIn(0.0, 1.0)

    /** What the label says, such as "Volume 40%". */
    fun label(control: Swipe.Control, level: Double): String {
        val percent = (level.coerceIn(0.0, 1.0) * 100).roundToInt()
        return when (control) {
            Swipe.Control.VOLUME -> "Volume $percent%"
            Swipe.Control.BRIGHTNESS -> "Brightness $percent%"
        }
    }
}
