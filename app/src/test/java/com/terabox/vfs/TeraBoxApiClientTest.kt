package com.terabox.vfs

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.terabox.vfs.data.QuotaResponse
import com.terabox.vfs.data.TeraBoxItem
import org.junit.Assert.*
import org.junit.Test

class TeraBoxApiClientTest {

    private val gson = Gson()

    @Test
    fun testQuotaResponseParsing() {
        val rawJson = """
            {
                "errno": 0,
                "total": 1099511627776,
                "used": 150323855360,
                "free": 949187772416,
                "request_id": 998877665544
            }
        """.trimIndent()

        val quota = gson.fromJson(rawJson, QuotaResponse::class.java)
        assertEquals(0, quota.errno)
        assertEquals(1099511627776L, quota.total)
        assertEquals(150323855360L, quota.used)
        assertEquals(949187772416L, quota.free)
    }

    @Test
    fun testListFilesResponseParsing() {
        val rawJson = """
            {
                "errno": 0,
                "guid_info": "ok",
                "list": [
                    {
                        "fs_id": 109283748291029,
                        "server_filename": "Games",
                        "path": "/Games",
                        "size": 0,
                        "isdir": 1,
                        "server_mtime": 1700000000,
                        "category": 6
                    },
                    {
                        "fs_id": 109283748291030,
                        "server_filename": "God_of_War.iso",
                        "path": "/Games/God_of_War.iso",
                        "size": 1572864000,
                        "isdir": 0,
                        "server_mtime": 1700050000,
                        "category": 0,
                        "dlink": "https://d.terabox.com/file/god_of_war"
                    }
                ],
                "has_more": 0
            }
        """.trimIndent()

        val json = gson.fromJson(rawJson, JsonObject::class.java)
        assertEquals(0, json.get("errno").asInt)

        val listArray = json.getAsJsonArray("list")
        assertEquals(2, listArray.size())

        val folder = gson.fromJson(listArray[0], TeraBoxItem::class.java)
        assertEquals(109283748291029L, folder.fsId)
        assertEquals("Games", folder.filename)
        assertEquals("/Games", folder.path)
        assertEquals(1, folder.isdir)

        val file = gson.fromJson(listArray[1], TeraBoxItem::class.java)
        assertEquals(109283748291030L, file.fsId)
        assertEquals("God_of_War.iso", file.filename)
        assertEquals(1572864000L, file.size)
        assertEquals(0, file.isdir)
        assertEquals("https://d.terabox.com/file/god_of_war", file.dlink)
    }

    @Test
    fun testSessionExpiredErrno() {
        val rawJson = """
            {
                "errno": -6,
                "request_id": 123456789
            }
        """.trimIndent()

        val json = gson.fromJson(rawJson, JsonObject::class.java)
        val errno = json.get("errno").asInt
        assertEquals(-6, errno)
    }

    @Test
    fun testEmptyDirectoryParsing() {
        val rawJson = """
            {
                "errno": 0,
                "list": [],
                "has_more": 0
            }
        """.trimIndent()

        val json = gson.fromJson(rawJson, JsonObject::class.java)
        val listArray = json.getAsJsonArray("list")
        assertNotNull(listArray)
        assertEquals(0, listArray.size())
    }
}
