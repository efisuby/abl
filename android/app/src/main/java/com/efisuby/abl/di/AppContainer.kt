package com.efisuby.abl.di

import android.content.Context
import com.efisuby.abl.data.auth.AuthRepository
import com.efisuby.abl.data.auth.AuthState
import com.efisuby.abl.data.auth.TokenStore
import com.efisuby.abl.data.sync.DiskCloudFiles
import com.efisuby.abl.data.sync.RoomSyncStore
import com.efisuby.abl.data.sync.SyncEngine
import com.efisuby.abl.data.sync.SyncRunner
import com.efisuby.abl.data.sync.SyncScheduler
import com.efisuby.abl.data.sync.WorkManagerSyncScheduler
import com.efisuby.abl.data.auth.keystoreCipher
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.disk.BookDownloader
import com.efisuby.abl.data.disk.BookUploader
import com.efisuby.abl.data.disk.UploadController
import com.efisuby.abl.data.disk.UploadScheduler
import com.efisuby.abl.data.disk.UploadTracker
import com.efisuby.abl.data.disk.WorkManagerUploadScheduler
import com.efisuby.abl.data.disk.CloudBookImporter
import com.efisuby.abl.data.disk.CloudLibraryAdder
import com.efisuby.abl.data.disk.DiskApi
import com.efisuby.abl.data.disk.DownloadController
import com.efisuby.abl.data.disk.DownloadScheduler
import com.efisuby.abl.data.disk.MediaBookFinalizer
import com.efisuby.abl.data.disk.WorkManagerDownloadScheduler
import com.efisuby.abl.data.disk.DiskSource
import com.efisuby.abl.data.importer.BookImporter
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.CascadingChapterReader
import com.efisuby.abl.data.importer.Media3ChapterReader
import com.efisuby.abl.data.importer.MediaMetadataTagReader
import com.efisuby.abl.data.importer.Mp4ChapterTrackReader
import com.efisuby.abl.data.importer.ImportSources
import com.efisuby.abl.data.importer.SafImportSources
import com.efisuby.abl.data.repository.BookActions
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
import okhttp3.OkHttpClient
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

    /** Yandex OAuth token: encrypted with a Keystore key, in the no-backup directory. */
    private var authOverride: AuthRepository? = null
    private val defaultAuth: AuthRepository by lazy {
        AuthRepository(TokenStore(File(appContext.noBackupFilesDir, "yandex-token.bin"), keystoreCipher("abl-yandex-token")))
    }

    /** A `var` so UI tests can stand in a signed-in state without touching the real token. */
    var authRepository: AuthRepository
        get() = authOverride ?: defaultAuth
        set(value) {
            authOverride = value
        }

    private val httpClient: OkHttpClient by lazy { OkHttpClient() }
    val diskApi: DiskApi by lazy { DiskApi(httpClient, authRepository) }
    private val defaultDisk: DiskSource get() = diskApi
    private var diskOverride: DiskSource? = null

    /** A `var` so UI tests can serve a fake Disk tree. */
    var diskSource: DiskSource
        get() = diskOverride ?: defaultDisk
        set(value) {
            diskOverride = value
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

    private val chapterAssembler by lazy {
        Pair(
            MediaMetadataTagReader(),
            CascadingChapterReader(listOf(Media3ChapterReader(appContext), Mp4ChapterTrackReader())),
        )
    }

    val cloudBookImporter: CloudBookImporter by lazy { CloudBookImporter(diskSource, bookStorage, libraryRepository) }

    val bookDownloader: BookDownloader by lazy {
        BookDownloader(
            links = diskApi,
            // The download host can take long to answer the first byte of a big file.
            http = httpClient.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build(),
            storage = bookStorage,
            library = libraryRepository,
            finalizer = MediaBookFinalizer(bookStorage, libraryRepository, chapterAssembler.first, chapterAssembler.second),
        )
    }

    /** A `var` so tests can replace WorkManager with a fake. */
    var downloadScheduler: DownloadScheduler = WorkManagerDownloadScheduler(appContext)

    val downloadController: DownloadController by lazy {
        DownloadController(
            libraryRepository,
            bookStorage,
            settingsRepository,
            // Looked up per call, so a scheduler swapped in by a test is used by an existing controller too.
            object : DownloadScheduler {
                override fun enqueue(bookId: String, wifiOnly: Boolean) = downloadScheduler.enqueue(bookId, wifiOnly)

                override fun cancel(bookId: String) = downloadScheduler.cancel(bookId)
            },
        )
    }

    val bookUploader: BookUploader by lazy {
        BookUploader(
            disk = diskApi,
            // A big file is sent in one request; the default 10 s write timeout would cut it.
            http = httpClient.newBuilder()
                .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            storage = bookStorage,
            library = libraryRepository,
        )
    }

    val uploadTracker: UploadTracker = UploadTracker()

    /** A `var` so tests can replace WorkManager with a fake. */
    var uploadScheduler: UploadScheduler = WorkManagerUploadScheduler(appContext)

    val uploadController: UploadController by lazy {
        UploadController(
            libraryRepository,
            settingsRepository,
            // Looked up per call, so a scheduler swapped in by a test is used by an existing controller too.
            object : UploadScheduler {
                override fun enqueue(bookId: String, wifiOnly: Boolean) = uploadScheduler.enqueue(bookId, wifiOnly)

                override fun cancel(bookId: String) = uploadScheduler.cancel(bookId)
            },
            uploadTracker,
        )
    }

    val cloudLibraryAdder: CloudLibraryAdder by lazy { CloudLibraryAdder(cloudBookImporter, downloadController) }

    /** The default 10 s timeouts are too short for many small requests over a slow or VPN link. */
    private val syncHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val syncDiskApi: DiskApi by lazy { DiskApi(syncHttpClient, authRepository) }

    val syncRunner: SyncRunner by lazy {
        SyncRunner(
            engine = SyncEngine(
                RoomSyncStore(database, settingsRepository, bookStorage, cloudBookImporter, syncDiskApi::resource),
                DiskCloudFiles(syncDiskApi, syncHttpClient),
                deviceIdProvider.deviceId(),
            ),
            signedIn = { authRepository.state.value == AuthState.SIGNED_IN },
            onSuccess = settingsRepository::setLastSyncAt,
            onFailure = { e ->
                android.util.Log.w("SyncRunner", "Sync failed: ${e.javaClass.simpleName}" + (e.message?.let { " ($it)" } ?: ""))
            },
        )
    }

    /** Synchronization triggers go through here; a `var` so instrumented tests can count requests. */
    var syncScheduler: SyncScheduler = WorkManagerSyncScheduler(appContext)

    val bookActions: BookActions by lazy { BookActions(libraryRepository, progressRepository, bookStorage) }

    /** A `var` so instrumented tests can serve fixtures through a mock content resolver. */
    var importSources: ImportSources = SafImportSources(appContext.contentResolver)
}
