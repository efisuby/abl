package com.efisuby.abl.data.sync

import com.efisuby.abl.data.disk.DiskException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SyncRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A cloud that fails like a missing network until [online] is set. */
    private class FlakyCloud(private val inner: CloudFiles, var online: Boolean = false, var error: Exception = IOException("offline")) : CloudFiles {
        override suspend fun read(path: String) = if (online) inner.read(path) else throw error
        override suspend fun write(path: String, bytes: ByteArray) = if (online) inner.write(path, bytes) else throw error
        override suspend fun list(dir: String) = if (online) inner.list(dir) else throw error
    }

    private fun store() = SyncEngineTestStore().apply {
        progress["book"] = ProgressEntry("book", "c1", 5000, 5000, false, 10, "phone")
        bookmarks["b1"] = BookmarkEntry("b1", "book", "c1", 1000, null, 1, 1)
    }

    @Test
    fun changesMadeOfflineAreSentOnceTheNetworkReturns() = runBlocking {
        val dir = tmp.newFolder()
        val cloud = FlakyCloud(DirectoryCloudFiles(dir))
        var lastSync: Long? = null
        val runner = SyncRunner(SyncEngine(store(), cloud, "phone"), { true }, { lastSync = it }, clock = { 99 })

        assertEquals(SyncOutcome.RETRY, runner.run())
        assertNull(lastSync)
        assertEquals(0, dir.walk().count { it.isFile })

        cloud.online = true
        assertEquals(SyncOutcome.DONE, runner.run())
        assertEquals(99L, lastSync)
        assertNotNull(DirectoryCloudFiles(dir).read("progress/phone.json"))
        assertNotNull(DirectoryCloudFiles(dir).read("bookmarks.json"))
    }

    @Test
    fun rejectedTokenDoesNotRetry() = runBlocking {
        val cloud = FlakyCloud(DirectoryCloudFiles(tmp.newFolder()), error = DiskException.Unauthorized())
        val runner = SyncRunner(SyncEngine(store(), cloud, "phone"), { true }, {})
        assertEquals(SyncOutcome.FAILED, runner.run())
    }

    @Test
    fun serverTroubleRetries() = runBlocking {
        val cloud = FlakyCloud(DirectoryCloudFiles(tmp.newFolder()), error = DiskException.Http(503))
        val runner = SyncRunner(SyncEngine(store(), cloud, "phone"), { true }, {})
        assertEquals(SyncOutcome.RETRY, runner.run())
    }

    @Test
    fun withoutAccountNothingHappens() = runBlocking {
        val dir = tmp.newFolder()
        val runner = SyncRunner(SyncEngine(store(), DirectoryCloudFiles(dir), "phone"), { false }, {})
        assertEquals(SyncOutcome.SIGNED_OUT, runner.run())
        assertEquals(0, dir.walk().count { it.isFile })
    }
}
