package com.staninna.yourtube

import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.LibraryFilter
import com.staninna.yourtube.data.LibraryView
import com.staninna.yourtube.data.LibrarySort
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.TagFilter
import com.staninna.yourtube.data.TagFiltering
import com.staninna.yourtube.data.TagMatch
import com.staninna.yourtube.data.TagSheet
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.page.PageIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The library as the screens use it: starring from Watch, the tag sheet, tag operations, Library's
 * segment, tag filter, sort, search and [selection], and avatar backfill. [starTarget] is what Watch
 * would star; [emit] sends snackbar events. Everything runs on [scope], one coroutine at a time (the
 * main thread).
 */
class LibraryModel(
    private val store: Store,
    private val channels: ChannelResolver,
    private val scope: CoroutineScope,
    private val starTarget: StateFlow<StarredItem?>,
    private val emit: (UiEvent) -> Unit,
    /** Each subscribed channel's newest upload time, by channel id, for the Latest upload sort. */
    private val latestUploads: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap()),
) : TagFiltering {
    /** Every starred item and the tag pool. */
    val state: StateFlow<Library> = store.library

    /** Whether what Watch shows is starred. */
    val starred: StateFlow<Boolean> = combine(starTarget, store.library, ::isStarred)
        .stateIn(scope, SharingStarted.Eagerly, isStarred(starTarget.value, store.library.value))

    // --- Library's view: segment, a tag filter per segment and search in memory; the sort is a setting ---

    private val segment = MutableStateFlow(ItemType.CHANNEL)
    private val filters = MutableStateFlow<Map<ItemType, TagFilter>>(emptyMap())
    private val query = MutableStateFlow("")

    private val order = combine(store.settings, latestUploads) { settings, uploads -> settings.librarySort to uploads }

    val view: StateFlow<LibraryView> = combine(store.library, segment, filters, query, order) { lib, seg, f, q, (sort, uploads) ->
        LibraryFilter.view(lib, seg, f[seg] ?: TagFilter(), q, sort, latestUploads = uploads)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        LibraryFilter.view(
            store.library.value, segment.value, TagFilter(), "", store.settings.value.librarySort,
            latestUploads = latestUploads.value,
        ),
    )

    /** Library's selection mode, over the rows [view] shows. */
    val selection = LibrarySelection(store, scope, view, openTagSheet = ::openTagSheet, emit = emit)

    /** The search text, kept apart from [view] so a field can start from it. */
    val queryText: StateFlow<String> = query.asStateFlow()

    // --- the tag sheet ---

    private val sheetItem = MutableStateFlow<String?>(null)

    /** The open tag sheet; it closes by itself when its item leaves the library. */
    val tagSheet: StateFlow<TagSheet?> = combine(sheetItem, store.library) { id, lib -> id?.let { TagSheet.of(lib, it) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The channel the open sheet unsubscribed, kept so switching it back on restores its tags and date. */
    private var unsubscribed: StarredItem? = null

    /** Channels whose avatar was looked up in this process, found or not. Touched only by the collector. */
    private val avatarLookups = HashSet<String>()

    init {
        // Starred channels without an avatar get one from the resolver, which caches and bounds fetches.
        scope.launch {
            store.library.collect { lib ->
                LibraryFilter.missingAvatars(lib).filter(avatarLookups::add).forEach { id -> launch { backfillAvatar(id) } }
            }
        }
        // A tag or Untagged that no item in a segment offers any more leaves its stored filter for good,
        // so it does not filter again when an item brings it back later.
        scope.launch {
            store.library.collect { lib ->
                filters.update { stored -> stored.mapValues { (seg, filter) -> LibraryFilter.inEffect(lib, seg, filter) } }
            }
        }
    }

    /**
     * The Watch star. An unstarred page is starred at once; either way its tag sheet opens, to edit its
     * tags or take it out again. Dismissing the sheet keeps the star.
     */
    fun onStarClick() {
        val target = starTarget.value ?: return
        if (!starred.value) store.star(target)
        openTagSheet(target.id)
    }

    /** Takes [itemId] out of the library, offering Undo. */
    fun unstar(itemId: String) {
        val item = state.value.items.firstOrNull { it.id == itemId } ?: return
        store.unstar(setOf(itemId))
        emit(UiEvent.Unstarred(listOf(item)))
    }

    fun undoUnstar(event: UiEvent.Unstarred) = store.restoreStars(event.items)

    /** The tag sheet's Remove from library: the sheet closes and the item leaves, with Undo. */
    fun removeFromLibrary(itemId: String) {
        closeTagSheet()
        unstar(itemId)
    }

    fun openTagSheet(itemId: String) {
        unsubscribed = null
        sheetItem.value = itemId
    }

    fun closeTagSheet() {
        unsubscribed = null
        sheetItem.value = null
    }

    fun setItemTag(itemId: String, tagId: String, attached: Boolean) = store.setTag(setOf(itemId), tagId, attached)

    /** Creates a tag (or reuses one with that name) and attaches it to [itemId]. */
    fun createTag(itemId: String, name: String) {
        store.createTag(name, attachTo = setOf(itemId))
    }

    /**
     * The tag sheet's Subscribe switch for a video's channel. No Undo, the switch flips back: switching
     * it off and on again within one sheet puts the channel back as it was, tags and date included.
     */
    fun setSubscribed(channel: StarredItem, subscribed: Boolean) {
        if (subscribed) {
            val previous = unsubscribed?.takeIf { it.id == channel.id }
            if (previous != null) store.restoreStars(listOf(previous)) else store.star(channel)
            unsubscribed = null
        } else {
            unsubscribed = state.value.items.firstOrNull { it.id == channel.id }
            store.unstar(setOf(channel.id))
        }
    }

    fun renameTag(tagId: String, name: String) = store.renameTag(tagId, name)
    fun recolorTag(tagId: String, color: Int) = store.recolorTag(tagId, color)
    fun deleteTag(tagId: String) = store.deleteTag(tagId)
    fun moveTag(tagId: String, toIndex: Int) = store.moveTag(tagId, toIndex)

    /** Another segment ends selection mode: its rows are not the selected ones. */
    fun selectSegment(type: ItemType) {
        if (type != segment.value) selection.clear()
        segment.value = type
    }

    override fun toggleTagFilter(tagId: String) = editFilter { it.toggle(tagId) }
    override fun toggleUntaggedFilter() = editFilter { it.toggleUntagged() }
    override fun setTagMatch(match: TagMatch) = editFilter { it.copy(match = match) }
    override fun clearTagFilter() = editFilter { it.cleared() }

    /** Stored, so the order survives restarts; every segment shares it (Latest upload applies in Subs only). */
    fun setSort(sort: LibrarySort) = store.updateSettings { it.copy(librarySort = sort) }

    /** Changes the current segment's filter, starting from the one in effect; each segment keeps its own. */
    private fun editFilter(change: (TagFilter) -> TagFilter) {
        val seg = segment.value
        filters.update { it + (seg to change(LibraryFilter.inEffect(state.value, seg, it[seg] ?: TagFilter()))) }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    private suspend fun backfillAvatar(channelId: String) {
        channels.resolve(PageIdentity.channelPath(channelId))?.avatar?.let { store.setChannelAvatar(channelId, it) }
    }

    private companion object {
        fun isStarred(target: StarredItem?, library: Library): Boolean = target != null && library.items.any { it.id == target.id }
    }
}
