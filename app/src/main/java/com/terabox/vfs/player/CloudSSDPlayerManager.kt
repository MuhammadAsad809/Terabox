package com.terabox.vfs.player

import android.content.Context
import android.net.Uri
import kotlin.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.terabox.vfs.cache.CloudSSDCacheManager
import com.terabox.vfs.cache.RuleBasedPrefetchEngine
import com.terabox.vfs.data.CloudSSDDatabaseHelper
import com.terabox.vfs.data.FileEntity
import com.terabox.vfs.data.GoogleDriveApiClient
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File

@OptIn(UnstableApi::class)
class CloudSSDPlayerManager(
    private val context: Context,
    private val cacheManager: CloudSSDCacheManager,
    private val dbHelper: CloudSSDDatabaseHelper,
    private val prefetchEngine: RuleBasedPrefetchEngine,
    private val httpClient: OkHttpClient
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressTrackingJob: Job? = null
    private var scheduledPrefetchJob: Job? = null
    private var pendingFolderItems: List<FileEntity> = emptyList()
    private var pendingAccessToken: String = ""

    var currentFile: FileEntity? = null
        private set

    val exoPlayer: ExoPlayer by lazy {
        // Ultra-low latency LoadControl: starts playback within 500ms of initial data
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 45_000,
                /* bufferForPlaybackMs = */ 500,              // 0.5 sec instant start! (Default was 2500ms)
                /* bufferForPlaybackAfterRebufferMs = */ 1_000 // 1.0 sec fast rebuffer! (Default was 5000ms)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10_000, false)
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            startProgressTracking()
                            // Delay next-item prefetch by 4s so current video gets 100% of bandwidth
                            scheduledPrefetchJob?.cancel()
                            scheduledPrefetchJob = scope.launch {
                                delay(4000)
                                val f = currentFile
                                if (f != null && isPlaying) {
                                    prefetchEngine.onMediaStarted(f, pendingFolderItems, pendingAccessToken)
                                }
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            stopProgressTracking()
                            scheduledPrefetchJob?.cancel()
                            currentFile?.let { dbHelper.savePlaybackPosition(it.id, 0L, duration) }
                        }
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            scheduledPrefetchJob?.cancel()
                            prefetchEngine.cancelActivePrefetch()
                        }
                    }
                })
            }
    }

    /**
     * Prepares and starts playback for a media file (video or audio).
     * If the file is pinned locally, plays instantly from disk;
     * otherwise streams via OkHttp HTTP/2 Range requests with SimpleCache buffering.
     */
    fun playMedia(
        file: FileEntity,
        folderItems: List<FileEntity>,
        accessToken: String
    ) {
        currentFile = file
        pendingFolderItems = folderItems
        pendingAccessToken = accessToken

        // Cancel any background prefetch immediately so 100% bandwidth is given to starting this video
        scheduledPrefetchJob?.cancel()
        prefetchEngine.cancelActivePrefetch()

        val isPinned = dbHelper.isPinned(file.id)
        val localPinnedFile = File(cacheManager.pinnedDir, "${file.id}_${file.name}")

        val mediaSourceFactory = if (isPinned && localPinnedFile.exists()) {
            DefaultMediaSourceFactory(context)
        } else {
            // High-speed OkHttp DataSource sharing connection pools & HTTP/2 multiplexing
            val httpDataSourceFactory = OkHttpDataSource.Factory(httpClient)
                .setUserAgent(GoogleDriveApiClient.USER_AGENT)
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $accessToken"))

            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cacheManager.simpleCache)
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val extractorsFactory = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)

            DefaultMediaSourceFactory(context, extractorsFactory)
                .setDataSourceFactory(cacheDataSourceFactory)
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
    }

    fun setSpeed(speed: Float) {
        exoPlayer.setPlaybackParameters(PlaybackParameters(speed))
    }

    fun seekForward(offsetMs: Long = 10000L) {
        val newPos = (exoPlayer.currentPosition + offsetMs).coerceAtMost(exoPlayer.duration)
        exoPlayer.seekTo(newPos)
        scheduledPrefetchJob?.cancel()
        prefetchEngine.cancelActivePrefetch()
    }

    fun seekBackward(offsetMs: Long = 10000L) {
        val newPos = (exoPlayer.currentPosition - offsetMs).coerceAtLeast(0L)
        exoPlayer.seekTo(newPos)
        scheduledPrefetchJob?.cancel()
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
        scheduledPrefetchJob?.cancel()
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
