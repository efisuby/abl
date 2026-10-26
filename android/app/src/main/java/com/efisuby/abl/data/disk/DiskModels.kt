package com.efisuby.abl.data.disk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A file or folder on Yandex Disk (`/v1/disk/resources`). */
@Serializable
data class DiskResource(
    val name: String,
    val path: String,
    /** `dir` or `file`. */
    val type: String,
    val size: Long = 0,
    val md5: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("_embedded") val embedded: DiskItems? = null,
) {
    val isFolder: Boolean get() = type == "dir"
}

@Serializable
data class DiskItems(
    val items: List<DiskResource> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

/** A pre-signed URL for a download or an upload. */
@Serializable
data class DiskLink(
    val href: String,
    val method: String = "GET",
    val templated: Boolean = false,
)

/** Failures of a Disk API call that callers are expected to handle. */
sealed class DiskException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401: the token is rejected; [com.efisuby.abl.data.auth.AuthRepository] has been told. */
    class Unauthorized : DiskException("Yandex Disk rejected the token")

    /** 404. */
    class NotFound(val path: String) : DiskException("Not found on Yandex Disk")

    /** 429 persisted after every retry. */
    class RateLimited : DiskException("Too many requests to Yandex Disk")

    class Http(val code: Int) : DiskException("Yandex Disk answered HTTP $code")

    /** No token: the user has not signed in. */
    class NotSignedIn : DiskException("Not signed in to Yandex")
}
