package com.efisuby.abl.data.disk

import android.content.Context
import com.efisuby.abl.data.auth.AesGcmCipher
import com.efisuby.abl.data.auth.AuthRepository
import com.efisuby.abl.data.auth.TokenStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator

/**
 * A Yandex Disk on the loopback address for instrumented tests: folders and files in memory,
 * listings, pre-signed download links with `Range`, folder creation with 409, and files that
 * answer 500 on demand. Point [DiskApi] at [baseUrl].
 */
class FakeDiskServer : AutoCloseable {

    private val server = MockWebServer()

    /** Absolute path (`disk:/a/b`) to content. */
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val folders: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("disk:/") }

    /** Paths whose download answers 500. */
    val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every request line, in order, for assertions. */
    val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    val baseUrl: String get() = server.url("/v1/disk").toString().trimEnd('/')

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                val url = request.requestUrl!!
                val path = url.queryParameter("path").orEmpty()
                return when {
                    request.method == "PUT" && url.encodedPath.startsWith("/put/") -> {
                        files["disk:/" + decode(url.encodedPath.removePrefix("/put/"))] = request.body.readByteArray()
                        MockResponse().setResponseCode(201)
                    }
                    url.encodedPath.endsWith("/resources/upload") ->
                        json("""{"href":"${server.url("/put/" + encode(path.removePrefix("disk:/")))}","method":"PUT"}""")
                    url.encodedPath.startsWith("/file/") -> serveFile(request, "disk:/" + url.encodedPath.removePrefix("/file/").let(::decode))
                    url.encodedPath.endsWith("/resources/download") ->
                        if (files.containsKey(path)) json("""{"href":"${server.url("/file/" + encode(path.removePrefix("disk:/")))}","method":"GET"}""") else notFound()
                    request.method == "PUT" && url.encodedPath.endsWith("/resources") -> createFolder(path)
                    request.method == "GET" && url.encodedPath.endsWith("/resources") -> list(path, url.queryParameter("limit")?.toInt() ?: 100, url.queryParameter("offset")?.toInt() ?: 0)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    /** Adds [content] at [path], creating the parent folders. */
    fun put(path: String, content: ByteArray) {
        var parent = path.substringBeforeLast('/')
        while (parent.length > "disk:/".length) {
            folders += parent
            parent = parent.substringBeforeLast('/')
        }
        files[path] = content
    }

    /** A [DiskApi] against this server with a signed-in token of its own; the device's real token is never touched. */
    fun api(context: Context): DiskApi {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val auth = AuthRepository(TokenStore(File(context.cacheDir, "fake-disk-token.bin"), AesGcmCipher { key })).also { it.signIn("test") }
        return DiskApi(OkHttpClient(), auth, baseUrl, maxRetries = 0, backoffMs = 0)
    }

    private fun createFolder(path: String): MockResponse {
        val parent = path.substringBeforeLast('/').let { if (it == "disk:") "disk:/" else it }
        return when {
            path in folders -> MockResponse().setResponseCode(409)
            parent !in folders -> MockResponse().setResponseCode(409)
            else -> {
                folders += path
                MockResponse().setResponseCode(201).setBody("{}")
            }
        }
    }

    private fun list(path: String, limit: Int, offset: Int): MockResponse {
        files[path]?.let { return json("""{"name":"${path.substringAfterLast('/')}","path":"$path","type":"file","size":${it.size},"md5":"${md5(it)}"}""") }
        if (path !in folders) return notFound()
        val prefix = if (path.endsWith("/")) path else "$path/"
        val children = (folders.filter { it != path && it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }.map { it to null } +
            files.filter { it.key.startsWith(prefix) && '/' !in it.key.removePrefix(prefix) }.map { it.key to it.value })
            .sortedBy { it.first }
        val items = children.drop(offset).take(limit).joinToString(",") { (p, content) ->
            val name = p.substringAfterLast('/')
            if (content == null) {
                """{"name":"$name","path":"$p","type":"dir"}"""
            } else {
                """{"name":"$name","path":"$p","type":"file","size":${content.size},"md5":"${md5(content)}"}"""
            }
        }
        return json("""{"name":"${path.substringAfterLast('/')}","path":"$path","type":"dir","_embedded":{"items":[$items],"total":${children.size},"offset":$offset,"limit":$limit}}""")
    }

    private fun serveFile(request: RecordedRequest, path: String): MockResponse {
        if (path in failing) return MockResponse().setResponseCode(500)
        val content = files[path] ?: return MockResponse().setResponseCode(404)
        val range = request.getHeader("Range")
        val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
        val response = MockResponse().setBody(Buffer().write(content, start, content.size - start))
        if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
        return response
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun notFound() = MockResponse().setResponseCode(404).setBody("""{"error":"DiskNotFoundError"}""")

    override fun close() = server.shutdown()

    companion object {
        fun md5(content: ByteArray): String =
            java.security.MessageDigest.getInstance("MD5").digest(content).joinToString("") { "%02x".format(it) }

        private fun encode(path: String) = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        private fun decode(path: String) = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    }
}
