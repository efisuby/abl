package com.efisuby.abl.data.sync

import com.efisuby.abl.data.disk.DiskApi
import com.efisuby.abl.data.disk.DiskException
import com.efisuby.abl.data.disk.DiskSource
import com.efisuby.abl.data.disk.listAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/** The synchronization folder, addressed by paths relative to it (`progress/<id>.json`). */
interface CloudFiles {
    /** Null when the file does not exist. */
    suspend fun read(path: String): ByteArray?

    suspend fun write(path: String, bytes: ByteArray)

    /** File names directly inside [dir]; empty when it does not exist. */
    suspend fun list(dir: String): List<String>
}

/** [CloudFiles] on Yandex Disk, under the app folder. */
class DiskCloudFiles(
    private val api: DiskApi,
    private val http: OkHttpClient,
    private val root: String = DEFAULT_ROOT,
) : CloudFiles {

    private val knownFolders = mutableSetOf<String>()

    override suspend fun read(path: String): ByteArray? {
        val href = try {
            api.downloadLink("$root/$path").href
        } catch (e: DiskException.NotFound) {
            return null
        }
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(href).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download answered HTTP ${response.code}")
                response.body?.bytes() ?: throw IOException("Empty download response")
            }
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        ensureFolder(path.substringBeforeLast('/', ""))
        val link = api.uploadLink("$root/$path", overwrite = true)
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(link.href).put(bytes.toRequestBody()).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Upload answered HTTP ${response.code}")
            }
        }
    }

    override suspend fun list(dir: String): List<String> {
        val source: DiskSource = api
        return try {
            source.listAll("$root/$dir").filter { !it.isFolder }.map { it.name }
        } catch (e: DiskException.NotFound) {
            emptyList()
        }
    }

    private suspend fun ensureFolder(relative: String) {
        if (relative.isEmpty() || relative in knownFolders) return
        api.ensureFolder("$root/$relative")
        knownFolders += relative
    }

    companion object {
        const val DEFAULT_ROOT = "disk:/Приложения/AudioBookPlayer"
    }
}

/** [CloudFiles] on a local directory: a stand-in for "the cloud" shared by emulated devices in tests. */
class DirectoryCloudFiles(private val dir: File) : CloudFiles {

    override suspend fun read(path: String): ByteArray? =
        withContext(Dispatchers.IO) { File(dir, path).takeIf { it.isFile }?.readBytes() }

    override suspend fun write(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = File(dir, path)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    override suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        File(this@DirectoryCloudFiles.dir, dir).listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
    }
}
