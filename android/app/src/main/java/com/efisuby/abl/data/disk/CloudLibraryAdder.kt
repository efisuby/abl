package com.efisuby.abl.data.disk

import com.efisuby.abl.data.importer.ImportException
import com.efisuby.abl.domain.model.DownloadState
import kotlinx.coroutines.CancellationException
import java.io.IOException

sealed interface AddResult {
    /** New cloud book, download queued. */
    data object Added : AddResult

    /** The book was in the library already; [downloadStarted] if it was not on the device and now is being fetched. */
    data class AlreadyThere(val downloadStarted: Boolean) : AddResult

    data object NoAudio : AddResult

    data class NotEnoughSpace(val requiredBytes: Long, val freeBytes: Long) : AddResult

    data object Failed : AddResult
}

/** "Download" in the cloud browser: adds the folder or file as a cloud book and starts fetching it. */
class CloudLibraryAdder(
    private val importer: CloudBookImporter,
    private val downloads: DownloadController,
) {

    suspend fun add(resource: DiskResource): AddResult {
        val imported = try {
            importer.import(resource)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportException) {
            return if (e.reason == ImportException.Reason.NO_AUDIO_FILES) AddResult.NoAudio else AddResult.Failed
        } catch (e: DiskException) {
            return AddResult.Failed
        } catch (e: IOException) {
            return AddResult.Failed
        }
        val state = imported.book.downloadState
        val needsFiles = state == DownloadState.IN_CLOUD || state == DownloadState.PARTIAL
        if (!imported.created && !needsFiles) return AddResult.AlreadyThere(downloadStarted = false)
        return when (val start = downloads.start(imported.book.id)) {
            StartResult.Started, StartResult.AlreadyDownloading ->
                if (imported.created) AddResult.Added else AddResult.AlreadyThere(downloadStarted = true)
            is StartResult.NotEnoughSpace -> AddResult.NotEnoughSpace(start.requiredBytes, start.freeBytes)
            StartResult.Unavailable -> AddResult.Failed
        }
    }
}
