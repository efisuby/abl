package com.efisuby.abl.data.importer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.test.mock.MockContentResolver
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal documents-contract provider over a local directory, so the import tests walk a tree
 * through the same `tree/…/document/…/children` URIs the system picker hands out. Document ids
 * are paths relative to [root].
 */
class FixtureDocumentsProvider(private val root: File, private val mode: Mode = Mode()) : ContentProvider() {

    /**
     * Quirks of cloud providers such as Google Drive; change them between steps of a test.
     * All are off by default.
     */
    class Mode {
        /** The size column is null, as Drive reports it for some files. */
        @Volatile var sizeUnknown = false

        /** Reading a file fails with an I/O error after this many bytes (a dropped connection). */
        @Volatile var failAfterBytes: Long? = null

        /** Reading a file ends cleanly after this many bytes although it is larger. */
        @Volatile var endEarlyAfterBytes: Long? = null

        /** Opening a document throws `SecurityException`, as after access was revoked. */
        @Volatile var denyOpen = false

        /** Pause before each 8 KiB chunk. */
        @Volatile var chunkDelayMs = 0L

        internal val streaming get() = failAfterBytes != null || endEarlyAfterBytes != null || chunkDelayMs > 0
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: DEFAULT_PROJECTION
        val cursor = MatrixCursor(columns)
        val isChildren = uri.pathSegments.lastOrNull() == "children"
        val documentId = DocumentsContract.getDocumentId(uri)
        val file = fileFor(documentId)
        if (isChildren) {
            file.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                cursor.addRow(columns, "$documentId/${child.name}", child)
            }
        } else {
            cursor.addRow(columns, documentId, file)
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (this.mode.denyOpen) throw SecurityException("Access to $uri was revoked")
        val file = fileFor(DocumentsContract.getDocumentId(uri))
        if (!this.mode.streaming) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        // Served through a pipe so that a slow or broken connection can be imitated.
        val pipe = ParcelFileDescriptor.createPipe()
        val settings = this.mode
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8 * 1024)
                        var sent = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            val allowed = listOfNotNull(settings.failAfterBytes, settings.endEarlyAfterBytes).minOrNull()
                            val chunk = if (allowed == null) read else minOf(read.toLong(), allowed - sent).toInt()
                            if (chunk > 0) out.write(buffer, 0, chunk)
                            sent += chunk
                            if (allowed != null && sent >= allowed) {
                                if (settings.failAfterBytes != null) pipe[1].closeWithError("connection lost")
                                break
                            }
                            if (settings.chunkDelayMs > 0) Thread.sleep(settings.chunkDelayMs)
                        }
                    }
                }
            } catch (e: Exception) {
                // The reader went away (cancelled import): nothing left to serve.
            }
        }.start()
        return pipe[0]
    }

    private fun fileFor(documentId: String): File {
        val file = File(root, documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun MatrixCursor.addRow(columns: Array<out String>, documentId: String, file: File) {
        addRow(
            columns.map { column ->
                when (column) {
                    Document.COLUMN_DOCUMENT_ID -> documentId
                    // Same column names as OpenableColumns, so single-document queries work too.
                    Document.COLUMN_DISPLAY_NAME -> file.name
                    Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else "audio/mpeg"
                    Document.COLUMN_SIZE -> if (file.isDirectory || mode.sizeUnknown) null else file.length()
                    else -> null
                }
            },
        )
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.efisuby.abl.fixtures"

        private val DEFAULT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )

        /**
         * Copies the `fixtures/` assets of the test APK into [target] and returns a resolver that
         * serves them under [AUTHORITY].
         */
        fun install(testContext: Context, appContext: Context, target: File, mode: Mode = Mode()): MockContentResolver {
            target.deleteRecursively()
            copyAssets(testContext, "fixtures", target)
            val provider = FixtureDocumentsProvider(target, mode)
            provider.attachInfo(appContext, ProviderInfo().apply { authority = AUTHORITY })
            return MockContentResolver(appContext).apply { addProvider(AUTHORITY, provider) }
        }

        fun treeUri(folder: String): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, folder)

        fun documentUri(path: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, path)

        private fun copyAssets(context: Context, path: String, target: File) {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                target.parentFile?.mkdirs()
                context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            } else {
                target.mkdirs()
                children.forEach { copyAssets(context, "$path/$it", File(target, it)) }
            }
        }
    }
}
