package com.terabox.vfs.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.terabox.vfs.TeraBoxApp
import com.terabox.vfs.data.FileEntity
import com.terabox.vfs.data.GoogleDriveApiClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

class CloudSSDDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.terabox.vfs.documents"
        const val ROOT_ID = "cloudssd_drive_root"
        const val DOC_ID_ROOT = "root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_CAPACITY_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )
    }

    private val app by lazy { TeraBoxApp.instance }
    private var handlerThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    override fun onCreate(): Boolean {
        handlerThread = HandlerThread("CloudSSD-ProxyFD").apply { start() }
        bgHandler = Handler(handlerThread!!.looper)
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val activeAccount = app.dbHelper.getActiveAccount()

        val capacity = activeAccount?.totalQuota ?: (15L * 1024L * 1024L * 1024L)
        val used = activeAccount?.usedQuota ?: 0L
        val available = (capacity - used).coerceAtLeast(0L)

        val row = result.newRow()
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(Root.COLUMN_DOCUMENT_ID, DOC_ID_ROOT)
        row.add(Root.COLUMN_TITLE, "CloudSSD Google Drive")
        row.add(
            Root.COLUMN_SUMMARY,
            if (activeAccount != null) "${activeAccount.displayName} (Virtual Storage)" else "Sign in to connect Drive"
        )
        row.add(
            Root.COLUMN_FLAGS,
            Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_RECENTS or Root.FLAG_SUPPORTS_SEARCH
        )
        row.add(Root.COLUMN_MIME_TYPES, "*/*")
        row.add(Root.COLUMN_AVAILABLE_BYTES, available)
        row.add(Root.COLUMN_CAPACITY_BYTES, capacity)
        row.add(Root.COLUMN_ICON, android.R.drawable.stat_sys_download_done)

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        if (documentId == DOC_ID_ROOT) {
            val row = result.newRow()
            row.add(Document.COLUMN_DOCUMENT_ID, DOC_ID_ROOT)
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            row.add(Document.COLUMN_DISPLAY_NAME, "CloudSSD Media")
            row.add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_GRID)
            row.add(Document.COLUMN_SIZE, 0L)
            return result
        }

        val file = app.dbHelper.getFile(documentId) ?: throw FileNotFoundException("Document $documentId not found")
        includeFile(result, file)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val activeAccount = app.dbHelper.getActiveAccount() ?: return result

        val localChildren = app.dbHelper.getChildren(parentDocumentId)
        if (localChildren.isNotEmpty()) {
            for (child in localChildren) {
                includeFile(result, child)
            }
        } else {
            // Fetch live from Google Drive API
            runBlocking {
                try {
                    val listRes = app.apiClient.listFiles(activeAccount.accessToken, parentDocumentId, mediaOnly = false)
                    listRes.getOrNull()?.files?.let { remoteFiles ->
                        val entities = remoteFiles.map { rf ->
                            FileEntity(
                                id = rf.id,
                                name = rf.name,
                                mimeType = rf.mimeType,
                                parentId = parentDocumentId,
                                size = rf.size,
                                modifiedTime = System.currentTimeMillis(),
                                md5Checksum = rf.md5Checksum,
                                thumbnailLink = rf.thumbnailLink,
                                isFolder = rf.isFolder
                            )
                        }
                        app.dbHelper.upsertFiles(entities)
                        for (entity in entities) {
                            includeFile(result, entity)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = app.dbHelper.getFile(documentId) ?: throw FileNotFoundException("File not found")

        // 1. If pinned locally, return direct file descriptor
        val pinnedFile = File(app.cacheManager.pinnedDir, "${file.id}_${file.name}")
        if (pinnedFile.exists() && pinnedFile.length() > 0) {
            return ParcelFileDescriptor.open(pinnedFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // 2. Otherwise serve through StorageManager.openProxyFileDescriptor (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val storageManager = context?.getSystemService(StorageManager::class.java)
                ?: throw FileNotFoundException("StorageManager not available")

            val activeAccount = app.dbHelper.getActiveAccount()
                ?: throw FileNotFoundException("No active Google Drive account")

            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = file.size

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset >= file.size) return 0
                    val endByte = (offset + size - 1).coerceAtMost(file.size - 1)

                    return runBlocking {
                        val res = app.apiClient.fetchMediaRange(
                            accessToken = activeAccount.accessToken,
                            fileId = file.id,
                            startByte = offset,
                            endByte = endByte
                        )
                        val bytes = res.getOrNull()
                        if (bytes != null && bytes.isNotEmpty()) {
                            System.arraycopy(bytes, 0, data, 0, bytes.size)
                            bytes.size
                        } else {
                            throw ErrnoException("onRead", OsConstants.EIO)
                        }
                    }
                }

                override fun onRelease() {}
            }

            return storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                callback,
                bgHandler
            )
        }

        throw FileNotFoundException("Remote proxy descriptors require Android 8.0+")
    }

    override fun getDocumentType(documentId: String): String {
        if (documentId == DOC_ID_ROOT) return Document.MIME_TYPE_DIR
        return app.dbHelper.getFile(documentId)?.mimeType ?: "application/octet-stream"
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current: FileEntity? = app.dbHelper.getFile(documentId)
        while (current != null) {
            if (current.parentId == parentDocumentId) return true
            current = app.dbHelper.getFile(current.parentId)
        }
        return false
    }

    private fun includeFile(result: MatrixCursor, file: FileEntity) {
        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, file.id)
        row.add(Document.COLUMN_DISPLAY_NAME, file.name)
        row.add(Document.COLUMN_SIZE, file.size)
        row.add(Document.COLUMN_MIME_TYPE, if (file.isFolder) Document.MIME_TYPE_DIR else file.mimeType)
        row.add(Document.COLUMN_LAST_MODIFIED, file.modifiedTime)

        var flags = 0
        if (file.isFolder) {
            flags = flags or Document.FLAG_DIR_PREFERS_GRID
        }
        row.add(Document.COLUMN_FLAGS, flags)
    }
}
