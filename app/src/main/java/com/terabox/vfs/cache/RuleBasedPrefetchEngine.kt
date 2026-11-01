package com.terabox.vfs.cache

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import kotlin.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.terabox.vfs.data.CloudSSDDatabaseHelper
import com.terabox.vfs.data.FileEntity
import com.terabox.vfs.data.GoogleDriveApiClient
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
class RuleBasedPrefetchEngine(
    private val context: Context,
    private val cacheManager: CloudSSDCacheManager,
    private val dbHelper: CloudSSDDatabaseHelper
) {
    companion object {
        const val PREFETCH_SIZE_BYTES = 15L * 1024L * 1024L // First 15 MB
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activePrefetchJob: Job? = null
    private var currentPrefetchedFileId: String? = null

    /**
     * Called when a media file begins playback.
     * Identifies the next item in the same folder and pre-buffers its first 15 MB.
     */
    fun onMediaStarted(
        currentFile: FileEntity,
        folderItems: List<FileEntity>,
        accessToken: String
    ) {
        // Cancel any existing prefetch job immediately (FR-F3)
        cancelActivePrefetch()

        if (!dbHelper.isPrefetchEnabled()) return

        // Verify prefetch conditions (Wi-Fi, Battery > 20%)
        if (!meetsPrefetchConditions()) return

        // Find the next media item in the playlist/folder
        val mediaOnlyList = folderItems.filter { !it.isFolder && (it.isVideo || it.isAudio) }
        val currentIndex = mediaOnlyList.indexOfFirst { it.id == currentFile.id }
        if (currentIndex < 0 || currentIndex >= mediaOnlyList.size - 1) {
            // No next media item
            return
        }

        val nextFile = mediaOnlyList[currentIndex + 1]

        // Skip if already pinned locally
        if (dbHelper.isPinned(nextFile.id)) return

        currentPrefetchedFileId = nextFile.id

        activePrefetchJob = scope.launch {
            try {
                val streamUrl = GoogleDriveApiClient.BASE_URL + "/files/${nextFile.id}?alt=media"
                val cacheKey = cacheManager.buildCacheKey(nextFile.id, nextFile.md5Checksum)
                val prefetchLength = if (nextFile.size in 1..PREFETCH_SIZE_BYTES) nextFile.size else PREFETCH_SIZE_BYTES

                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(GoogleDriveApiClient.USER_AGENT)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(30000)
                    .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $accessToken"))

                val cacheDataSource = CacheDataSource.Factory()
                    .setCache(cacheManager.simpleCache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()

                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(streamUrl))
                    .setPosition(0)
                    .setLength(prefetchLength)
                    .setKey(cacheKey)
                    .build()

                val cacheWriter = CacheWriter(cacheDataSource, dataSpec, null, null)
                cacheWriter.cache()
            } catch (_: CancellationException) {
                // Normal cancellation on seek or song change
            } catch (_: Exception) {
                // Non-fatal prefetch failure
            }
        }
    }

    /**
     * Cancels any active prefetch job immediately when the user seeks or switches items (FR-F3).
     */
    fun cancelActivePrefetch() {
        activePrefetchJob?.cancel()
        activePrefetchJob = null
        currentPrefetchedFileId = null
    }

    private fun meetsPrefetchConditions(): Boolean {
        // 1. Wi-Fi Check (if configured)
        if (dbHelper.isWifiOnlyPrefetch()) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val capabilities = cm?.getNetworkCapabilities(network)
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            if (!isWifi) return false
        }

        // 2. Battery Check (> 20%)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level > 0 && scale > 0) {
            val batteryPct = (level * 100) / scale
            if (batteryPct <= 20) return false
        }

        return true
    }
}
