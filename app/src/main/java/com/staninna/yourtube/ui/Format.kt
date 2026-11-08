package com.staninna.yourtube.ui

import kotlin.math.roundToLong

/** A video length as YouTube writes it (`4:05`, `1:02:09`), or null when it is not known yet. */
fun formatDuration(seconds: Double): String? {
    if (seconds <= 0.0 || seconds.isNaN() || seconds.isInfinite()) return null
    val total = seconds.roundToLong()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
