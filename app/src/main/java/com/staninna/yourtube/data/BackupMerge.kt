package com.staninna.yourtube.data

/** What an import changed, for the report the user sees. */
data class ImportReport(
    val itemsAdded: Int,
    /** Items already in the library that gained tags from the file. */
    val itemsUpdated: Int,
    val tagsAdded: Int,
    val historyAdded: Int,
    /** Entries the file had but broke an invariant (see `BackupCodec`). */
    val skipped: Int = 0,
)

/**
 * Merges a backup into the current store. Nothing already here is removed or renamed:
 *
 * - Tags match by id, then by name ignoring case; an unmatched tag is added as it is.
 * - Items match by id. A new item is added with its tags translated through the tag matches; an
 *   existing one keeps its fields, gains the file's tags, and takes the file's thumbnail only when it
 *   has none.
 * - History matches by video id; of two entries the more recently watched wins, so a position is never
 *   rolled back.
 * - Settings are the one record without ids; the file's replace the current ones.
 */
object BackupMerge {

    data class LibraryResult(val library: Library, val itemsAdded: Int, val itemsUpdated: Int, val tagsAdded: Int)

    data class HistoryResult(val history: List<HistoryEntry>, val added: Int)

    fun library(current: Library, incoming: Library): LibraryResult {
        val tags = current.tags.toMutableList()
        // The file's tag id -> the id it has in the merged pool.
        val tagIds = HashMap<String, String>()
        for (tag in incoming.tags) {
            val match = tags.firstOrNull { it.id == tag.id } ?: tags.firstOrNull { it.name.equals(tag.name, ignoreCase = true) }
            if (match == null) tags += tag
            tagIds[tag.id] = match?.id ?: tag.id
        }

        val byId = current.items.associateBy { it.id }.toMutableMap()
        var added = 0
        var updated = 0
        for (item in incoming.items) {
            val tagged = item.tagIds.mapNotNull(tagIds::get).toSet()
            val existing = byId[item.id]
            if (existing == null) {
                byId[item.id] = item.copy(tagIds = tagged)
                added++
            } else {
                val merged = existing.copy(tagIds = existing.tagIds + tagged, thumbnail = existing.thumbnail ?: item.thumbnail)
                if (merged.tagIds != existing.tagIds) updated++
                byId[item.id] = merged
            }
        }
        val items = byId.values.sortedByDescending { it.addedAt }
        return LibraryResult(Library(items, tags), added, updated, tags.size - current.tags.size)
    }

    fun history(current: List<HistoryEntry>, incoming: List<HistoryEntry>, cap: Int): HistoryResult {
        val currentIds = current.map { it.videoId }.toSet()
        val byId = current.associateBy { it.videoId }.toMutableMap()
        for (entry in incoming) {
            val existing = byId[entry.videoId]
            if (existing == null || entry.watchedAt > existing.watchedAt) byId[entry.videoId] = entry
        }
        val merged = byId.values.sortedByDescending { it.watchedAt }.take(cap)
        // Counted after the cap: an old entry that fell off was not added.
        return HistoryResult(merged, merged.count { it.videoId !in currentIds })
    }
}
