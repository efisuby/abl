package com.efisuby.abl.data.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns

/** A folder picked with `OpenDocumentTree`, walked recursively through the documents contract. */
class SafTreeSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : ImportSource {

    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override val isFolder: Boolean = true

    override val displayName: String by lazy {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        resolver.query(rootUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: rootId.substringAfterLast(':').substringAfterLast('/')
    }

    override fun listFiles(): List<SourceFile> {
        val result = mutableListOf<SourceFile>()
        collect(rootId, prefix = "", result)
        return result
    }

    private fun collect(parentId: String, prefix: String, into: MutableList<SourceFile>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )
        val children = mutableListOf<Child>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                children += Child(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (cursor.isNull(3)) -1L else cursor.getLong(3),
                )
            }
        }
        for (child in children) {
            val path = prefix + child.name
            if (child.isDirectory) {
                collect(child.id, "$path/", into)
            } else {
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                into += SourceFile(path, child.size) {
                    resolver.openInputStream(documentUri) ?: error("Cannot open $documentUri")
                }
            }
        }
    }

    private class Child(val id: String, val name: String, val isDirectory: Boolean, val size: Long)
}

/** A single file picked with `OpenDocument`. */
class SafFileSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ImportSource {

    private val info: Pair<String, Long> by lazy {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(0) ?: uri.lastPathSegment.orEmpty()
                val size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                name to size
            }
            ?: (uri.lastPathSegment.orEmpty() to -1L)
    }

    override val isFolder: Boolean = false

    override val displayName: String get() = info.first

    override fun listFiles(): List<SourceFile> = listOf(
        SourceFile(info.first, info.second) {
            resolver.openInputStream(uri) ?: error("Cannot open $uri")
        },
    )
}

/** Turns what the system picker returned into an [ImportSource]. */
interface ImportSources {

    fun folder(treeUri: Uri): ImportSource

    fun file(uri: Uri): ImportSource
}

class SafImportSources(private val resolver: ContentResolver) : ImportSources {

    override fun folder(treeUri: Uri): ImportSource = SafTreeSource(resolver, treeUri)

    override fun file(uri: Uri): ImportSource = SafFileSource(resolver, uri)
}
