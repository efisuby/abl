package com.efisuby.abl.ui.common

import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.disk.StartResult
import com.efisuby.abl.domain.model.Book
import com.efisuby.abl.domain.model.BookSource
import com.efisuby.abl.domain.model.DownloadState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** A cloud book whose audio is not fully on the device cannot be played (offline spec). */
val Book.needsDownload: Boolean
    get() = source == BookSource.YANDEX_DISK && downloadState != DownloadState.DOWNLOADED

fun DownloadState.labelRes(): Int? = when (this) {
    DownloadState.IN_CLOUD -> R.string.download_state_in_cloud
    DownloadState.DOWNLOADING -> R.string.download_state_downloading
    DownloadState.PARTIAL -> R.string.download_state_partial
    else -> null
}

/** Queues the download and tells the user how it went, including the not-enough-space warning. */
fun AppCompatActivity.startDownload(bookId: String) {
    lifecycleScope.launch {
        when (val result = appContainer.downloadController.start(bookId)) {
            StartResult.Started -> Toast.makeText(this@startDownload, R.string.download_started, Toast.LENGTH_SHORT).show()
            StartResult.AlreadyDownloading -> Unit
            StartResult.Unavailable -> Toast.makeText(this@startDownload, R.string.download_unavailable, Toast.LENGTH_SHORT).show()
            is StartResult.NotEnoughSpace -> showNoSpace(result.requiredBytes, result.freeBytes)
        }
    }
}

/** The "not enough space" warning with the size of the download and what is free. */
fun AppCompatActivity.showNoSpace(requiredBytes: Long, freeBytes: Long) {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.download_no_space_title)
        .setMessage(
            getString(
                R.string.download_no_space_message,
                Formatter.formatFileSize(this, requiredBytes),
                Formatter.formatFileSize(this, freeBytes),
            ),
        )
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

/** Playback of a book that is not on the device is replaced by an offer to download it. */
fun AppCompatActivity.offerDownload(book: Book) {
    lifecycleScope.launch {
        val builder = MaterialAlertDialogBuilder(this@offerDownload).setTitle(R.string.download_offer_title)
        if (book.downloadState == DownloadState.DOWNLOADING) {
            builder.setMessage(getString(R.string.download_offer_downloading, book.title))
                .setPositiveButton(android.R.string.ok, null)
        } else {
            val size = appContainer.downloadController.totalBytes(book.id)?.let { Formatter.formatFileSize(this@offerDownload, it) }.orEmpty()
            builder.setMessage(getString(R.string.download_offer_message, book.title, size))
                .setPositiveButton(R.string.download_start) { _, _ -> startDownload(book.id) }
                .setNegativeButton(R.string.bookmark_cancel, null)
        }
        builder.show()
    }
}
