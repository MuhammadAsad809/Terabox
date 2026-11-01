package com.terabox.vfs.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class AccountModel(
    val id: String,
    val name: String,
    val ndus: String,
    val totalQuota: Long,
    val usedQuota: Long,
    val active: Boolean
)

data class FileNodeModel(
    val fsId: Long,
    val accountId: String,
    val path: String,
    val parentPath: String,
    val filename: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val dlink: String?,
    val dlinkUpdatedAt: Long,
    val isPinned: Boolean
)

class TeraBoxDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "terabox_vfs.db"
        const val DATABASE_VERSION = 1

        const val TABLE_ACCOUNTS = "accounts"
        const val TABLE_NODES = "nodes"
        const val TABLE_CACHE_BLOCKS = "cache_blocks"
        const val TABLE_UPLOADS = "upload_tasks"

        fun normalizeParentPath(path: String): String {
            if (path == "/" || path.isBlank()) return "/"
            val trimmed = if (path.endsWith("/")) path.dropLast(1) else path
            return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_ACCOUNTS (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                ndus TEXT NOT NULL,
                total_quota INTEGER DEFAULT 0,
                used_quota INTEGER DEFAULT 0,
                active INTEGER DEFAULT 1
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_NODES (
                fs_id INTEGER PRIMARY KEY,
                account_id TEXT NOT NULL,
                path TEXT NOT NULL,
                parent_path TEXT NOT NULL,
                filename TEXT NOT NULL,
                is_dir INTEGER NOT NULL,
                size INTEGER DEFAULT 0,
                mtime INTEGER DEFAULT 0,
                dlink TEXT,
                dlink_updated_at INTEGER DEFAULT 0,
                is_pinned INTEGER DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parent ON $TABLE_NODES (account_id, parent_path)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_filename ON $TABLE_NODES (account_id, filename)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_path ON $TABLE_NODES (account_id, path)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CACHE_BLOCKS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id TEXT NOT NULL,
                fs_id INTEGER NOT NULL,
                block_index INTEGER NOT NULL,
                size INTEGER NOT NULL,
                last_accessed INTEGER NOT NULL,
                UNIQUE(fs_id, block_index)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_UPLOADS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id TEXT NOT NULL,
                target_dir TEXT NOT NULL,
                staged_path TEXT NOT NULL,
                filename TEXT NOT NULL,
                size INTEGER DEFAULT 0,
                status TEXT DEFAULT 'PENDING'
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ACCOUNTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NODES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CACHE_BLOCKS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_UPLOADS")
        onCreate(db)
    }

    fun upsertAccount(account: AccountModel) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", account.id)
            put("name", account.name)
            put("ndus", account.ndus)
            put("total_quota", account.totalQuota)
            put("used_quota", account.usedQuota)
            put("active", if (account.active) 1 else 0)
        }
        db.insertWithOnConflict(TABLE_ACCOUNTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAccounts(): List<AccountModel> {
        val list = mutableListOf<AccountModel>()
        val db = readableDatabase
        val cursor: Cursor = db.query(TABLE_ACCOUNTS, null, null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    AccountModel(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        ndus = it.getString(it.getColumnIndexOrThrow("ndus")),
                        totalQuota = it.getLong(it.getColumnIndexOrThrow("total_quota")),
                        usedQuota = it.getLong(it.getColumnIndexOrThrow("used_quota")),
                        active = it.getInt(it.getColumnIndexOrThrow("active")) == 1
                    )
                )
            }
        }
        return list
    }

    fun getAccount(id: String): AccountModel? {
        val db = readableDatabase
        val cursor = db.query(TABLE_ACCOUNTS, null, "id = ?", arrayOf(id), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                return AccountModel(
                    id = it.getString(it.getColumnIndexOrThrow("id")),
                    name = it.getString(it.getColumnIndexOrThrow("name")),
                    ndus = it.getString(it.getColumnIndexOrThrow("ndus")),
                    totalQuota = it.getLong(it.getColumnIndexOrThrow("total_quota")),
                    usedQuota = it.getLong(it.getColumnIndexOrThrow("used_quota")),
                    active = it.getInt(it.getColumnIndexOrThrow("active")) == 1
                )
            }
        }
        return null
    }

    fun setAccountActive(id: String, active: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("active", if (active) 1 else 0)
        }
        db.update(TABLE_ACCOUNTS, values, "id = ?", arrayOf(id))
    }

    fun insertOrUpdateNodes(nodes: List<FileNodeModel>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (node in nodes) {
                val normalizedParent = normalizeParentPath(node.parentPath)
                val values = ContentValues().apply {
                    put("fs_id", node.fsId)
                    put("account_id", node.accountId)
                    put("path", node.path)
                    put("parent_path", normalizedParent)
                    put("filename", node.filename)
                    put("is_dir", if (node.isDir) 1 else 0)
                    put("size", node.size)
                    put("mtime", node.mtime)
                    if (node.dlink != null) {
                        put("dlink", node.dlink)
                        put("dlink_updated_at", node.dlinkUpdatedAt)
                    }
                    put("is_pinned", if (node.isPinned) 1 else 0)
                }
                db.insertWithOnConflict(TABLE_NODES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteNodesForParent(accountId: String, parentPath: String) {
        val db = writableDatabase
        val normalized = normalizeParentPath(parentPath)
        db.delete(TABLE_NODES, "account_id = ? AND parent_path = ?", arrayOf(accountId, normalized))
    }

    fun getNode(fsId: Long): FileNodeModel? {
        val db = readableDatabase
        val cursor = db.query(TABLE_NODES, null, "fs_id = ?", arrayOf(fsId.toString()), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                return extractNode(it)
            }
        }
        return null
    }

    fun getNodeByPath(accountId: String, path: String): FileNodeModel? {
        val db = readableDatabase
        val cursor = db.query(TABLE_NODES, null, "account_id = ? AND path = ?", arrayOf(accountId, path), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                return extractNode(it)
            }
        }
        return null
    }

    fun getChildren(accountId: String, parentPath: String): List<FileNodeModel> {
        val list = mutableListOf<FileNodeModel>()
        val db = readableDatabase
        val normalized = normalizeParentPath(parentPath)
        val cursor = db.query(
            TABLE_NODES,
            null,
            "account_id = ? AND parent_path = ?",
            arrayOf(accountId, normalized),
            null,
            null,
            "is_dir DESC, filename ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(extractNode(it))
            }
        }
        return list
    }

    fun searchNodes(accountId: String, query: String): List<FileNodeModel> {
        val list = mutableListOf<FileNodeModel>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NODES,
            null,
            "account_id = ? AND filename LIKE ?",
            arrayOf(accountId, "%$query%"),
            null,
            null,
            "filename ASC",
            "100"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(extractNode(it))
            }
        }
        return list
    }

    fun updateDlink(fsId: Long, dlink: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("dlink", dlink)
            put("dlink_updated_at", System.currentTimeMillis())
        }
        db.update(TABLE_NODES, values, "fs_id = ?", arrayOf(fsId.toString()))
    }

    fun setPinned(fsId: Long, isPinned: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("is_pinned", if (isPinned) 1 else 0)
        }
        db.update(TABLE_NODES, values, "fs_id = ?", arrayOf(fsId.toString()))
    }

    fun deleteNode(fsId: Long) {
        val db = writableDatabase
        db.delete(TABLE_NODES, "fs_id = ?", arrayOf(fsId.toString()))
    }

    fun renameNode(fsId: Long, newFilename: String, newPath: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("filename", newFilename)
            put("path", newPath)
        }
        db.update(TABLE_NODES, values, "fs_id = ?", arrayOf(fsId.toString()))
    }

    private fun extractNode(cursor: Cursor): FileNodeModel {
        return FileNodeModel(
            fsId = cursor.getLong(cursor.getColumnIndexOrThrow("fs_id")),
            accountId = cursor.getString(cursor.getColumnIndexOrThrow("account_id")),
            path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
            parentPath = cursor.getString(cursor.getColumnIndexOrThrow("parent_path")),
            filename = cursor.getString(cursor.getColumnIndexOrThrow("filename")),
            isDir = cursor.getInt(cursor.getColumnIndexOrThrow("is_dir")) == 1,
            size = cursor.getLong(cursor.getColumnIndexOrThrow("size")),
            mtime = cursor.getLong(cursor.getColumnIndexOrThrow("mtime")),
            dlink = cursor.getString(cursor.getColumnIndexOrThrow("dlink")),
            dlinkUpdatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("dlink_updated_at")),
            isPinned = cursor.getInt(cursor.getColumnIndexOrThrow("is_pinned")) == 1
        )
    }
}
