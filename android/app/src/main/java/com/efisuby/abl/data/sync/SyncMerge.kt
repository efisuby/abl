package com.efisuby.abl.data.sync

import com.efisuby.abl.data.settings.SyncableSettings

/**
 * Merging happens at read time (design D8): each device writes only its own files, so there is
 * no write conflict, and the rules below make the outcome the same on every device.
 */
object SyncMerge {

    /**
     * The freshest position per book. On equal [ProgressEntry.updatedAt] the larger position wins
     * for a book still being listened to; a finished book (or any other tie) falls back to the
     * larger device id, so every device picks the same record.
     */
    fun progress(vararg sources: Collection<ProgressEntry>): Map<String, ProgressEntry> {
        val best = mutableMapOf<String, ProgressEntry>()
        for (entry in sources.asSequence().flatten()) {
            val current = best[entry.bookId]
            if (current == null || entry.beats(current)) best[entry.bookId] = entry
        }
        return best
    }

    private fun ProgressEntry.beats(other: ProgressEntry): Boolean = when {
        updatedAt != other.updatedAt -> updatedAt > other.updatedAt
        !finished && !other.finished && positionMs != other.positionMs -> positionMs > other.positionMs
        else -> deviceId > other.deviceId
    }

    /**
     * Union by id; of two copies the later modification wins, and on a tie the tombstone does,
     * so a deleted bookmark is never brought back by an old copy.
     */
    fun bookmarks(vararg sources: Collection<BookmarkEntry>): Map<String, BookmarkEntry> {
        val best = mutableMapOf<String, BookmarkEntry>()
        for (entry in sources.asSequence().flatten()) {
            val current = best[entry.id]
            if (current == null || entry.updatedAt > current.updatedAt ||
                (entry.updatedAt == current.updatedAt && entry.deleted && !current.deleted)
            ) {
                best[entry.id] = entry
            }
        }
        return best
    }

    /** Last write wins per key, ties by device id; keys that are not syncable are dropped. */
    fun settings(vararg sources: SettingsFile): Map<String, SettingEntry> {
        val best = mutableMapOf<String, Pair<SettingEntry, String>>()
        for (file in sources) {
            for ((key, entry) in file.values) {
                if (key !in SyncableSettings.keys) continue
                val current = best[key]
                if (current == null || entry.updatedAt > current.first.updatedAt ||
                    (entry.updatedAt == current.first.updatedAt && file.deviceId > current.second)
                ) {
                    best[key] = entry to file.deviceId
                }
            }
        }
        return best.mapValues { it.value.first }
    }
}
