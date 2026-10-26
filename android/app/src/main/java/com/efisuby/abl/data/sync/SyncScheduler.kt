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

    /** True while a synchronization is running. */
    val active: Flow<Boolean>
}

/**
 * [SyncScheduler] on WorkManager. The job needs a network, so a request made offline waits and
 * starts when the network appears (the "network appearance" trigger); a failed run is repeated
 * with exponential backoff. Background requests do not pile up behind a pending job; a manual
 * one replaces it and starts at once.
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
        // Every run publishes the whole difference, so a background request while a job is waiting
        // or retrying adds nothing. A manual request replaces it: queued behind a job that sits in
        // backoff it would never start, and cutting a running exchange short loses nothing.
        val policy = if (immediate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        work.enqueueUniqueWork(UNIQUE_NAME, policy, request)
    }

    override val active: Flow<Boolean> = work.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).map { infos ->
        infos.any { it.state == WorkInfo.State.RUNNING }
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
