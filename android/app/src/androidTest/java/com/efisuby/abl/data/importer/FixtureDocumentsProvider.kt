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
class FixtureDocumentsProvider(private val root: File) : ContentProvider() {

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

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY)

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
                    Document.COLUMN_SIZE -> if (file.isDirectory) null else file.length()
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
        fun install(testContext: Context, appContext: Context, target: File): MockContentResolver {
            target.deleteRecursively()
            copyAssets(testContext, "fixtures", target)
            val provider = FixtureDocumentsProvider(target)
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
