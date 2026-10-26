package com.efisuby.abl.data.disk

import com.efisuby.abl.data.auth.AuthRepository
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the cloud browser needs from Disk; faked in UI tests. */
interface DiskSource {
    suspend fun list(path: String, limit: Int = 100, offset: Int = 0): DiskItems
}

/** Pre-signed download links; faked in tests. */
interface DiskLinks {
    suspend fun downloadLink(path: String): DiskLink
}

/** Every entry of one level, paging through large folders. */
suspend fun DiskSource.listAll(path: String, pageSize: Int = 100): List<DiskResource> {
    val all = mutableListOf<DiskResource>()
    while (true) {
        val page = list(path, pageSize, all.size)
        all += page.items
        if (page.items.isEmpty() || all.size >= page.total) return all
    }
}

/**
 * Yandex Disk REST API (`/v1/disk/resources`): listings and pre-signed download/upload links.
 * 401 drops the token through [auth]; 429 and 503 are retried with exponential backoff.
 */
class DiskApi(
    private val client: OkHttpClient,
    private val auth: AuthRepository,
    baseUrl: String = DEFAULT_BASE_URL,
    private val maxRetries: Int = 3,
    private val backoffMs: Long = 1_000,
    /** Replaced in tests so backoff does not slow them down. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DiskSource, DiskLinks {

    private val base: HttpUrl = baseUrl.toHttpUrl()

    /** One level of [path] (e.g. `app:/books`); [offset] and [limit] page through large folders. */
    override suspend fun list(path: String, limit: Int, offset: Int): DiskItems {
        val url = resourcesUrl().newBuilder()
            .addQueryParameter("path", path)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("sort", "name")
            .addQueryParameter("fields", LIST_FIELDS)
            .build()
        val resource = get<DiskResource>(url, path)
        return resource.embedded ?: DiskItems()
    }

    suspend fun resource(path: String): DiskResource =
        get(resourcesUrl().newBuilder().addQueryParameter("path", path).build(), path)

    /** A temporary link to download the file at [path]; the file itself is fetched without the token. */
    override suspend fun downloadLink(path: String): DiskLink =
        get(resourcesUrl("download").newBuilder().addQueryParameter("path", path).build(), path)

    suspend fun uploadLink(path: String, overwrite: Boolean = true): DiskLink =
        get(
            resourcesUrl("upload").newBuilder()
                .addQueryParameter("path", path)
                .addQueryParameter("overwrite", overwrite.toString())
                .build(),
            path,
        )

    private fun resourcesUrl(suffix: String? = null): HttpUrl = base.newBuilder()
        .addPathSegments("v1/disk/resources")
        .apply { suffix?.let { addPathSegment(it) } }
        .build()

    private suspend inline fun <reified T> get(url: HttpUrl, path: String): T {
        val body = execute(url, path)
        return json.decodeFromString(body)
    }

    /** On the IO dispatcher: reading and closing the body is blocking, and the caller may be on Main. */
    private suspend fun execute(url: HttpUrl, path: String): String = withContext(Dispatchers.IO) {
        executeBlocking(url, path)
    }

    private suspend fun executeBlocking(url: HttpUrl, path: String): String {
        var attempt = 0
        while (true) {
            // The token is read per attempt: a sign-out between retries must stop the request.
            val token = auth.token() ?: throw DiskException.NotSignedIn()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "OAuth $token")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).await().use { response ->
                when (val code = response.code) {
                    in 200..299 -> return response.body?.string().orEmpty()
                    401 -> {
                        auth.onUnauthorized()
                        throw DiskException.Unauthorized()
                    }
                    404 -> throw DiskException.NotFound(path)
                    429, 503 -> {
                        if (attempt >= maxRetries) {
                            if (code == 429) throw DiskException.RateLimited() else throw DiskException.Http(code)
                        }
                    }
                    else -> throw DiskException.Http(code)
                }
            }
            sleep(backoffMs shl attempt)
            attempt++
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://cloud-api.yandex.net/"

        /** The fixed folder the user puts audiobooks into (see design D8); the system folder is localized, this is the Russian account layout. */
        const val BOOKS_ROOT = "disk:/Приложения/AudioBookPlayer/books"

        /** Keeps listings small: no previews, EXIF and the like. */
        private const val LIST_FIELDS =
            "name,path,type,_embedded.total,_embedded.offset,_embedded.limit," +
                "_embedded.items.name,_embedded.items.path,_embedded.items.type," +
                "_embedded.items.size,_embedded.items.md5,_embedded.items.mime_type,_embedded.items.media_type"

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Suspends until the response arrives; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
