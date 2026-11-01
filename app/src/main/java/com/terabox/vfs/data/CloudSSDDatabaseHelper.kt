package com.terabox.vfs.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class FileEntity(
    val id: String,
    val name: String,
    val mimeType: String,
    val parentId: String,
    val size: Long,
    val modifiedTime: Long,
    val md5Checksum: String?,
    val thumbnailLink: String?,
    val isFolder: Boolean
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/") || name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".avi", true)

    val isAudio: Boolean
        get() = mimeType.startsWith("audio/") || name.endsWith(".mp3", true) || name.endsWith(".flac", true) || name.endsWith(".wav", true) || name.endsWith(".m4a", true)

    val isImage: Boolean
        get() = mimeType.startsWith("image/") || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) || name.endsWith(".png", true) || name.endsWith(".webp", true)
}

data class PinnedEntity(
    val fileId: String,
    val localPath: String,
    val fileSize: Long,
    val pinnedAt: Long
)

data class PlaybackHistory(
    val fileId: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long
)

data class DriveAccount(
    val accountId: String,
    val email: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String?,
    val totalQuota: Long,
    val usedQuota: Long,
    val isActive: Boolean
)

class CloudSSDDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "cloudssd_media.db"
        const val DATABASE_VERSION = 1

        const val TABLE_FILES = "files"
        const val TABLE_PINNED = "pinned"
        const val TABLE_PLAYBACK = "playback_history"
        const val TABLE_SETTINGS = "settings"
        const val TABLE_ACCOUNTS = "accounts"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_ACCOUNTS (
                account_id TEXT PRIMARY KEY,
                email TEXT,
                display_name TEXT,
                access_token TEXT NOT NULL,
                refresh_token TEXT,
                total_quota INTEGER DEFAULT 0,
                used_quota INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_FILES (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                parent_id TEXT NOT NULL,
                size INTEGER DEFAULT 0,
                modified_time INTEGER DEFAULT 0,
                md5_checksum TEXT,
                thumbnail_link TEXT,
                is_folder INTEGER DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parent_id ON $TABLE_FILES (parent_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_file_name ON $TABLE_FILES (name)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_PINNED (
                file_id TEXT PRIMARY KEY,
                local_path TEXT NOT NULL,
                file_size INTEGER DEFAULT 0,
                pinned_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYBACK (
                file_id TEXT PRIMARY KEY,
                position_ms INTEGER DEFAULT 0,
                duration_ms INTEGER DEFAULT 0,
                last_played_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SETTINGS (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ACCOUNTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FILES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PINNED")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PLAYBACK")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SETTINGS")
        onCreate(db)
    }

    // --- Account Operations ---

    fun saveAccount(account: DriveAccount) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("account_id", account.accountId)
            put("email", account.email)
            put("display_name", account.displayName)
            put("access_token", account.accessToken)
            put("refresh_token", account.refreshToken)
            put("total_quota", account.totalQuota)
            put("used_quota", account.usedQuota)
            put("is_active", if (account.isActive) 1 else 0)
        }
        db.insertWithOnConflict(TABLE_ACCOUNTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getActiveAccount(): DriveAccount? {
        val db = readableDatabase
        val cursor = db.query(TABLE_ACCOUNTS, null, "is_active = 1", null, null, null, null, "1")
        cursor.use {
            if (it.moveToFirst()) {
                return extractAccount(it)
            }
        }
        return null
    }

    fun clearActiveAccount() {
        val db = writableDatabase
        db.delete(TABLE_ACCOUNTS, null, null)
    }

    // --- Files Operations ---

    fun upsertFiles(files: List<FileEntity>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (file in files) {
                val values = ContentValues().apply {
                    put("id", file.id)
                    put("name", file.name)
                    put("mime_type", file.mimeType)
                    put("parent_id", file.parentId)
                    put("size", file.size)
                    put("modified_time", file.modifiedTime)
                    put("md5_checksum", file.md5Checksum)
                    put("thumbnail_link", file.thumbnailLink)
                    put("is_folder", if (file.isFolder) 1 else 0)
                }
                db.insertWithOnConflict(TABLE_FILES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getChildren(parentId: String): List<FileEntity> {
        val list = mutableListOf<FileEntity>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FILES,
            null,
            "parent_id = ?",
            arrayOf(parentId),
            null,
            null,
            "is_folder DESC, name ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(extractFile(it))
            }
        }
        return list
    }

    fun getFile(id: String): FileEntity? {
        val db = readableDatabase
        val cursor = db.query(TABLE_FILES, null, "id = ?", arrayOf(id), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                return extractFile(it)
            }
        }
        return null
    }

    fun searchFiles(query: String): List<FileEntity> {
        val list = mutableListOf<FileEntity>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FILES,
            null,
            "name LIKE ?",
            arrayOf("%$query%"),
            null,
            null,
            "is_folder DESC, name ASC",
            "100"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(extractFile(it))
            }
        }
        return list
    }

    fun deleteFile(id: String) {
        val db = writableDatabase
        db.delete(TABLE_FILES, "id = ?", arrayOf(id))
    }

    // --- Pinned Operations ---

    fun pinFile(fileId: String, localPath: String, size: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("file_id", fileId)
            put("local_path", localPath)
            put("file_size", size)
            put("pinned_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_PINNED, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unpinFile(fileId: String) {
        val db = writableDatabase
        db.delete(TABLE_PINNED, "file_id = ?", arrayOf(fileId))
    }

    fun isPinned(fileId: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT 1 FROM $TABLE_PINNED WHERE file_id = ?", arrayOf(fileId))
        cursor.use {
            return it.moveToFirst()
        }
    }

    fun getPinnedFiles(): List<PinnedEntity> {
        val list = mutableListOf<PinnedEntity>()
        val db = readableDatabase
        val cursor = db.query(TABLE_PINNED, null, null, null, null, null, "pinned_at DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    PinnedEntity(
                        fileId = it.getString(it.getColumnIndexOrThrow("file_id")),
                        localPath = it.getString(it.getColumnIndexOrThrow("local_path")),
                        fileSize = it.getLong(it.getColumnIndexOrThrow("file_size")),
                        pinnedAt = it.getLong(it.getColumnIndexOrThrow("pinned_at"))
                    )
                )
            }
        }
        return list
    }

    fun getTotalPinnedSizeBytes(): Long {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT SUM(file_size) FROM $TABLE_PINNED", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return 0L
    }

    // --- Playback History Operations ---

    fun savePlaybackPosition(fileId: String, positionMs: Long, durationMs: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("file_id", fileId)
            put("position_ms", positionMs)
            put("duration_ms", durationMs)
            put("last_played_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_PLAYBACK, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getPlaybackPosition(fileId: String): Long {
        val db = readableDatabase
        val cursor = db.query(TABLE_PLAYBACK, arrayOf("position_ms"), "file_id = ?", arrayOf(fileId), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return 0L
    }

    // --- Settings Operations ---

    fun getCacheCapGb(): Float {
        val value = getSetting("cache_cap_gb")
        return value?.toFloatOrNull() ?: 5.0f
    }

    fun setCacheCapGb(capGb: Float) {
        saveSetting("cache_cap_gb", capGb.toString())
    }

    fun isWifiOnlyPrefetch(): Boolean {
        return getSetting("wifi_only_prefetch")?.toBooleanStrictOrNull() ?: true
    }

    fun setWifiOnlyPrefetch(enabled: Boolean) {
        saveSetting("wifi_only_prefetch", enabled.toString())
    }

    fun isPrefetchEnabled(): Boolean {
        return getSetting("prefetch_enabled")?.toBooleanStrictOrNull() ?: true
    }

    fun setPrefetchEnabled(enabled: Boolean) {
        saveSetting("prefetch_enabled", enabled.toString())
    }

    private fun saveSetting(key: String, value: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict(TABLE_SETTINGS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun getSetting(key: String): String? {
        val db = readableDatabase
        val cursor = db.query(TABLE_SETTINGS, arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return null
    }

    // --- Extraction Helpers ---

    private fun extractFile(cursor: Cursor): FileEntity {
        return FileEntity(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
            parentId = cursor.getString(cursor.getColumnIndexOrThrow("parent_id")),
            size = cursor.getLong(cursor.getColumnIndexOrThrow("size")),
            modifiedTime = cursor.getLong(cursor.getColumnIndexOrThrow("modified_time")),
            md5Checksum = cursor.getString(cursor.getColumnIndexOrThrow("md5_checksum")),
            thumbnailLink = cursor.getString(cursor.getColumnIndexOrThrow("thumbnail_link")),
            isFolder = cursor.getInt(cursor.getColumnIndexOrThrow("is_folder")) == 1
        )
    }

    private fun extractAccount(cursor: Cursor): DriveAccount {
        return DriveAccount(
            accountId = cursor.getString(cursor.getColumnIndexOrThrow("account_id")),
            email = cursor.getString(cursor.getColumnIndexOrThrow("email")),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
            accessToken = cursor.getString(cursor.getColumnIndexOrThrow("access_token")),
            refreshToken = cursor.getString(cursor.getColumnIndexOrThrow("refresh_token")),
            totalQuota = cursor.getLong(cursor.getColumnIndexOrThrow("total_quota")),
            usedQuota = cursor.getLong(cursor.getColumnIndexOrThrow("used_quota")),
            isActive = cursor.getInt(cursor.getColumnIndexOrThrow("is_active")) == 1
        )
    }
}
