package com.efisuby.abl.data.disk

import com.efisuby.abl.data.auth.AesGcmCipher
import com.efisuby.abl.data.auth.AuthRepository
import com.efisuby.abl.data.auth.AuthState
import com.efisuby.abl.data.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class DiskApiTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()
    private lateinit var auth: AuthRepository
    private lateinit var api: DiskApi

    @Before
    fun setUp() {
        server.start()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        auth = AuthRepository(TokenStore(folder.root.resolve("t"), AesGcmCipher { key }))
        auth.signIn("tok")
        api = DiskApi(OkHttpClient(), auth, server.url("/").toString(), sleep = { sleeps += it })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun listsOneLevelWithTokenHeader() = runBlocking {
        server.enqueue(
            json(
                """{"name":"books","path":"disk:/Apps/x/books","type":"dir","unknown":1,
                    "_embedded":{"total":2,"offset":0,"limit":100,"items":[
                    {"name":"A","path":"disk:/Apps/x/books/A","type":"dir"},
                    {"name":"b.m4b","path":"disk:/Apps/x/books/b.m4b","type":"file","size":42,"md5":"abc","mime_type":"audio/mp4"}]}}""",
            ),
        )
        val items = api.list("disk:/Apps/x/books")

        assertEquals(listOf("A", "b.m4b"), items.items.map { it.name })
        assertTrue(items.items[0].isFolder)
        assertEquals(42L, items.items[1].size)
        assertEquals("abc", items.items[1].md5)
        val request = server.takeRequest()
        assertEquals("OAuth tok", request.getHeader("Authorization"))
        assertEquals("/v1/disk/resources", request.requestUrl!!.encodedPath)
        assertEquals("disk:/Apps/x/books", request.requestUrl!!.queryParameter("path"))
    }

    @Test
    fun downloadAndUploadLinks() = runBlocking {
        server.enqueue(json("""{"href":"https://dl.example/f","method":"GET","templated":false}"""))
        server.enqueue(json("""{"href":"https://up.example/f","method":"PUT"}"""))

        assertEquals("https://dl.example/f", api.downloadLink("disk:/a.mp3").href)
        assertEquals("PUT", api.uploadLink("disk:/p.json").method)
        assertEquals("/v1/disk/resources/download", server.takeRequest().requestUrl!!.encodedPath)
        val up = server.takeRequest().requestUrl!!
        assertEquals("/v1/disk/resources/upload", up.encodedPath)
        assertEquals("true", up.queryParameter("overwrite"))
    }

    @Test
    fun retriesAfter429WithGrowingBackoff() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(json("""{"href":"https://dl.example/f"}"""))

        assertEquals("https://dl.example/f", api.downloadLink("disk:/a.mp3").href)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test
    fun givesUpWhen429Persists() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429)) }
        assertThrows(DiskException.RateLimited::class.java) { runBlocking { api.downloadLink("disk:/a") } }
        assertEquals(4, server.requestCount) // first try + 3 retries
    }

    @Test
    fun unauthorizedDropsTheTokenAndKeepsAuthStateForReSignIn() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(DiskException.Unauthorized::class.java) { runBlocking { api.list("disk:/x") } }
        assertEquals(AuthState.REAUTH_REQUIRED, auth.state.value)
        assertNull(auth.token())

        // Without a token nothing is sent.
        assertThrows(DiskException.NotSignedIn::class.java) { runBlocking { api.list("disk:/x") } }
        assertEquals(1, server.requestCount)
        assertFalse(sleeps.isNotEmpty())
    }

    @Test
    fun notFoundAndOtherErrors() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(DiskException.NotFound::class.java) { runBlocking { api.list("disk:/nope") } }
        val e = assertThrows(DiskException.Http::class.java) { runBlocking { api.list("disk:/x") } }
        assertEquals(500, e.code)
    }

    @Test
    fun ensureFolderCreatesTheWholeChainTopDown() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(201)) }

        api.ensureFolder("disk:/Apps/AudioBookPlayer/books/Foo")

        val requests = (1..4).map { server.takeRequest() }
        assertTrue(requests.all { it.method == "PUT" })
        assertEquals(
            listOf("disk:/Apps", "disk:/Apps/AudioBookPlayer", "disk:/Apps/AudioBookPlayer/books", "disk:/Apps/AudioBookPlayer/books/Foo"),
            requests.map { it.requestUrl!!.queryParameter("path") },
        )
    }

    @Test
    fun ensureFolderTreatsExistingFoldersAsDone() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409)) // disk:/a exists
        server.enqueue(MockResponse().setResponseCode(201)) // disk:/a/b is new

        api.ensureFolder("disk:/a/b")

        assertEquals("disk:/a", server.takeRequest().requestUrl!!.queryParameter("path"))
        assertEquals("disk:/a/b", server.takeRequest().requestUrl!!.queryParameter("path"))
    }
}
