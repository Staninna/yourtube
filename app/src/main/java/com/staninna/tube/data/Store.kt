package com.staninna.tube.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Local persistence for the login-free library, history and settings. */
object Store {
    private val json = Json { ignoreUnknownKeys = true }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File
    private const val HISTORY_LIMIT = 500

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = context.filesDir
        _library.value = read("library.json", LibraryState())
        _history.value = read("history.json", emptyList())
        _settings.value = read("settings.json", Settings())
    }

    private inline fun <reified T> read(name: String, fallback: T): T =
        runCatching { json.decodeFromString<T>(File(dir, name).readText()) }.getOrDefault(fallback)

    private inline fun <reified T> write(name: String, value: T) {
        io.launch { runCatching { File(dir, name).writeText(json.encodeToString(value)) } }
    }

    // --- library ---

    fun isStarred(id: String): Boolean = _library.value.items.any { it.id == id }

    fun toggleStar(item: StarredItem) {
        val items = _library.value.items
        val next = if (items.any { it.id == item.id }) {
            items.filterNot { it.id == item.id }
        } else {
            listOf(item) + items
        }
        _library.value = _library.value.copy(items = next)
        write("library.json", _library.value)
    }

    fun setFolder(itemId: String, folderId: String?) {
        _library.value = _library.value.copy(
            items = _library.value.items.map { if (it.id == itemId) it.copy(folderId = folderId) else it },
        )
        write("library.json", _library.value)
    }

    fun addFolder(name: String): Folder {
        val folder = Folder(id = UUID.randomUUID().toString(), name = name)
        _library.value = _library.value.copy(folders = _library.value.folders + folder)
        write("library.json", _library.value)
        return folder
    }

    fun removeFolder(id: String) {
        _library.value = _library.value.copy(
            folders = _library.value.folders.filterNot { it.id == id },
            items = _library.value.items.map { if (it.folderId == id) it.copy(folderId = null) else it },
        )
        write("library.json", _library.value)
    }

    // --- history ---

    fun recordWatch(entry: HistoryEntry) {
        if (entry.videoId.isBlank()) return
        val rest = _history.value.filterNot { it.videoId == entry.videoId }
        val existing = _history.value.firstOrNull { it.videoId == entry.videoId }
        val merged = entry.copy(
            title = entry.title.ifBlank { existing?.title ?: "" },
            author = entry.author.ifBlank { existing?.author ?: "" },
            thumbnail = entry.thumbnail.ifBlank { existing?.thumbnail ?: "" },
        )
        _history.value = (listOf(merged) + rest).take(HISTORY_LIMIT)
        write("history.json", _history.value)
    }

    fun clearHistory() {
        _history.value = emptyList()
        write("history.json", _history.value)
    }

    fun removeHistory(videoId: String) {
        _history.value = _history.value.filterNot { it.videoId == videoId }
        write("history.json", _history.value)
    }

    // --- settings ---

    fun updateSettings(transform: (Settings) -> Settings) {
        _settings.value = transform(_settings.value)
        write("settings.json", _settings.value)
    }

    /**
     * Point the singleton at a clean [context] file dir and drop all in-memory state. Tests call
     * this so each one starts from an empty store, which [init]'s one-shot guard would otherwise
     * prevent.
     */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTest(context: Context) {
        dir = context.filesDir
        _library.value = LibraryState()
        _history.value = emptyList()
        _settings.value = Settings()
    }
}
