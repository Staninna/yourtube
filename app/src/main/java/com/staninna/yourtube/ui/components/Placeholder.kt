package com.staninna.yourtube.ui.components

/** The initial and color slot of a channel without an avatar image. Pure, so it is the same everywhere. */
object Placeholder {
    /**
     * The first letter or digit of [name], uppercased, skipping a handle's `@` and leading symbols;
     * the first character when there is no letter; `?` for a blank name.
     */
    fun initial(name: String): String {
        val trimmed = name.trim()
        val start = trimmed.indexOfFirst { it.isLetterOrDigit() }.takeIf { it >= 0 } ?: 0
        if (trimmed.isEmpty()) return "?"
        // Whole code points, so a surrogate pair (emoji, many CJK names) is never cut in half.
        val end = trimmed.offsetByCodePoints(start, 1)
        return trimmed.substring(start, end).uppercase()
    }

    /**
     * A slot in a palette of [size] colors for [key] (a channel id or name). Deterministic across runs
     * and devices: `String.hashCode` is specified by the JVM.
     */
    fun colorSlot(key: String, size: Int): Int = Math.floorMod(key.hashCode(), size)
}
