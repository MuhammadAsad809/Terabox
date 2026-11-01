package com.terabox.vfs.sync

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.terabox.vfs.TeraBoxApp
import com.terabox.vfs.data.FileNodeModel
import com.terabox.vfs.data.TeraBoxApiClient
import com.terabox.vfs.data.TeraBoxDatabaseHelper
import com.terabox.vfs.data.TeraBoxSessionExpiredException
import com.terabox.vfs.provider.TeraBoxDocumentsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedList
import java.util.Queue

class AccountSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AccountSyncWorker"
    }

    private val apiClient = TeraBoxApiClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = TeraBoxApp.instance
        val accounts = app.dbHelper.getAccounts().filter { it.active }

        for (account in accounts) {
            try {
                // 1. Sync quota
                val quotaResult = apiClient.getQuota(account.ndus)
                if (quotaResult.isSuccess) {
                    val quota = quotaResult.getOrThrow()
                    val updatedAccount = account.copy(
                        totalQuota = quota.total,
                        usedQuota = quota.used
                    )
                    app.dbHelper.upsertAccount(updatedAccount)
                } else if (quotaResult.exceptionOrNull() is TeraBoxSessionExpiredException) {
                    Log.w(TAG, "TeraBox session expired for account ${account.name}")
                    app.dbHelper.setAccountActive(account.id, false)
                    continue
                }

                // 2. Recursively crawl directories starting from root "/"
                val dirQueue: Queue<String> = LinkedList()
                dirQueue.add("/")

                while (!dirQueue.isEmpty()) {
                    val currentDir = dirQueue.poll() ?: break
                    val listResult = apiClient.listFiles(account.ndus, currentDir)

                    if (listResult.isSuccess) {
                        val items = listResult.getOrDefault(emptyList())
                        val normalizedParent = TeraBoxDatabaseHelper.normalizeParentPath(currentDir)

                        val nodes = items.map { item ->
                            if (item.isdir == 1) {
                                dirQueue.add(item.path)
                            }

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

                        app.dbHelper.insertOrUpdateNodes(nodes)
                    } else if (listResult.exceptionOrNull() is TeraBoxSessionExpiredException) {
                        Log.w(TAG, "Session expired while crawling $currentDir")
                        app.dbHelper.setAccountActive(account.id, false)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing account ${account.name}: ${e.message}")
            }
        }

        // Notify Android system that file trees have updated
        applicationContext.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(TeraBoxDocumentsProvider.AUTHORITY),
            null
        )

        Result.success()
    }
}
