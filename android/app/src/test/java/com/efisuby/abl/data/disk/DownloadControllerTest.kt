package com.efisuby.abl.data.disk

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.NetworkType
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.settings.SettingsRepository
import com.efisuby.abl.domain.model.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadControllerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val library = FakeLibrary()
    private val storage by lazy { BookStorage(folder.root.resolve("books")) }
    private val scheduler = RecordingScheduler()
    private var free = Long.MAX_VALUE

    private class RecordingScheduler : DownloadScheduler {
        val enqueued = mutableListOf<Pair<String, Boolean>>()
        val cancelled = mutableListOf<String>()
        override fun enqueue(bookId: String, wifiOnly: Boolean) {
            enqueued += bookId to wifiOnly
        }
        override fun cancel(bookId: String) {
            cancelled += bookId
        }
    }

    private fun controller(): DownloadController {
        val store = PreferenceDataStoreFactory.create(produceFile = { folder.root.resolve("settings.preferences_pb") })
        return DownloadController(library, storage, SettingsRepository(store), scheduler, freeSpace = { free }, ioDispatcher = Dispatchers.Unconfined)
    }

    private val mb = 1024L * 1024

    @Test
    fun wifiOnlyMeansUnmeteredNetwork() {
        assertEquals(NetworkType.UNMETERED, downloadNetworkType(true))
        assertEquals(NetworkType.CONNECTED, downloadNetworkType(false))
    }

    @Test
    fun startQueuesTheJobWithTheWifiSetting() = runBlocking {
        library.addCloudBook(storage, files = listOf(Triple("1.mp3", 10 * mb, "")))
        assertEquals(StartResult.Started, controller().start("cloud-book"))
        // "Wi-Fi only" is on by default.
        assertEquals(listOf("cloud-book" to true), scheduler.enqueued)
        assertEquals(DownloadState.DOWNLOADING, library.books.getValue("cloud-book").downloadState)
    }

    @Test
    fun notEnoughSpaceDoesNotStartAndReportsTheSize() = runBlocking {
        library.addCloudBook(storage, files = listOf(Triple("1.m4b", 1800 * mb, "")))
        free = 900 * mb

        val result = controller().start("cloud-book")

        assertEquals(StartResult.NotEnoughSpace(1800 * mb, 900 * mb), result)
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(DownloadState.IN_CLOUD, library.books.getValue("cloud-book").downloadState)
    }

    @Test
    fun remainingBytesSubtractsFinishedFilesAndPartialProgress() = runBlocking {
        library.addCloudBook(storage, files = listOf(Triple("1.mp3", 100, ""), Triple("2.mp3", 200, ""), Triple("3.mp3", 300, "")))
        storage.audioFile("cloud-book", "1.mp3").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100)) }
        storage.audioFile("cloud-book", "2.mp3.part").writeBytes(ByteArray(50))

        val controller = controller()
        assertEquals(450L, controller.remainingBytes("cloud-book"))
        assertEquals(600L, controller.totalBytes("cloud-book"))
    }

    @Test
    fun cancelReturnsToTheCloudAndRemovesPartialFiles() = runBlocking {
        library.addCloudBook(storage, state = DownloadState.DOWNLOADING, files = listOf(Triple("1.mp3", 100, "")))
        storage.audioFile("cloud-book", "1.mp3.part").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(40)) }

        controller().cancel("cloud-book")

        assertEquals(DownloadState.IN_CLOUD, library.books.getValue("cloud-book").downloadState)
        assertEquals(listOf("cloud-book"), scheduler.cancelled)
        assertFalse(storage.audioDir("cloud-book").exists())
    }
}
