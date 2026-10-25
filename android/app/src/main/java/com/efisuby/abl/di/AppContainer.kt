package com.efisuby.abl.di

import android.content.Context
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.importer.BookImporter
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.CascadingChapterReader
import com.efisuby.abl.data.importer.Media3ChapterReader
import com.efisuby.abl.data.importer.MediaMetadataTagReader
import com.efisuby.abl.data.importer.Mp4ChapterTrackReader
import com.efisuby.abl.data.importer.ImportSources
import com.efisuby.abl.data.importer.SafImportSources
import com.efisuby.abl.data.repository.DeviceIdProvider
import com.efisuby.abl.data.repository.RoomBookmarkRepository
import com.efisuby.abl.data.repository.RoomLibraryRepository
import com.efisuby.abl.data.repository.RoomProgressRepository
import com.efisuby.abl.data.settings.SettingsRepository
import com.efisuby.abl.data.settings.settingsDataStore
import com.efisuby.abl.domain.repository.BookmarkRepository
import com.efisuby.abl.domain.repository.LibraryRepository
import com.efisuby.abl.domain.repository.ProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manual dependency container. Created lazily by [com.efisuby.abl.AblApplication] so that the
 * first screen decides when the database is opened.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** For writes that must finish even if the screen or service that started them is gone. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AblDatabase by lazy { AblDatabase.create(appContext) }

    val deviceIdProvider: DeviceIdProvider = DeviceIdProvider(appContext)

    val libraryRepository: LibraryRepository by lazy { RoomLibraryRepository(database) }

    val progressRepository: ProgressRepository by lazy {
        RoomProgressRepository(database, deviceIdProvider)
    }

    val bookmarkRepository: BookmarkRepository by lazy { RoomBookmarkRepository(database) }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext.settingsDataStore)
    }

    val bookStorage: BookStorage by lazy { BookStorage(File(appContext.filesDir, "books")) }

    val bookImporter: BookImporter by lazy {
        BookImporter(
            storage = bookStorage,
            library = libraryRepository,
            tagReader = MediaMetadataTagReader(),
            chapterReader = CascadingChapterReader(
                listOf(Media3ChapterReader(appContext), Mp4ChapterTrackReader()),
            ),
        )
    }

    /** A `var` so instrumented tests can serve fixtures through a mock content resolver. */
    var importSources: ImportSources = SafImportSources(appContext.contentResolver)
}
