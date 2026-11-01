package com.terabox.vfs

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.terabox.vfs.data.DriveFileItem
import com.terabox.vfs.data.GoogleDriveApiClient
import org.junit.Assert.*
import org.junit.Test

class CloudSSDApiClientTest {

    private val gson = Gson()

    @Test
    fun testDriveAboutQuotaParsing() {
        val rawJson = """
            {
                "storageQuota": {
                    "limit": "107374182400",
                    "usage": "21474836480",
                    "usageInDrive": "21474836480"
                },
                "user": {
                    "displayName": "Test Student",
                    "emailAddress": "student@gmail.com",
                    "photoLink": "https://lh3.googleusercontent.com/photo.jpg"
                }
            }
        """.trimIndent()

        val json = gson.fromJson(rawJson, JsonObject::class.java)
        val quotaObj = json.getAsJsonObject("storageQuota")
        val limit = quotaObj.get("limit").asLong
        val usage = quotaObj.get("usage").asLong

        assertEquals(107374182400L, limit) // 100 GB
        assertEquals(21474836480L, usage) // 20 GB

        val userObj = json.getAsJsonObject("user")
        val displayName = userObj.get("displayName").asString
        val email = userObj.get("emailAddress").asString

        assertEquals("Test Student", displayName)
        assertEquals("student@gmail.com", email)
    }

    @Test
    fun testDriveFileListParsing() {
        val rawJson = """
            {
                "nextPageToken": "token_page_2",
                "files": [
                    {
                        "id": "folder_123",
                        "name": "Movies",
                        "mimeType": "application/vnd.google-apps.folder",
                        "parents": ["root"],
                        "size": "0"
                    },
                    {
                        "id": "file_456",
                        "name": "Interstellar.mp4",
                        "mimeType": "video/mp4",
                        "parents": ["folder_123"],
                        "size": "4294967296",
                        "md5Checksum": "c8b417c823f46f",
                        "thumbnailLink": "https://drive.google.com/thumb/file_456"
                    },
                    {
                        "id": "file_789",
                        "name": "Lecture_Audio.mp3",
                        "mimeType": "audio/mpeg",
                        "parents": ["root"],
                        "size": "15728640"
                    }
                ]
            }
        """.trimIndent()

        val json = gson.fromJson(rawJson, JsonObject::class.java)
        assertEquals("token_page_2", json.get("nextPageToken").asString)

        val filesArray = json.getAsJsonArray("files")
        assertEquals(3, filesArray.size())

        val folder = gson.fromJson(filesArray[0], DriveFileItem::class.java)
        assertEquals("folder_123", folder.id)
        assertEquals("Movies", folder.name)
        assertTrue(folder.isFolder)
        assertFalse(folder.isVideo)

        val video = gson.fromJson(filesArray[1], DriveFileItem::class.java)
        assertEquals("file_456", video.id)
        assertEquals("Interstellar.mp4", video.name)
        assertEquals(4294967296L, video.size)
        assertTrue(video.isVideo)
        assertFalse(video.isFolder)
        assertEquals("c8b417c823f46f", video.md5Checksum)

        val audio = gson.fromJson(filesArray[2], DriveFileItem::class.java)
        assertEquals("file_789", audio.id)
        assertTrue(audio.isAudio)
        assertFalse(audio.isVideo)
    }

    @Test
    fun testMediaStreamUrl() {
        val client = GoogleDriveApiClient()
        val url = client.getMediaStreamUrl("file_abc_123")
        assertEquals("https://www.googleapis.com/drive/v3/files/file_abc_123?alt=media", url)
    }

    @Test
    fun testCacheKeyFormation() {
        val fileId = "video_999"
        val checksum = "hash_abc_456"
        val cacheKey = "cloudssd_${fileId}_$checksum"
        assertEquals("cloudssd_video_999_hash_abc_456", cacheKey)

        val fallbackKey = "cloudssd_${fileId}_v1"
        assertEquals("cloudssd_video_999_v1", fallbackKey)
    }

    @Test
    fun testDriveFolderCreationParsing() {
        val rawJson = """
            {
                "id": "new_folder_999",
                "name": "My New Folder",
                "mimeType": "application/vnd.google-apps.folder",
                "parents": ["root"],
                "size": "0"
            }
        """.trimIndent()
        val item = gson.fromJson(rawJson, DriveFileItem::class.java)
        assertEquals("new_folder_999", item.id)
        assertEquals("My New Folder", item.name)
        assertTrue(item.isFolder)
    }

    @Test
    fun testDriveFileUploadParsing() {
        val rawJson = """
            {
                "id": "uploaded_file_111",
                "name": "Presentation.pdf",
                "mimeType": "application/pdf",
                "parents": ["root"],
                "size": "204800",
                "md5Checksum": "d41d8cd98f00b204e9800998ecf8427e"
            }
        """.trimIndent()
        val item = gson.fromJson(rawJson, DriveFileItem::class.java)
        assertEquals("uploaded_file_111", item.id)
        assertEquals("Presentation.pdf", item.name)
        assertEquals(204800L, item.size)
        assertFalse(item.isFolder)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", item.md5Checksum)
    }
}
