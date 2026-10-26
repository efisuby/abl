package com.efisuby.abl.data.disk

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/** [UploadScheduler] on WorkManager: one unique job per book, constrained by the network type. */
class WorkManagerUploadScheduler(context: Context) : UploadScheduler {

    private val work = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(bookId: String, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(Data.Builder().putString(UploadWorker.KEY_BOOK_ID, bookId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(downloadNetworkType(wifiOnly)).build())
            .build()
        work.enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(bookId: String) {
        work.cancelUniqueWork(workName(bookId))
    }

    private fun workName(bookId: String) = "upload-$bookId"
}

/** Uploads one book in a `dataSync` foreground service; transient trouble is retried like downloads. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val container = applicationContext.appContainer
        val tracker = container.uploadTracker
        val book = container.libraryRepository.getBook(bookId) ?: return Result.failure()

        showProgress(book.title, 0)
        var lastShown = 0L
        var attempt = 0
        while (true) {
            attempt++
            try {
                container.bookUploader.run(bookId) { done, total ->
                    tracker.set(bookId, UploadStatus.Running(done, total))
                    val now = System.currentTimeMillis()
                    if (now - lastShown >= PROGRESS_INTERVAL_MS) {
                        lastShown = now
                        setForegroundAsync(foregroundInfo(book.title, if (total > 0) (done * 100 / total).toInt() else 0))
                    }
                }
                tracker.set(bookId, null)
                return Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class and HTTP code only: neither the token nor response bodies are logged.
                Log.w(TAG, "Attempt $attempt failed: ${e.javaClass.simpleName}" + (e.takeIf { it is IOException }?.message?.let { " ($it)" } ?: ""))
                if (!RetryPolicy.isTransient(e) || attempt >= RetryPolicy.MAX_ATTEMPTS) {
                    tracker.set(bookId, UploadStatus.Failed(e.toUploadError()))
                    return Result.failure()
                }
                // Files already sent are found on Disk by the next attempt and skipped.
                delay(RetryPolicy.DELAY_MS)
            }
        }
    }

    private suspend fun showProgress(title: String, percent: Int) {
        try {
            setForeground(foregroundInfo(title, percent))
        } catch (e: IllegalStateException) {
            // Runs as an ordinary background job.
        }
    }

    @SuppressLint("InlinedApi")
    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(applicationContext.getString(R.string.upload_notification_title))
            .setContentText(title)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                applicationContext.getString(R.string.download_cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val KEY_BOOK_ID = "bookId"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 3
        private const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
