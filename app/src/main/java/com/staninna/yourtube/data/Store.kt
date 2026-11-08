package com.staninna.yourtube.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.UUID

/**
 * The app's first-party state: library, tags, history (which holds resume positions), settings and
 * the channel cache. Identity changes never touch it. Every mutation is one atomic `update {}` on its
 * file.
 *
 * Library, settings and channel changes are written right away. History changes with every playback
 * event, so it is kept in memory and written every [PLAYBACK_FLUSH_MS] while playing, and on [flush]
 * (pause, end, Activity stop and destroy).
 *
 * [scope] must run one coroutine at a time; it is the store's single writer.
 */
class Store(
    dir: File,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val libraryFile = StoreFile(dir, "library.json", Library.serializer(), Library(), scope)
    private val historyFile =
        StoreFile(dir, "history.json", ListSerializer(HistoryEntry.serializer()), emptyList(), scope)
    private val settingsFile = StoreFile(dir, "settings.json", Settings.serializer(), Settings(), scope)
    private val channelsFile = StoreFile(
        dir, "channels.json", MapSerializer(String.serializer(), CachedChannel.serializer()), emptyMap(), scope,
    )
    private val files = listOf(libraryFile, historyFile, settingsFile, channelsFile)

    val library: StateFlow<Library> = libraryFile.state

    /** Watched videos, most recent first, each with its playback position. */
    val history: StateFlow<List<HistoryEntry>> = historyFile.state
    val settings: StateFlow<Settings> = settingsFile.state

    /** Resolved channels keyed by channel page path (see `PageIdentity.Channel.path`). */
    val channels: StateFlow<Map<String, CachedChannel>> = channelsFile.state

    /**
     * Files that could not be read at startup; each was kept as `<name>.corrupt`. Stage 4 shows a
     * one-time notice with an export action from this.
     */
    val corruptFiles: Set<String> = files.filter { it.loadResult == LoadResult.CORRUPT }.map { it.name }.toSet()

    @Volatile private var pendingFlush: Job? = null

    // --- library ---

    /** Stars [item] (newest first, stamped now) or, when an item with its id is starred, unstars it. */
    fun toggleStar(item: StarredItem) = libraryFile.update { lib ->
        val starred = lib.items.any { it.id == item.id }
        lib.copy(
            items = if (starred) lib.items.filterNot { it.id == item.id } else listOf(item.copy(addedAt = now())) + lib.items,
        )
    }

    /** Creates a tag with the next palette color. */
    fun createTag(name: String): Tag {
        val id = UUID.randomUUID().toString()
        libraryFile.update { lib ->
            lib.copy(tags = lib.tags + Tag(id, name.trim(), TAG_COLORS[lib.tags.size % TAG_COLORS.size]))
        }
        return libraryFile.value.tags.first { it.id == id }
    }

    fun setItemTags(itemId: String, tagIds: Set<String>) = libraryFile.update { lib ->
        val known = lib.tags.map { it.id }.toSet()
        lib.copy(items = lib.items.map { if (it.id == itemId) it.copy(tagIds = tagIds intersect known) else it })
    }

    /** Deletes a tag and detaches it from every item in the same write. Items stay starred. */
    fun deleteTag(tagId: String) = libraryFile.update { lib ->
        lib.copy(
            tags = lib.tags.filterNot { it.id == tagId },
            items = lib.items.map { if (tagId in it.tagIds) it.copy(tagIds = it.tagIds - tagId) else it },
        )
    }

    // --- playback: history with positions ---

    /**
     * Applies one playback event of the page's main video to history. [meta] is the player's
     * confirmed metadata for that video, when known.
     *
     * - A video not at the top of history moves there (any event: after a pre-roll ad the video can
     *   continue without a play event of its own). One already at the top is updated in place.
     * - Time, pause and end events store the position. Play does not: it can fire before the page
     *   has asked where to resume, and its position is still 0 then.
     * - Metadata changes nothing, for the same reason.
     * - Pause and end write at once; the rest is written by the debounce.
     */
    fun recordPlayback(kind: PlaybackKind, videoId: String, position: Double, duration: Double, meta: VideoMeta?) {
        if (kind == PlaybackKind.METADATA) return
        val storesPosition = kind != PlaybackKind.PLAY
        historyFile.updateInMemory { history ->
            val atTop = history.firstOrNull()?.takeIf { it.videoId == videoId }
            val previous = atTop ?: history.firstOrNull { it.videoId == videoId }
            var entry = (previous ?: HistoryEntry(videoId = videoId, watchedAt = now()))
            if (meta != null) entry = entry.copy(meta = meta)
            if (storesPosition) entry = entry.copy(position = position, duration = duration)
            if (atTop != null) {
                listOf(entry) + history.drop(1)
            } else {
                (listOf(entry.copy(watchedAt = now())) + history.filterNot { it.videoId == videoId }).take(HISTORY_CAP)
            }
        }
        if (kind == PlaybackKind.PAUSE || kind == PlaybackKind.ENDED) flush() else persistPlaybackSoon()
    }

    /** Fills in metadata for a history entry that was recorded before the player confirmed it. */
    fun annotateHistory(meta: VideoMeta) {
        val entry = history.value.firstOrNull { it.videoId == meta.videoId } ?: return
        if (entry.meta == meta) return
        historyFile.updateInMemory { history -> history.map { if (it.videoId == meta.videoId) it.copy(meta = meta) else it } }
        persistPlaybackSoon()
    }

    /** Where [videoId] should start, per [ResumeRules]. */
    fun resumePosition(videoId: String): Double? = ResumeRules.startAt(history.value.firstOrNull { it.videoId == videoId })

    /** Removes one video from history, and with it its resume position. */
    fun removeHistory(videoId: String) = historyFile.update { history -> history.filterNot { it.videoId == videoId } }

    /** Clears history and every resume position in one write. */
    fun clearHistory() = historyFile.update { emptyList() }

    /**
     * Puts back [entries] taken before a clear (Undo). Videos watched since keep their newer entries;
     * the result is newest first and capped like any history.
     */
    fun restoreHistory(entries: List<HistoryEntry>) = historyFile.update { current ->
        val kept = current.map { it.videoId }.toSet()
        (current + entries.filterNot { it.videoId in kept }).sortedByDescending { it.watchedAt }.take(HISTORY_CAP)
    }

    /** Writes pending playback state now. The returned job completes when every write is done. */
    fun flush(): Job {
        pendingFlush?.cancel()
        pendingFlush = null
        val writes = files.mapNotNull { it.save() }
        return scope.launch { writes.joinAll() }
    }

    private fun persistPlaybackSoon() {
        if (pendingFlush?.isActive == true) return
        pendingFlush = scope.launch {
            delay(PLAYBACK_FLUSH_MS)
            historyFile.save()
        }
    }

    // --- settings ---

    fun updateSettings(transform: (Settings) -> Settings) = settingsFile.update(transform)

    // --- channel cache ---

    fun cachedChannel(path: String): CachedChannel? = channels.value[path]

    fun cacheChannel(path: String, info: ChannelInfo) = channelsFile.update { it + (path to CachedChannel(info, now())) }

    companion object {
        const val HISTORY_CAP = 2000
        const val PLAYBACK_FLUSH_MS = 10_000L

        /** Material tonal reds, blues, greens and ambers; tags take them in turn. */
        private val TAG_COLORS = listOf(
            0xFFE57373, 0xFF64B5F6, 0xFF81C784, 0xFFFFB74D, 0xFFBA68C8, 0xFF4DB6AC, 0xFFF06292, 0xFFA1887F,
        ).map { it.toInt() }
    }
}
