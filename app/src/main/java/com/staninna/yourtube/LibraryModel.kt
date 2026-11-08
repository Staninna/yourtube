package com.staninna.yourtube

import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.LibraryFilter
import com.staninna.yourtube.data.LibraryView
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
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
 * segment, chips and search, and avatar backfill. [starTarget] is what Watch would star; [emit]
 * sends snackbar events. Everything runs on [scope], one coroutine at a time (the main thread).
 */
class LibraryModel(
    private val store: Store,
    private val channels: ChannelResolver,
    private val scope: CoroutineScope,
    private val starTarget: StateFlow<StarredItem?>,
    private val emit: (UiEvent) -> Unit,
) {
    /** Every starred item and the tag pool. */
    val state: StateFlow<Library> = store.library

    /** Whether what Watch shows is starred. */
    val starred: StateFlow<Boolean> = combine(starTarget, store.library, ::isStarred)
        .stateIn(scope, SharingStarted.Eagerly, isStarred(starTarget.value, store.library.value))

    // --- Library's view: segment, chips per segment, search; in memory only ---

    private val segment = MutableStateFlow(ItemType.CHANNEL)
    private val chipSelection = MutableStateFlow<Map<ItemType, Set<String>>>(emptyMap())
    private val query = MutableStateFlow("")

    val view: StateFlow<LibraryView> = combine(store.library, segment, chipSelection, query) { lib, seg, chips, q ->
        LibraryFilter.view(lib, seg, chips[seg].orEmpty(), q)
    }.stateIn(scope, SharingStarted.Eagerly, LibraryFilter.view(store.library.value, segment.value, emptySet(), ""))

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
    }

    /**
     * The Watch star. An unstarred page is starred at once and its tag sheet opens; dismissing the sheet
     * keeps the star. A starred one is unstarred without a sheet, with Undo.
     */
    fun onStarClick() {
        val target = starTarget.value ?: return
        if (starred.value) {
            unstar(target.id)
        } else {
            store.star(target)
            openTagSheet(target.id)
        }
    }

    /** Takes [itemId] out of the library, offering Undo. */
    fun unstar(itemId: String) {
        val item = state.value.items.firstOrNull { it.id == itemId } ?: return
        store.unstar(itemId)
        emit(UiEvent.Unstarred(item))
    }

    fun undoUnstar(event: UiEvent.Unstarred) = store.restoreStar(event.item)

    fun openTagSheet(itemId: String) {
        unsubscribed = null
        sheetItem.value = itemId
    }

    fun closeTagSheet() {
        unsubscribed = null
        sheetItem.value = null
    }

    fun setItemTag(itemId: String, tagId: String, attached: Boolean) = store.setItemTag(itemId, tagId, attached)

    /** Creates a tag (or reuses one with that name) and attaches it to [itemId]. */
    fun createTag(itemId: String, name: String) {
        store.createTag(name, attachTo = itemId)
    }

    /**
     * The tag sheet's Subscribe switch for a video's channel. No Undo, the switch flips back: switching
     * it off and on again within one sheet puts the channel back as it was, tags and date included.
     */
    fun setSubscribed(channel: StarredItem, subscribed: Boolean) {
        if (subscribed) {
            val previous = unsubscribed?.takeIf { it.id == channel.id }
            if (previous != null) store.restoreStar(previous) else store.star(channel)
            unsubscribed = null
        } else {
            unsubscribed = state.value.items.firstOrNull { it.id == channel.id }
            store.unstar(channel.id)
        }
    }

    fun renameTag(tagId: String, name: String) = store.renameTag(tagId, name)
    fun recolorTag(tagId: String, color: Int) = store.recolorTag(tagId, color)
    fun deleteTag(tagId: String) = store.deleteTag(tagId)

    fun selectSegment(type: ItemType) {
        segment.value = type
    }

    /** Flips a chip in the current segment; each segment keeps its own selection. */
    fun toggleTagFilter(tagId: String) {
        val seg = segment.value
        chipSelection.update { it + (seg to LibraryFilter.toggleChip(state.value, seg, it[seg].orEmpty(), tagId)) }
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
