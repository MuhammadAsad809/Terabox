package com.terabox.vfs.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.terabox.vfs.cache.CloudSSDCacheManager
import com.terabox.vfs.cache.RuleBasedPrefetchEngine
import com.terabox.vfs.data.CloudSSDDatabaseHelper
import com.terabox.vfs.data.FileEntity
import com.terabox.vfs.data.GoogleDriveApiClient
import kotlinx.coroutines.*
import java.io.File

@OptIn(UnstableApi::class)
class CloudSSDPlayerManager(
    private val context: Context,
    private val cacheManager: CloudSSDCacheManager,
    private val dbHelper: CloudSSDDatabaseHelper,
    private val prefetchEngine: RuleBasedPrefetchEngine
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressTrackingJob: Job? = null

    var currentFile: FileEntity? = null
        private set

    val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        startProgressTracking()
                    } else if (playbackState == Player.STATE_ENDED) {
                        stopProgressTracking()
                        currentFile?.let { dbHelper.savePlaybackPosition(it.id, 0L, duration) }
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        prefetchEngine.cancelActivePrefetch()
                    }
                }
            })
        }
    }

    /**
     * Prepares and starts playback for a media file (video or audio).
     * If the file is pinned locally, plays instantly from disk;
     * otherwise streams via HTTP Range requests with SimpleCache buffering.
     */
    fun playMedia(
        file: FileEntity,
        folderItems: List<FileEntity>,
        accessToken: String
    ) {
        currentFile = file
        prefetchEngine.cancelActivePrefetch()

        val isPinned = dbHelper.isPinned(file.id)
        val localPinnedFile = File(cacheManager.pinnedDir, "${file.id}_${file.name}")

        val mediaSourceFactory = if (isPinned && localPinnedFile.exists()) {
            DefaultMediaSourceFactory(context)
        } else {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(GoogleDriveApiClient.USER_AGENT)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $accessToken"))

            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cacheManager.simpleCache)
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            DefaultMediaSourceFactory(context).setDataSourceFactory(cacheDataSourceFactory)
        }

        val uri = if (isPinned && localPinnedFile.exists()) {
            Uri.fromFile(localPinnedFile)
        } else {
            Uri.parse(GoogleDriveApiClient.BASE_URL + "/files/${file.id}?alt=media")
        }

        val cacheKey = cacheManager.buildCacheKey(file.id, file.md5Checksum)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(file.id)
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(file.name)
                    .setDisplayTitle(file.name)
                    .build()
            )
            .build()

        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()

        // Resume from last saved playback position
        val lastPosition = dbHelper.getPlaybackPosition(file.id)
        if (lastPosition > 0L) {
            exoPlayer.seekTo(lastPosition)
        }

        exoPlayer.playWhenReady = true

        // Trigger next-file prefetching in background
        prefetchEngine.onMediaStarted(file, folderItems, accessToken)
    }

    fun setSpeed(speed: Float) {
        exoPlayer.setPlaybackParameters(PlaybackParameters(speed))
    }

    fun seekForward(offsetMs: Long = 10000L) {
        val newPos = (exoPlayer.currentPosition + offsetMs).coerceAtMost(exoPlayer.duration)
        exoPlayer.seekTo(newPos)
        prefetchEngine.cancelActivePrefetch()
    }

    fun seekBackward(offsetMs: Long = 10000L) {
        val newPos = (exoPlayer.currentPosition - offsetMs).coerceAtLeast(0L)
        exoPlayer.seekTo(newPos)
        prefetchEngine.cancelActivePrefetch()
    }

    fun pause() {
        exoPlayer.pause()
        saveCurrentPosition()
    }

    fun resume() {
        exoPlayer.play()
    }

    fun release() {
        saveCurrentPosition()
        stopProgressTracking()
        exoPlayer.release()
    }

    private fun startProgressTracking() {
        progressTrackingJob?.cancel()
        progressTrackingJob = scope.launch {
            while (isActive) {
                saveCurrentPosition()
                delay(4000)
            }
        }
    }

    private fun stopProgressTracking() {
        progressTrackingJob?.cancel()
        progressTrackingJob = null
    }

    private fun saveCurrentPosition() {
        val file = currentFile ?: return
        val pos = exoPlayer.currentPosition
        val dur = exoPlayer.duration
        if (pos > 0 && dur > 0) {
            dbHelper.savePlaybackPosition(file.id, pos, dur)
        }
    }
}
