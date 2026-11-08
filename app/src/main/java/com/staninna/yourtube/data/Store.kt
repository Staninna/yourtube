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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * The app's first-party state: library, tags, history (which holds resume positions), settings, the
 * channel and feed caches, and the player choices identities hand on. Identity changes never touch
 * it. Every mutation is one atomic `update {}` on its file.
 *
 * Library, settings, channel and feed changes are written right away. History changes with every playback
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
    private val feedsFile = StoreFile(
        dir, "feeds.json", MapSerializer(String.serializer(), ListSerializer(FeedVideo.serializer())), emptyMap(), scope,
    )
    private val playerFile = StoreFile(dir, "player.json", PlayerPrefs.serializer(), PlayerPrefs(), scope)
    private val notifyFile = StoreFile(dir, "notify.json", NotifyState.serializer(), NotifyState(), scope)
    private val files = listOf(libraryFile, historyFile, settingsFile, channelsFile, feedsFile, playerFile, notifyFile)

    val library: StateFlow<Library> = libraryFile.state

    /** Watched videos, most recent first, each with its playback position. */
    val history: StateFlow<List<HistoryEntry>> = historyFile.state
    val settings: StateFlow<Settings> = settingsFile.state

    /** Resolved channels keyed by channel page path (see `PageIdentity.Channel.path`). */
    val channels: StateFlow<Map<String, CachedChannel>> = channelsFile.state

    /**
     * Each subscribed channel's last uploads by channel id: a cache, so a cold start shows Feeds at
     * once while it refreshes. Backups leave it out.
     */
    val feeds: StateFlow<Map<String, List<FeedVideo>>> = feedsFile.state

    /** The last identity's player choices, for the next identity to start with. The library export leaves it out. */
    val playerPrefs: StateFlow<PlayerPrefs> = playerFile.state

    /** What the new-video check has seen per channel. Backups leave it out. */
    val notify: StateFlow<NotifyState> = notifyFile.state

    /** Files that could not be read at startup; each was kept as `<name>.corrupt`. */
    val corruptFiles: Set<String> = files.filter { it.loadResult == LoadResult.CORRUPT }.map { it.name }.toSet()

    // Only the user's own data earns a notice; the caches and the new-video state refill themselves.
    private val _corruptNotice = MutableStateFlow(
        listOf(libraryFile, historyFile, settingsFile).map { it.name }.filter { it in corruptFiles }.toSet().takeIf { it.isNotEmpty() },
    )

    /**
     * The user-data files among [corruptFiles], until the user has answered the notice about them
     * (dismissed it or exported). It outlives any one screen; the next process finds the files moved
     * aside, so it shows once per damage.
     */
    val corruptNotice: StateFlow<Set<String>?> = _corruptNotice.asStateFlow()

    @Volatile private var pendingFlush: Job? = null

    fun dismissCorruptNotice() {
        _corruptNotice.value = null
    }

    // --- library ---

    /** Stars [item], newest first and stamped now. An item already starred stays as it is. */
    fun star(item: StarredItem) = libraryFile.update { lib ->
        if (lib.items.any { it.id == item.id }) lib else lib.copy(items = listOf(item.copy(addedAt = now())) + lib.items)
    }

    /** Takes [itemIds] out of the library in one write. */
    fun unstar(itemIds: Set<String>) = libraryFile.update { lib -> lib.copy(items = lib.items.filterNot { it.id in itemIds }) }

    /**
     * Puts back [items] as they were before an unstar (Undo), in one write: their dates and their tags
     * that still exist. An item starred again since keeps the newer star.
     */
    fun restoreStars(items: List<StarredItem>) = libraryFile.update { lib ->
        val present = lib.items.mapTo(HashSet()) { it.id }
        val known = lib.tags.mapTo(HashSet()) { it.id }
        val back = items.filter { it.id !in present }.distinctBy { it.id }.map { it.copy(tagIds = it.tagIds intersect known) }
        if (back.isEmpty()) lib else lib.copy(items = (lib.items + back).sortedByDescending { it.addedAt })
    }

    /**
     * Creates a tag named [name] with the next palette color, and attaches it to the items [attachTo] in
     * the same write. A name already in the pool (ignoring case) reuses that tag. Null for a blank name.
     */
    fun createTag(name: String, attachTo: Set<String> = emptySet()): Tag? {
        val normalized = TagNames.normalize(name) ?: return null
        val newId = UUID.randomUUID().toString()
        var tag: Tag? = null
        libraryFile.update { lib ->
            // update {} may run this more than once; the last run is the one applied.
            val t = TagNames.clash(normalized, lib.tags) ?: Tag(newId, normalized, TagPalette.next(lib.tags.size))
            tag = t
            lib.copy(
                tags = if (t.id == newId) lib.tags + t else lib.tags,
                items = lib.items.map { if (it.id in attachTo) it.copy(tagIds = it.tagIds + t.id) else it },
            )
        }
        return tag
    }

    /** Attaches [tagId] to every one of [itemIds], or detaches it, in one write. An unknown tag is never attached. */
    fun setTag(itemIds: Set<String>, tagId: String, attached: Boolean) = libraryFile.update { lib ->
        if (attached && lib.tags.none { it.id == tagId }) return@update lib
        lib.copy(
            items = lib.items.map {
                if (it.id !in itemIds) it else it.copy(tagIds = if (attached) it.tagIds + tagId else it.tagIds - tagId)
            },
        )
    }

    /** Moves [tagId] to [toIndex] in the pool, whose order every list of tags follows. */
    fun moveTag(tagId: String, toIndex: Int) = libraryFile.update { lib -> lib.copy(tags = TagOrder.move(lib.tags, tagId, toIndex)) }

    /** Renames a tag. A blank name, or one another tag has, changes nothing. */
    fun renameTag(tagId: String, name: String) = libraryFile.update { lib ->
        val normalized = TagNames.normalize(name)
        if (normalized == null || TagNames.clash(normalized, lib.tags, exceptId = tagId) != null) return@update lib
        lib.copy(tags = lib.tags.map { if (it.id == tagId) it.copy(name = normalized) else it })
    }

    fun recolorTag(tagId: String, color: Int) = libraryFile.update { lib ->
        lib.copy(tags = lib.tags.map { if (it.id == tagId) it.copy(color = color) else it })
    }

    /** Deletes a tag and detaches it from every item in the same write. Items stay starred. */
    fun deleteTag(tagId: String) = libraryFile.update { lib ->
        lib.copy(
            tags = lib.tags.filterNot { it.id == tagId },
            items = lib.items.map { if (tagId in it.tagIds) it.copy(tagIds = it.tagIds - tagId) else it },
        )
    }

    /** Gives the starred channel [channelId] its avatar, unless it already has one. */
    fun setChannelAvatar(channelId: String, avatar: String) = libraryFile.update { lib ->
        lib.copy(
            items = lib.items.map {
                if (it.type == ItemType.CHANNEL && it.id == channelId && it.thumbnail == null) it.copy(thumbnail = avatar) else it
            },
        )
    }

    // --- backup ---

    /** Everything first-party as it stands now, including playback not yet written. */
    fun backup(): Backup = Backup(exportedAt = now(), library = library.value, history = history.value, settings = settings.value)

    /** Merges [backup] in per [BackupMerge]: one write per file, each atomic. */
    fun import(backup: Backup): ImportReport {
        // update {} may run a transform more than once; the last run is the one applied.
        var lib: BackupMerge.LibraryResult? = null
        libraryFile.update { current -> BackupMerge.library(current, backup.library).also { lib = it }.library }
        var historyAdded = 0
        historyFile.update { current ->
            BackupMerge.history(current, backup.history, HISTORY_CAP).also { historyAdded = it.added }.history
        }
        settingsFile.update { backup.settings }
        val merged = checkNotNull(lib)
        return ImportReport(merged.itemsAdded, merged.itemsUpdated, merged.tagsAdded, historyAdded)
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
     * Puts back [entries] taken before a clear or a removal (Undo), positions included. Videos watched
     * since keep their newer entries; the result is newest first and capped like any history.
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

    fun setPlayerPrefs(prefs: PlayerPrefs) {
        if (prefs != playerFile.value) playerFile.update { prefs }
    }

    // --- channel cache ---

    fun cachedChannel(path: String): CachedChannel? = channels.value[path]

    fun cacheChannel(path: String, info: ChannelInfo) = channelsFile.update { it + (path to CachedChannel(info, now())) }

    // --- feed cache ---

    /** Replaces the feed cache with [feeds], at most [FEED_CACHE_PER_CHANNEL] videos per channel. */
    fun cacheFeeds(feeds: Map<String, List<FeedVideo>>) = updateFeeds { feeds }

    /**
     * Changes the feed cache in one atomic step, so Feeds and the background check, which each write
     * only their own channels, never undo each other. [transform] may run more than once. Lists are
     * capped at [FEED_CACHE_PER_CHANNEL]; an unchanged cache is not written.
     */
    fun updateFeeds(transform: (Map<String, List<FeedVideo>>) -> Map<String, List<FeedVideo>>) {
        feedsFile.updateIfChanged { cache -> transform(cache).mapValues { (_, videos) -> videos.take(FEED_CACHE_PER_CHANNEL) } }
    }

    // --- new-video notifications ---

    /**
     * Turns the new-video check on or off. Turning it on starts its state over: the next check seeds
     * every channel, so the backlog never notifies.
     */
    fun setNewVideoNotifications(on: Boolean) {
        if (on) restartNotify()
        settingsFile.update { it.copy(newVideoNotifications = on) }
    }

    /** Forgets what the new-video check has seen, so its next run seeds every channel again. */
    fun restartNotify() = notifyFile.update { NotifyState(since = now()) }

    /**
     * Stores [next], a check's result computed from [expected], unless the state moved on meanwhile
     * (turned on again, or restarted). Returns whether it was stored.
     */
    fun replaceNotify(expected: NotifyState, next: NotifyState): Boolean {
        var applied = false
        notifyFile.update { current -> (if (current == expected) next else current).also { applied = current == expected } }
        return applied
    }

    /** Waits until every write queued so far is on disk, without touching the playback debounce. */
    suspend fun awaitWrites() = scope.launch {}.join()

    companion object {
        const val HISTORY_CAP = 2000

        /** A channel's videos page lists 30 uploads, RSS 15; the cache keeps that many. */
        const val FEED_CACHE_PER_CHANNEL = 30
        const val PLAYBACK_FLUSH_MS = 10_000L
    }
}
