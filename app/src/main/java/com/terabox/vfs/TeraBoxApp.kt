package com.terabox.vfs

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.terabox.vfs.cache.LruBlockCacheManager
import com.terabox.vfs.data.TeraBoxDatabaseHelper

class TeraBoxApp : Application() {

    companion object {
        const val CHANNEL_SYNC = "channel_terabox_sync"
        const val CHANNEL_UPLOAD = "channel_terabox_upload"
        
        lateinit var instance: TeraBoxApp
            private set
    }

    lateinit var dbHelper: TeraBoxDatabaseHelper
        private set

    lateinit var cacheManager: LruBlockCacheManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        dbHelper = TeraBoxDatabaseHelper(this)
        cacheManager = LruBlockCacheManager(this)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background file index and sync notifications"
            }
            
            val uploadChannel = NotificationChannel(
                CHANNEL_UPLOAD,
                getString(R.string.upload_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "File upload progress notifications"
            }
            
            notificationManager.createNotificationChannel(syncChannel)
            notificationManager.createNotificationChannel(uploadChannel)
        }
    }
}
