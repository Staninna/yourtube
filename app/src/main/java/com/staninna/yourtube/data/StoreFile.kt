package com.staninna.yourtube.data

import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Every store file is `{"schemaVersion": n, "data": ...}`, so a later version can migrate raw JSON. */
@Serializable
private class Envelope<T>(val schemaVersion: Int, val data: T)

/** How a store file was found at startup. */
enum class LoadResult { MISSING, LOADED, CORRUPT }

/**
 * One JSON file of the store and its in-memory state. Writes go through [AtomicFile] on [writer],
 * a single-threaded scope, so saves never interleave and the last save always holds the newest state.
 */
internal class StoreFile<T>(
    dir: File,
    val name: String,
    private val serializer: KSerializer<T>,
    default: T,
    private val writer: CoroutineScope,
) {
    private val file = AtomicFile(File(dir, name))
    private val envelope = Envelope.serializer(serializer)
    private val dirty = AtomicBoolean(false)

    val loadResult: LoadResult
    private val _state: MutableStateFlow<T>
    val state: StateFlow<T>

    init {
        val (result, value) = read(dir, default)
        loadResult = result
        _state = MutableStateFlow(value)
        state = _state.asStateFlow()
    }

    val value: T get() = _state.value

    /** Applies [transform] atomically and saves the file. */
    fun update(transform: (T) -> T) {
        updateInMemory(transform)
        save()
    }

    /** Applies [transform] atomically; the file is written by the next [save]. */
    fun updateInMemory(transform: (T) -> T) {
        _state.update(transform)
        dirty.set(true)
    }

    /** Writes the current state if it changed since the last save. Returns the write, if any. */
    fun save(): Job? {
        if (!dirty.compareAndSet(true, false)) return null
        // The value is read when the write runs, so a burst of updates costs at most one stale write.
        return writer.launch { write(_state.value) }
    }

    private fun read(dir: File, default: T): Pair<LoadResult, T> {
        val bytes = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return LoadResult.MISSING to default
        } catch (e: IOException) {
            return quarantine(dir, e) to default
        }
        return try {
            val decoded = json.decodeFromString(envelope, bytes.decodeToString())
            if (decoded.schemaVersion != SCHEMA_VERSION) {
                throw SerializationException("unsupported schemaVersion ${decoded.schemaVersion}")
            }
            LoadResult.LOADED to decoded.data
        } catch (e: SerializationException) {
            quarantine(dir, e) to default
        } catch (e: IllegalArgumentException) {
            quarantine(dir, e) to default
        }
    }

    /** Keeps the unreadable file as `<name>.corrupt` so nothing overwrites what the user had. */
    private fun quarantine(dir: File, cause: Exception): LoadResult {
        Log.w(TAG, "$name is unreadable, keeping it as $name.corrupt and starting empty", cause)
        file.baseFile.renameTo(File(dir, "$name.corrupt"))
        return LoadResult.CORRUPT
    }

    private fun write(value: T) {
        val bytes = json.encodeToString(envelope, Envelope(SCHEMA_VERSION, value)).toByteArray()
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.e(TAG, "opening $name for writing failed", e)
            return
        }
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            Log.e(TAG, "writing $name failed", e)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val TAG = "YT-STORE"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
