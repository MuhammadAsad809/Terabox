package com.terabox.vfs.cache

import android.content.Context
import kotlin.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.terabox.vfs.data.CloudSSDDatabaseHelper
import java.io.File

@OptIn(UnstableApi::class)
class CloudSSDCacheManager(private val context: Context, private val dbHelper: CloudSSDDatabaseHelper) {

    private val cacheDir: File = File(context.cacheDir, "cloudssd_media_cache").apply { mkdirs() }
    val pinnedDir: File = File(context.filesDir, "pinned_media").apply { mkdirs() }

    private var currentCapBytes: Long = (dbHelper.getCacheCapGb() * 1024L * 1024L * 1024L).toLong()
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private var evictor = LeastRecentlyUsedCacheEvictor(currentCapBytes)

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    val simpleCache: SimpleCache
        @Synchronized
        get() {
            if (simpleCacheInstance == null) {
                simpleCacheInstance = SimpleCache(cacheDir, evictor, databaseProvider)
            }
            return simpleCacheInstance!!
        }

    /**
     * Dynamically updates the LRU cache limit (between 1 GB and 20 GB).
     */
    @Synchronized
    fun setCacheCapGb(capGb: Float) {
        val safeCap = capGb.coerceIn(1.0f, 20.0f)
        dbHelper.setCacheCapGb(safeCap)
        currentCapBytes = (safeCap * 1024L * 1024L * 1024L).toLong()

        // Recreate evictor with new cap
        try {
            simpleCacheInstance?.release()
        } catch (_: Exception) {}
        evictor = LeastRecentlyUsedCacheEvictor(currentCapBytes)
        simpleCacheInstance = SimpleCache(cacheDir, evictor, databaseProvider)
    }

    fun getCacheCapGb(): Float = dbHelper.getCacheCapGb()

    fun getCacheCapBytes(): Long = currentCapBytes

    /**
     * Returns total bytes currently stored in the LRU cache.
     */
    fun getCurrentLruCacheSizeBytes(): Long {
        return try {
            simpleCache.cacheSpace
        } catch (_: Exception) {
            getFolderSize(cacheDir)
        }
    }

    /**
     * Returns total bytes stored in pinned offline files.
     */
    fun getTotalPinnedSizeBytes(): Long {
        return dbHelper.getTotalPinnedSizeBytes()
    }

    /**
     * Clears all unpinned LRU cached chunks from device storage.
     */
    @Synchronized
    fun clearLruCache() {
        try {
            simpleCacheInstance?.let { cache ->
                val keys = cache.keys
                for (key in keys) {
                    val spans = cache.getCachedSpans(key)
                    for (span in spans) {
                        cache.removeSpan(span)
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback: delete files inside cacheDir
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * Generates a stable cache key incorporating fileId and md5Checksum.
     * When remote file is updated on Drive, its checksum changes, invalidating old chunks.
     */
    fun buildCacheKey(fileId: String, md5Checksum: String?): String {
        val hash = md5Checksum?.ifBlank { "v1" } ?: "v1"
        return "cloudssd_${fileId}_$hash"
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }
}
