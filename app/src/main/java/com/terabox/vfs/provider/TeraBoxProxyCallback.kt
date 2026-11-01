package com.terabox.vfs.provider

import android.os.Build
import android.os.ProxyFileDescriptorCallback
import androidx.annotation.RequiresApi
import com.terabox.vfs.cache.LruBlockCacheManager
import com.terabox.vfs.data.AccountModel
import com.terabox.vfs.data.FileNodeModel
import com.terabox.vfs.data.TeraBoxApiClient
import com.terabox.vfs.data.TeraBoxDatabaseHelper
import java.io.IOException

@RequiresApi(Build.VERSION_CODES.O)
class TeraBoxProxyCallback(
    private val account: AccountModel,
    private var node: FileNodeModel,
    private val apiClient: TeraBoxApiClient,
    private val dbHelper: TeraBoxDatabaseHelper,
    private val cacheManager: LruBlockCacheManager
) : ProxyFileDescriptorCallback() {

    private var currentDlink: String? = node.dlink

    override fun onGetSize(): Long {
        return node.size
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (offset >= node.size) return 0

        val bytesToRead = minOf(size.toLong(), node.size - offset).toInt()
        var currentOffset = offset
        var remainingBytes = bytesToRead
        var destOffset = 0

        while (remainingBytes > 0) {
            val blockIndex = currentOffset / LruBlockCacheManager.BLOCK_SIZE
            val offsetInBlock = (currentOffset % LruBlockCacheManager.BLOCK_SIZE).toInt()
            val remainingInBlock = LruBlockCacheManager.BLOCK_SIZE - offsetInBlock
            val sliceLength = minOf(remainingBytes, remainingInBlock)

            // 1. Attempt read from local LRU cache
            var bytesFromCache = cacheManager.readBlockSlice(
                fsId = node.fsId,
                blockIndex = blockIndex,
                offsetInBlock = offsetInBlock,
                length = sliceLength,
                dest = data,
                destOffset = destOffset
            )

            // 2. Cache miss -> stream block from TeraBox CDN
            if (bytesFromCache < 0) {
                val blockStart = blockIndex * LruBlockCacheManager.BLOCK_SIZE
                val blockEnd = minOf(blockStart + LruBlockCacheManager.BLOCK_SIZE - 1, node.size - 1)
                
                val downloadedBlock = fetchBlockWithRetry(blockStart, blockEnd)
                    ?: throw IOException("Failed to stream block $blockIndex for file ${node.filename}")

                // Save block to LRU cache
                cacheManager.saveBlock(node.fsId, blockIndex, downloadedBlock)

                // Copy slice into output buffer
                System.arraycopy(downloadedBlock, offsetInBlock, data, destOffset, sliceLength)
                bytesFromCache = sliceLength
            }

            currentOffset += bytesFromCache
            destOffset += bytesFromCache
            remainingBytes -= bytesFromCache
        }

        return bytesToRead
    }

    override fun onRelease() {
        // Stream session complete
    }

    /**
     * Fetches a chunk with reactive 401/403 token re-resolution
     */
    private fun fetchBlockWithRetry(startByte: Long, endByte: Long): ByteArray? {
        // 1. Ensure we have an active download link
        if (currentDlink.isNullOrEmpty()) {
            val linkResult = apiClient.getDownloadLink(account.ndus, node.fsId)
            currentDlink = linkResult.getOrNull()
            currentDlink?.let { dbHelper.updateDlink(node.fsId, it) }
        }

        val dlink = currentDlink ?: return null

        // 2. First attempt
        val firstAttempt = apiClient.fetchRange(dlink, account.ndus, startByte, endByte)
        if (firstAttempt.isSuccess) {
            return firstAttempt.getOrNull()
        }

        // 3. Link may have expired (HTTP 401/403/410) -> Re-resolve fresh link
        val refreshResult = apiClient.getDownloadLink(account.ndus, node.fsId)
        val freshDlink = refreshResult.getOrNull() ?: return null
        currentDlink = freshDlink
        dbHelper.updateDlink(node.fsId, freshDlink)

        // 4. Retry with fresh link
        val retryAttempt = apiClient.fetchRange(freshDlink, account.ndus, startByte, endByte)
        return retryAttempt.getOrNull()
    }
}
