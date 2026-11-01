package com.terabox.vfs.cache

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.RandomAccessFile

class LruBlockCacheManager(private val context: Context) {

    companion object {
        const val BLOCK_SIZE = 2 * 1024 * 1024 // 2MB block chunking
        const val PREF_NAME = "terabox_vfs_cache_prefs"
        const val KEY_MAX_CACHE_GB = "max_cache_gb"
        const val DEFAULT_MAX_CACHE_GB = 5L
    }

    private val cacheDir = File(context.filesDir, "vfs_cache").apply {
        if (!exists()) mkdirs()
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getMaxCacheSizeBytes(): Long {
        val gb = prefs.getLong(KEY_MAX_CACHE_GB, DEFAULT_MAX_CACHE_GB)
        return gb * 1024L * 1024L * 1024L
    }

    fun setMaxCacheSizeGb(gb: Long) {
        prefs.edit().putLong(KEY_MAX_CACHE_GB, gb).apply()
    }

    private fun getBlockFile(fsId: Long, blockIndex: Long): File {
        return File(cacheDir, "${fsId}_${blockIndex}.bin")
    }

    fun hasBlock(fsId: Long, blockIndex: Long): Boolean {
        val file = getBlockFile(fsId, blockIndex)
        return file.exists() && file.length() > 0
    }

    fun readBlockSlice(
        fsId: Long,
        blockIndex: Long,
        offsetInBlock: Int,
        length: Int,
        dest: ByteArray,
        destOffset: Int
    ): Int {
        val file = getBlockFile(fsId, blockIndex)
        if (!file.exists()) return -1

        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offsetInBlock.toLong())
                val bytesRead = raf.read(dest, destOffset, length)
                file.setLastModified(System.currentTimeMillis())
                bytesRead
            }
        } catch (e: Exception) {
            -1
        }
    }

    fun saveBlock(fsId: Long, blockIndex: Long, data: ByteArray): Boolean {
        val file = getBlockFile(fsId, blockIndex)
        return try {
            file.writeBytes(data)
            checkAndEvictOldBlocks()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getTotalCacheSizeBytes(): Long {
        var total = 0L
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile) total += file.length()
        }
        return total
    }

    fun clearAllUnpinnedCache(pinnedFsIds: Set<Long>) {
        cacheDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.endsWith(".bin")) {
                val fsIdStr = name.substringBefore("_")
                val fsId = fsIdStr.toLongOrNull()
                if (fsId == null || !pinnedFsIds.contains(fsId)) {
                    file.delete()
                }
            }
        }
    }

    private fun checkAndEvictOldBlocks() {
        val maxBudget = getMaxCacheSizeBytes()
        var currentSize = getTotalCacheSizeBytes()
        if (currentSize <= maxBudget) return

        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        for (file in files) {
            if (currentSize <= maxBudget * 0.85) break // Evict until 85% of budget
            val size = file.length()
            if (file.delete()) {
                currentSize -= size
            }
        }
    }
}
