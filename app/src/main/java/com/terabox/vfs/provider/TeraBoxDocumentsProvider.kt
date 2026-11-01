package com.terabox.vfs.provider

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.terabox.vfs.R
import com.terabox.vfs.TeraBoxApp
import com.terabox.vfs.data.AccountModel
import com.terabox.vfs.data.FileNodeModel
import com.terabox.vfs.data.TeraBoxApiClient
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class TeraBoxDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.terabox.vfs.documents"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            DocumentsContract.Root.COLUMN_CAPACITY_BYTES,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
    }

    private val apiClient = TeraBoxApiClient()
    private val handlerThread = HandlerThread("VfsProxyThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val app = TeraBoxApp.instance
        val accounts = app.dbHelper.getAccounts().filter { it.active }

        for (account in accounts) {
            val row = result.newRow()
            val freeBytes = if (account.totalQuota > account.usedQuota) account.totalQuota - account.usedQuota else 0L

            row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "root_${account.id}")
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, buildDocId(account.id, 0L))
            row.add(DocumentsContract.Root.COLUMN_TITLE, "TeraBox (${account.name})")
            row.add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                        DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            )
            row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, freeBytes)
            row.add(DocumentsContract.Root.COLUMN_CAPACITY_BYTES, account.totalQuota)
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (accountId, fsId) = parseDocId(documentId)
        val app = TeraBoxApp.instance

        if (fsId == 0L) {
            // Root virtual folder
            includeRootDocument(result, documentId)
        } else {
            val node = app.dbHelper.getNode(fsId)
                ?: throw FileNotFoundException("Document not found: $documentId")
            includeFileDocument(result, node)
        }

        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (accountId, fsId) = parseDocId(parentDocumentId)
        val app = TeraBoxApp.instance

        val parentPath = if (fsId == 0L) {
            "/"
        } else {
            val parentNode = app.dbHelper.getNode(fsId)
            parentNode?.path ?: "/"
        }

        val children = app.dbHelper.getChildren(accountId, parentPath)
        for (node in children) {
            includeFileDocument(result, node)
        }

        return result
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<String>?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val accountId = rootId.removePrefix("root_")
        val app = TeraBoxApp.instance

        val matches = app.dbHelper.searchNodes(accountId, query)
        for (node in matches) {
            includeFileDocument(result, node)
        }

        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val (accountId, fsId) = parseDocId(documentId)
        val app = TeraBoxApp.instance
        val account = app.dbHelper.getAccount(accountId)
            ?: throw FileNotFoundException("Account not found for: $documentId")

        if (mode == "r" || mode == "rt") {
            val node = app.dbHelper.getNode(fsId)
                ?: throw FileNotFoundException("File not found: $documentId")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val storageManager = context?.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                    ?: throw IOException("StorageManager unavailable")

                val callback = TeraBoxProxyCallback(
                    account = account,
                    node = node,
                    apiClient = apiClient,
                    dbHelper = app.dbHelper,
                    cacheManager = app.cacheManager
                )

                return storageManager.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    callback,
                    handler
                )
            } else {
                throw IOException("Virtual Cloud Storage requires Android 8.0+ (API 26)")
            }
        } else {
            // Staged write mode
            val stagingDir = File(context?.cacheDir, "staged_uploads").apply { mkdirs() }
            val stagedFile = File(stagingDir, "stage_${fsId}_${System.currentTimeMillis()}.tmp")
            return ParcelFileDescriptor.open(
                stagedFile,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            )
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val (accountId, _) = parseDocId(parentDocumentId)
        val tempFsId = System.currentTimeMillis()
        return buildDocId(accountId, tempFsId)
    }

    override fun deleteDocument(documentId: String) {
        val (_, fsId) = parseDocId(documentId)
        val app = TeraBoxApp.instance
        val node = app.dbHelper.getNode(fsId) ?: return
        // Staged for deletion
    }

    private fun includeRootDocument(result: MatrixCursor, docId: String) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "TeraBox Virtual Drive")
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
        row.add(
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        )
    }

    private fun includeFileDocument(result: MatrixCursor, node: FileNodeModel) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, buildDocId(node.accountId, node.fsId))
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, node.filename)

        val mime = if (node.isDir) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            getMimeTypeForName(node.filename)
        }
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
        row.add(DocumentsContract.Document.COLUMN_SIZE, node.size)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, node.mtime * 1000L)

        var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME

        if (node.isDir) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            if (mime.startsWith("image/") || mime.startsWith("video/")) {
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
            }
        }

        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
    }

    private fun getMimeTypeForName(name: String): String {
        val ext = name.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
    }

    private fun buildDocId(accountId: String, fsId: Long): String = "doc_${accountId}_$fsId"

    private fun parseDocId(documentId: String): Pair<String, Long> {
        val parts = documentId.split("_")
        if (parts.size >= 3) {
            val accountId = parts[1]
            val fsId = parts[2].toLongOrNull() ?: 0L
            return Pair(accountId, fsId)
        }
        return Pair("default", 0L)
    }
}
