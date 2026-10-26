package com.efisuby.abl.data.disk

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test of the fake Disk the other instrumented tests rely on. */
@RunWith(AndroidJUnit4::class)
class FakeDiskServerTest {

    private val server = FakeDiskServer()

    @After
    fun tearDown() = server.close()

    @Test
    fun listsAndDownloadsAFixture() = runBlocking {
        val content = ByteArray(5_000) { it.toByte() }
        server.put("disk:/Приложения/Book/01.mp3", content)
        val api = server.api(ApplicationProvider.getApplicationContext())

        val items = api.listAll("disk:/Приложения/Book")
        assertEquals(listOf("01.mp3"), items.map { it.name })
        assertEquals(FakeDiskServer.md5(content), items.single().md5)

        val href = api.downloadLink("disk:/Приложения/Book/01.mp3").href
        val body = OkHttpClient().newCall(Request.Builder().url(href).build()).execute().use { it.body!!.bytes() }
        assertArrayEquals(content, body)
    }

    @Test
    fun ensureFolderBuildsTheChainAndAnExistingOneIsFine() = runBlocking {
        val api = server.api(ApplicationProvider.getApplicationContext())
        api.ensureFolder("disk:/a/b/c")
        api.ensureFolder("disk:/a/b/c")
        assertEquals(setOf("disk:/", "disk:/a", "disk:/a/b", "disk:/a/b/c"), server.folders.toSet())
    }
}
