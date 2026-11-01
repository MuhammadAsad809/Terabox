package com.terabox.vfs

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.terabox.vfs.cache.CloudSSDCacheManager
import com.terabox.vfs.cache.RuleBasedPrefetchEngine
import com.terabox.vfs.data.CloudSSDDatabaseHelper
import com.terabox.vfs.data.GoogleDriveApiClient
import com.terabox.vfs.player.CloudSSDPlayerManager

class TeraBoxApp : Application() {

    companion object {
        const val CHANNEL_MEDIA = "channel_cloudssd_media"
        const val CHANNEL_PREFETCH = "channel_cloudssd_prefetch"

        lateinit var instance: TeraBoxApp
            private set
    }

    lateinit var dbHelper: CloudSSDDatabaseHelper
        private set

    lateinit var cacheManager: CloudSSDCacheManager
        private set

    lateinit var prefetchEngine: RuleBasedPrefetchEngine
        private set

    lateinit var playerManager: CloudSSDPlayerManager
        private set

    lateinit var apiClient: GoogleDriveApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        apiClient = GoogleDriveApiClient()
        dbHelper = CloudSSDDatabaseHelper(this)
        cacheManager = CloudSSDCacheManager(this, dbHelper)
        prefetchEngine = RuleBasedPrefetchEngine(this, cacheManager, dbHelper, apiClient.httpClient)
        playerManager = CloudSSDPlayerManager(this, cacheManager, dbHelper, prefetchEngine, apiClient.httpClient)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val mediaChannel = NotificationChannel(
                CHANNEL_MEDIA,
                getString(R.string.media_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio playback and notification controls"
            }

            val prefetchChannel = NotificationChannel(
                CHANNEL_PREFETCH,
                getString(R.string.prefetch_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Smart cache prefetching indicators"
            }

            notificationManager.createNotificationChannel(mediaChannel)
            notificationManager.createNotificationChannel(prefetchChannel)
        }
    }
}
