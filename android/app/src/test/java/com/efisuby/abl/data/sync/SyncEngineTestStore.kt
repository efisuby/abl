package com.efisuby.abl.data.sync

/** In-memory [SyncStore] standing in for one device. */
class SyncEngineTestStore : SyncStore {
    val progress = mutableMapOf<String, ProgressEntry>()
    val bookmarks = mutableMapOf<String, BookmarkEntry>()
    val settings = mutableMapOf<String, SettingEntry>()

    override suspend fun progress() = progress.values.toList()
    override suspend fun applyProgress(entries: Collection<ProgressEntry>) {
        entries.forEach { progress[it.bookId] = it }
    }
    override suspend fun bookmarks() = bookmarks.values.toList()
    override suspend fun applyBookmarks(entries: Collection<BookmarkEntry>) {
        entries.forEach { bookmarks[it.id] = it }
    }
    override suspend fun settings(): Map<String, SettingEntry> = settings.toMap()
    override suspend fun applySettings(values: Map<String, SettingEntry>) {
        settings.putAll(values)
    }

    val library = mutableMapOf<String, LibraryEntry>()
    val covers = mutableMapOf<String, ByteArray>()
    override suspend fun books() = library.values.map { it.copy(hasCover = it.bookId in covers) }
    override suspend fun cover(bookId: String) = covers[bookId]
    override suspend fun applyBook(entry: LibraryEntry, cover: ByteArray?) {
        library[entry.bookId] = entry
        if (cover != null) covers[entry.bookId] = cover
    }
    
    var restored = false
    val restorable = mutableSetOf<String>()
    override suspend fun libraryRestored() = restored
    override suspend fun markLibraryRestored() {
    restored = true
    }
    override suspend fun restoreBook(entry: LibraryEntry, cover: ByteArray?): Boolean {
    if (entry.bookId !in restorable) return false
    library[entry.bookId] = entry
    if (cover != null) covers[entry.bookId] = cover
    return true
    }
}
