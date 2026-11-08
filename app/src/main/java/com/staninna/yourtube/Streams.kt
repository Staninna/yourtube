package com.staninna.yourtube

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads until the end or [limit] bytes, whichever comes first. A caller that must refuse a longer
 * input reads `limit + 1` and checks the size.
 */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
