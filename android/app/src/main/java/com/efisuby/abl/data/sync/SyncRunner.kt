package com.efisuby.abl.data.sync

import com.efisuby.abl.data.disk.RetryPolicy
import kotlinx.coroutines.CancellationException

enum class SyncOutcome {
    DONE,

    /** Network or server trouble: nothing was lost, ask the scheduler to try again later with backoff. */
    RETRY,

    /** Retrying cannot help (rejected token, unexpected error). */
    FAILED,

    /** No account, so there is nothing to synchronize with. */
    SIGNED_OUT,
}

/**
 * Runs one [SyncEngine] exchange and classifies the result for the scheduler. There is no queue of
 * changes to store: local changes already live in the database (progress, bookmarks, metadata), and
 * every exchange publishes whatever differs from the cloud, so "sending after the network returns"
 * is simply running again.
 */
class SyncRunner(
    private val engine: SyncEngine,
    private val signedIn: () -> Boolean,
    private val onSuccess: suspend (finishedAt: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun run(): SyncOutcome {
        if (!signedIn()) return SyncOutcome.SIGNED_OUT
        return try {
            engine.sync()
            onSuccess(clock())
            SyncOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (RetryPolicy.isTransient(e)) SyncOutcome.RETRY else SyncOutcome.FAILED
        }
    }
}
