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
import android.util.Log
import android.webkit.MimeTypeMap
import com.terabox.vfs.R
import com.terabox.vfs.TeraBoxApp
import com.terabox.vfs.data.AccountModel
import com.terabox.vfs.data.FileNodeModel
import com.terabox.vfs.data.TeraBoxApiClient
import com.terabox.vfs.data.TeraBoxDatabaseHelper
import com.terabox.vfs.data.TeraBoxSessionExpiredException
import java.io.FileNotFoundException
import java.io.IOException

class TeraBoxDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.terabox.vfs.documents"
        private const val TAG = "TeraBoxDocumentsProvider"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
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

        fun buildDocId(accountId: String, fsId: Long): String = "doc:$accountId:$fsId"

        fun parseDocId(documentId: String): Pair<String, Long> {
            if (documentId.startsWith("doc:")) {
                val parts = documentId.split(":")
                if (parts.size >= 3) {
                    val accountId = parts[1]
                    val fsId = parts[2].toLongOrNull() ?: 0L
                    return Pair(accountId, fsId)
                }
            }
            // Fallback for legacy underscore-delimited format
            val trimmed = documentId.removePrefix("doc_")
            val lastUnderscore = trimmed.lastIndexOf('_')
            if (lastUnderscore > 0) {
                val accountId = trimmed.substring(0, lastUnderscore)
                val fsId = trimmed.substring(lastUnderscore + 1).toLongOrNull() ?: 0L
                return Pair(accountId, fsId)
            }
            return Pair("default", 0L)
        }
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
            row.add(DocumentsContract.Root.COLUMN_SUMMARY, "Cloud Storage")
            row.add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            )
            row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, freeBytes)
            row.add(DocumentsContract.Root.COLUMN_CAPACITY_BYTES, account.totalQuota)
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }

        // Inform Android system to watch for account changes
        result.setNotificationUri(
            context?.contentResolver,
            DocumentsContract.buildRootsUri(AUTHORITY)
        )

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val (accountId, fsId) = parseDocId(documentId)
        val app = TeraBoxApp.instance

        if (fsId == 0L) {
            val account = app.dbHelper.getAccount(accountId)
            val displayName = if (account != null) "TeraBox (${account.name})" else "TeraBox Cloud Drive"
            includeRootDocument(result, documentId, displayName)
        } else {
            val node = app.dbHelper.getNode(fsId)
                ?: throw FileNotFoundException("Document not found: $documentId")
            includeFileDocument(result, node)
        }

        result.setNotificationUri(
            context?.contentResolver,
            DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
        )

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
        val account = app.dbHelper.getAccount(accountId)

        val parentPath = if (fsId == 0L) {
            "/"
        } else {
            val parentNode = app.dbHelper.getNode(fsId)
            parentNode?.path ?: "/"
        }

        // 1. Check local indexed metadata
        var children = app.dbHelper.getChildren(accountId, parentPath)

        // 2. If local cache is empty and account is authenticated, fetch live from real TeraBox API
        if (children.isEmpty() && account != null && account.active) {
            try {
                val listResult = apiClient.listFiles(account.ndus, parentPath)
                if (listResult.isSuccess) {
                    val items = listResult.getOrDefault(emptyList())
                    val normalizedParent = TeraBoxDatabaseHelper.normalizeParentPath(parentPath)
                    val newNodes = items.map { item ->
                        FileNodeModel(
                            fsId = item.fsId,
                            accountId = account.id,
                            path = item.path,
                            parentPath = normalizedParent,
                            filename = item.filename,
                            isDir = item.isdir == 1,
                            size = item.size,
                            mtime = item.mtime,
                            dlink = item.dlink,
                            dlinkUpdatedAt = if (item.dlink != null) System.currentTimeMillis() else 0L,
                            isPinned = false
                        )
                    }
                    app.dbHelper.insertOrUpdateNodes(newNodes)
                    children = app.dbHelper.getChildren(accountId, parentPath)
                } else {
                    val error = listResult.exceptionOrNull()
                    if (error is TeraBoxSessionExpiredException) {
                        Log.w(TAG, "TeraBox session expired for account $accountId")
                        app.dbHelper.setAccountActive(accountId, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching live children for $parentPath: ${e.message}")
            }
        }

        for (node in children) {
            includeFileDocument(result, node)
        }

        result.setNotificationUri(
            context?.contentResolver,
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId)
        )

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

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentAcc, parentFsId) = parseDocId(parentDocumentId)
        val (childAcc, childFsId) = parseDocId(documentId)

        if (parentAcc != childAcc) return false
        if (parentDocumentId == documentId) return false

        // Root directory contains all items belonging to this account
        if (parentFsId == 0L) return true

        val app = TeraBoxApp.instance
        val parentNode = app.dbHelper.getNode(parentFsId) ?: return false
        val childNode = app.dbHelper.getNode(childFsId) ?: return false

        val parentPrefix = if (parentNode.path.endsWith("/")) parentNode.path else "${parentNode.path}/"
        return childNode.path.startsWith(parentPrefix)
    }

    override fun getDocumentType(documentId: String): String {
        val (_, fsId) = parseDocId(documentId)
        if (fsId == 0L) {
            return DocumentsContract.Document.MIME_TYPE_DIR
        }
        val app = TeraBoxApp.instance
        val node = app.dbHelper.getNode(fsId)
        return if (node?.isDir == true) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            getMimeTypeForName(node?.filename ?: "")
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode != "r" && mode != "rt") {
            throw UnsupportedOperationException("Read-only access: writing to virtual cloud storage is not supported in this phase")
        }

        val (accountId, fsId) = parseDocId(documentId)
        val app = TeraBoxApp.instance
        val account = app.dbHelper.getAccount(accountId)
            ?: throw FileNotFoundException("Account not found for: $documentId")

        val node = app.dbHelper.getNode(fsId)
            ?: throw FileNotFoundException("File not found: $documentId")

        if (node.isDir) {
            throw FileNotFoundException("Cannot open directory as a file: $documentId")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val storageManager = context?.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                ?: throw IOException("StorageManager unavailable on this device")

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
    }

    override fun deleteDocument(documentId: String) {
        val (accountId, fsId) = parseDocId(documentId)
        val app = TeraBoxApp.instance
        val account = app.dbHelper.getAccount(accountId) ?: return
        val node = app.dbHelper.getNode(fsId) ?: return

        try {
            val res = apiClient.deleteItem(account.ndus, node.path)
            if (res.isSuccess) {
                app.dbHelper.deleteNode(fsId)
                context?.contentResolver?.notifyChange(
                    DocumentsContract.buildRootsUri(AUTHORITY),
                    null
                )
            }
        } catch (e: Exception) {
            throw IOException("Failed to delete document: ${e.message}")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (accountId, fsId) = parseDocId(documentId)
        val app = TeraBoxApp.instance
        val account = app.dbHelper.getAccount(accountId) ?: throw FileNotFoundException("Account not found")
        val node = app.dbHelper.getNode(fsId) ?: throw FileNotFoundException("Document not found")

        try {
            val res = apiClient.renameItem(account.ndus, node.path, displayName)
            if (res.isSuccess) {
                val newPath = node.path.substringBeforeLast('/') + "/$displayName"
                app.dbHelper.renameNode(fsId, displayName, newPath)
                context?.contentResolver?.notifyChange(
                    DocumentsContract.buildRootsUri(AUTHORITY),
                    null
                )
                return documentId
            } else {
                throw IOException("Rename failed on remote storage")
            }
        } catch (e: Exception) {
            throw IOException("Failed to rename document: ${e.message}")
        }
    }

    private fun includeRootDocument(result: MatrixCursor, docId: String, title: String) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, title)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
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

        if (!node.isDir) {
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
}
