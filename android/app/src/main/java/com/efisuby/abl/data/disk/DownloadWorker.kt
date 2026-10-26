package com.efisuby.abl.data.disk

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
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
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [DownloadScheduler] on WorkManager: one unique job per book, constrained by the network type. */
class WorkManagerDownloadScheduler(context: Context) : DownloadScheduler {

    private val work = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(bookId: String, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putString(DownloadWorker.KEY_BOOK_ID, bookId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(downloadNetworkType(wifiOnly)).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(bookId: String) {
        work.cancelUniqueWork(workName(bookId))
    }

    private fun workName(bookId: String) = "download-$bookId"
}

/** Downloads one book in a `dataSync` foreground service with a progress notification. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val container = applicationContext.appContainer
        val book = container.libraryRepository.getBook(bookId) ?: return Result.failure()

        showProgress(book.title, 0)
        var lastShown = 0L
        try {
            container.bookDownloader.run(bookId) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastShown >= PROGRESS_INTERVAL_MS) {
                    lastShown = now
                    setForegroundAsync(foregroundInfo(book.title, if (total > 0) (done * 100 / total).toInt() else 0))
                }
            }
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network trouble is worth another attempt; a rejected token or a vanished file is not.
            val transient = e is IOException && e !is ChecksumMismatchException ||
                e is DiskException.RateLimited || (e is DiskException.Http && e.code >= 500)
            if (transient && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            container.bookDownloader.markFailed(bookId)
            return Result.failure()
        }
    }

    /** Foreground promotion can be refused (e.g. the app is in the background): the download goes on without it. */
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
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(title)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                applicationContext.getString(R.string.download_cancel),
                androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2
        private const val MAX_ATTEMPTS = 5
        private const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
