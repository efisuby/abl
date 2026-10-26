package com.efisuby.abl.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.efisuby.abl.appContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Asks for a synchronization; callers do not wait for it. */
interface SyncScheduler {

    /** [immediate] skips the short settling delay used for background triggers (a manual start). */
    fun request(immediate: Boolean = false)

    /** True while a synchronization is waiting for the network or running. */
    val active: Flow<Boolean>
}

/**
 * [SyncScheduler] on WorkManager. The job needs a network, so a request made offline waits and
 * starts when the network appears (the "network appearance" trigger); a failed run is repeated
 * with exponential backoff. Requests queue behind a running job instead of being dropped, so a
 * change made during a synchronization is picked up by the next one.
 */
class WorkManagerSyncScheduler(context: Context) : SyncScheduler {

    private val work = WorkManager.getInstance(context.applicationContext)

    override fun request(immediate: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // The player writes the position every few seconds: let the last write land first.
            .setInitialDelay(if (immediate) 0 else SETTLE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override val active: Flow<Boolean> = work.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).map { infos ->
        infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
    }

    private companion object {
        const val UNIQUE_NAME = "sync"
        const val SETTLE_SECONDS = 10L
        const val BACKOFF_SECONDS = 30L
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (applicationContext.appContainer.syncRunner.run()) {
        SyncOutcome.RETRY -> Result.retry()
        SyncOutcome.DONE, SyncOutcome.SIGNED_OUT, SyncOutcome.FAILED -> Result.success()
    }
}
