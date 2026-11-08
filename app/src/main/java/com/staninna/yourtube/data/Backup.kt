package com.staninna.yourtube.data

import com.staninna.yourtube.YouTube
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate

/**
 * Everything first-party in one file: library, tags, history (with resume positions) and settings.
 * The channel cache is left out; it refills itself.
 */
@Serializable
data class Backup(
    val format: String = BackupCodec.FORMAT,
    val schemaVersion: Int = BackupCodec.SCHEMA_VERSION,
    val exportedAt: Long = 0,
    val library: Library = Library(),
    val history: List<HistoryEntry> = emptyList(),
    val settings: Settings = Settings(),
)

/** Why a file was not imported. */
enum class BackupProblem {
    /** The file could not be read: no access, an I/O error, or larger than [BackupCodec.MAX_BYTES]. */
    UNREADABLE,

    /** Not JSON, or JSON without the YourTube backup marker. */
    NOT_A_BACKUP,

    /** Written by a newer YourTube with a schema this version does not know. */
    NEWER_VERSION,

    /** A YourTube backup whose structure does not decode. */
    DAMAGED,
}

/** The outcome of reading a backup file. */
sealed interface BackupRead {
    /** [skipped] counts entries dropped because they broke an invariant (blank or malformed ids). */
    data class Valid(val backup: Backup, val skipped: Int) : BackupRead

    data class Invalid(val problem: BackupProblem) : BackupRead
}

/** Writes and reads backup files. Reading validates everything and never throws on bad input. */
object BackupCodec {
    const val FORMAT = "yourtube-backup"
    const val SCHEMA_VERSION = 1

    /** Larger files are refused before parsing; a full store is well under 2 MB. */
    const val MAX_BYTES = 16 * 1024 * 1024

    const val MIME_TYPE = "application/json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(backup: Backup): String = json.encodeToString(Backup.serializer(), backup)

    /** A dated file name for the system's save dialog. */
    fun fileName(date: LocalDate): String = "yourtube-backup-$date.json"

    /** Reads [text] as a backup. Dates after [now] (a clock that ran ahead) are pulled back to it. */
    fun decode(text: String, now: Long): BackupRead {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return BackupRead.Invalid(BackupProblem.NOT_A_BACKUP)
        if ((root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content != FORMAT) {
            return BackupRead.Invalid(BackupProblem.NOT_A_BACKUP)
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        when {
            version == null || version < 1 -> return BackupRead.Invalid(BackupProblem.DAMAGED)
            version > SCHEMA_VERSION -> return BackupRead.Invalid(BackupProblem.NEWER_VERSION)
        }
        val backup = try {
            json.decodeFromJsonElement(Backup.serializer(), root)
        } catch (_: SerializationException) {
            return BackupRead.Invalid(BackupProblem.DAMAGED)
        } catch (_: IllegalArgumentException) {
            return BackupRead.Invalid(BackupProblem.DAMAGED)
        }
        return sanitize(backup, now)
    }

    /**
     * Drops entries that break the store's invariants and repairs what can be derived. Ids must look
     * like YouTube ids, since links are rebuilt from them; the first of a duplicated id wins; tag ids
     * on items must name a kept tag; tag colors are opaque; positions are clamped to the duration and
     * dates to [now]. Strict JSON has no NaN or infinity, so numbers are finite already.
     */
    private fun sanitize(backup: Backup, now: Long): BackupRead.Valid {
        var skipped = 0
        val tags = backup.library.tags
            .filter { it.id.isNotBlank() }
            .mapNotNull { tag -> TagNames.normalize(tag.name)?.let { tag.copy(name = it, color = tag.color or OPAQUE) } }
            .distinctBy { it.id }
        skipped += backup.library.tags.size - tags.size
        val tagIds = tags.map { it.id }.toSet()

        val items = backup.library.items.mapNotNull { item ->
            if (!idPattern(item.type).matches(item.id) || item.title.isBlank()) return@mapNotNull null
            item.copy(
                url = canonicalUrl(item.type, item.id),
                thumbnail = item.thumbnail?.takeIf { it.startsWith("https://") },
                // A channel's own id is its UC id, which builds its feed.
                channelId = if (item.type == ItemType.CHANNEL) item.id else item.channelId?.takeIf(YouTube.CHANNEL_ID::matches),
                tagIds = item.tagIds intersect tagIds,
                addedAt = item.addedAt.coerceAtMost(now),
            )
        }.distinctBy { it.id }
        skipped += backup.library.items.size - items.size

        val history = backup.history.mapNotNull { entry ->
            if (!YouTube.VIDEO_ID.matches(entry.videoId)) return@mapNotNull null
            val duration = entry.duration.coerceAtLeast(0.0)
            entry.copy(
                meta = entry.meta?.takeIf { it.videoId == entry.videoId },
                duration = duration,
                position = entry.position.coerceIn(0.0, duration),
                watchedAt = entry.watchedAt.coerceAtMost(now),
            )
        }.distinctBy { it.videoId }
        skipped += backup.history.size - history.size

        // Settings replace the current ones whole (BackupMerge), so the server must pass the same check
        // the Server dialog makes; one that does not falls back to the public server.
        val sponsor = backup.settings.sponsorBlock
        val server = (ServerInput.parse(sponsor.server) as? ServerInput.Valid)?.url ?: SponsorBlockSettings.DEFAULT_SERVER
        val settings = backup.settings.copy(sponsorBlock = sponsor.copy(server = server))

        return BackupRead.Valid(backup.copy(library = Library(items, tags), history = history, settings = settings), skipped)
    }

    private fun canonicalUrl(type: ItemType, id: String): String = when (type) {
        ItemType.CHANNEL -> YouTube.channelUrl(id)
        ItemType.VIDEO -> YouTube.watchUrl(id)
        ItemType.PLAYLIST -> YouTube.playlistUrl(id)
    }

    private fun idPattern(type: ItemType): Regex = when (type) {
        ItemType.CHANNEL -> YouTube.CHANNEL_ID
        ItemType.VIDEO -> YouTube.VIDEO_ID
        ItemType.PLAYLIST -> YouTube.PLAYLIST_ID
    }

    private const val OPAQUE = 0xFF000000.toInt()
}
