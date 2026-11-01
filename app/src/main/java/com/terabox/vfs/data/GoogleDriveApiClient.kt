package com.terabox.vfs.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DriveSessionExpiredException(
    message: String = "Google Drive access token has expired (HTTP 401). Please re-authenticate."
) : IOException(message)

class DriveQuotaExceededException(
    message: String = "Google Drive API rate limit or quota exceeded (HTTP 403). Please wait a moment."
) : IOException(message)

data class DriveQuota(
    val limit: Long,
    val usage: Long,
    val usageInDrive: Long
)

data class DriveUser(
    val displayName: String,
    val emailAddress: String,
    val photoLink: String?
)

data class DriveFileItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String>?,
    val size: Long,
    val modifiedTime: String?,
    val md5Checksum: String?,
    val thumbnailLink: String?
) {
    val isFolder: Boolean
        get() = mimeType == "application/vnd.google-apps.folder"

    val isVideo: Boolean
        get() = mimeType.startsWith("video/") || name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".avi", true) || name.endsWith(".mov", true)

    val isAudio: Boolean
        get() = mimeType.startsWith("audio/") || name.endsWith(".mp3", true) || name.endsWith(".flac", true) || name.endsWith(".wav", true) || name.endsWith(".m4a", true) || name.endsWith(".aac", true)

    val isImage: Boolean
        get() = mimeType.startsWith("image/") || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) || name.endsWith(".png", true) || name.endsWith(".webp", true)
}

data class DriveFileListResponse(
    val files: List<DriveFileItem>,
    val nextPageToken: String?
)

data class OAuthTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("token_type") val tokenType: String
)

class GoogleDriveApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()

    companion object {
        const val BASE_URL = "https://www.googleapis.com/drive/v3"
        const val OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val USER_AGENT = "CloudSSD-Media/1.0 (Android)"
    }

    /**
     * Retrieves storage quota and user profile from Google Drive v3.
     */
    fun getAbout(accessToken: String): Result<Pair<DriveQuota, DriveUser?>> {
        val url = "$BASE_URL/about?fields=storageQuota,user"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (response.code == 401) return Result.failure(DriveSessionExpiredException())
            if (response.code == 403) return Result.failure(DriveQuotaExceededException())
            if (!response.isSuccessful) return Result.failure(IOException("HTTP Error ${response.code}"))

            val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
            val json = gson.fromJson(body, JsonObject::class.java)

            val quotaObj = json.getAsJsonObject("storageQuota")
            val limit = quotaObj?.get("limit")?.asLong ?: (15L * 1024L * 1024L * 1024L)
            val usage = quotaObj?.get("usage")?.asLong ?: 0L
            val usageInDrive = quotaObj?.get("usageInDrive")?.asLong ?: usage
            val quota = DriveQuota(limit, usage, usageInDrive)

            val userObj = json.getAsJsonObject("user")
            val user = if (userObj != null) {
                DriveUser(
                    displayName = userObj.get("displayName")?.asString ?: "Google User",
                    emailAddress = userObj.get("emailAddress")?.asString ?: "",
                    photoLink = userObj.get("photoLink")?.asString
                )
            } else null

            Result.success(Pair(quota, user))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lists media files and folders under a parent directory.
     * Implements pagination and filters out non-media files unless requested otherwise.
     */
    fun listFiles(
        accessToken: String,
        folderId: String = "root",
        mediaOnly: Boolean = true,
        pageSize: Int = 100,
        pageToken: String? = null
    ): Result<DriveFileListResponse> {
        val parentClause = "'$folderId' in parents and trashed = false"
        val mediaFilter = if (mediaOnly) {
            " and (mimeType = 'application/vnd.google-apps.folder' or mimeType contains 'video/' or mimeType contains 'audio/' or mimeType contains 'image/')"
        } else ""

        val rawQuery = "$parentClause$mediaFilter"
        val encodedQuery = URLEncoder.encode(rawQuery, "UTF-8")

        var url = "$BASE_URL/files?q=$encodedQuery&fields=nextPageToken,files(id,name,mimeType,parents,size,modifiedTime,md5Checksum,thumbnailLink)&pageSize=$pageSize&orderBy=folder,name"
        if (!pageToken.isNullOrBlank()) {
            url += "&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}"
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (response.code == 401) return Result.failure(DriveSessionExpiredException())
            if (response.code == 403) return Result.failure(DriveQuotaExceededException())
            if (!response.isSuccessful) return Result.failure(IOException("HTTP Error ${response.code}"))

            val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
            val json = gson.fromJson(body, JsonObject::class.java)

            val nextToken = json.get("nextPageToken")?.asString
            val filesArray = json.getAsJsonArray("files")
            val items = mutableListOf<DriveFileItem>()

            if (filesArray != null) {
                for (element in filesArray) {
                    val item = gson.fromJson(element, DriveFileItem::class.java)
                    items.add(item)
                }
            }

            Result.success(DriveFileListResponse(items, nextToken))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves individual file metadata.
     */
    fun getFileMetadata(accessToken: String, fileId: String): Result<DriveFileItem> {
        val url = "$BASE_URL/files/$fileId?fields=id,name,mimeType,parents,size,modifiedTime,md5Checksum,thumbnailLink"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (response.code == 401) return Result.failure(DriveSessionExpiredException())
            if (response.code == 403) return Result.failure(DriveQuotaExceededException())
            if (!response.isSuccessful) return Result.failure(IOException("HTTP Error ${response.code}"))

            val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
            val item = gson.fromJson(body, DriveFileItem::class.java)
            Result.success(item)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds the direct media streaming URL for Media3 / ExoPlayer.
     */
    fun getMediaStreamUrl(fileId: String): String {
        return "$BASE_URL/files/$fileId?alt=media"
    }

    /**
     * Fetches a specific byte range (HTTP Range 206) for seekable streaming or prefetching.
     */
    fun fetchMediaRange(
        accessToken: String,
        fileId: String,
        startByte: Long,
        endByte: Long
    ): Result<ByteArray> {
        val url = getMediaStreamUrl(fileId)
        val rangeHeader = "bytes=$startByte-$endByte"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $accessToken")
            .header("Range", rangeHeader)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (response.code == 401) return Result.failure(DriveSessionExpiredException())
            if (response.code == 403) return Result.failure(DriveQuotaExceededException())
            if (response.code != 200 && response.code != 206) {
                return Result.failure(IOException("HTTP Error ${response.code} fetching Range $rangeHeader"))
            }

            val bytes = response.body?.bytes() ?: return Result.failure(IOException("Empty range response"))
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads an entire file locally for offline pinning.
     */
    fun downloadFileForPin(
        accessToken: String,
        fileId: String,
        destinationFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> {
        val url = getMediaStreamUrl(fileId)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $accessToken")
            .build()

        return try {
            destinationFile.parentFile?.mkdirs()
            val response = httpClient.newCall(request).execute()
            if (response.code == 401) return Result.failure(DriveSessionExpiredException())
            if (!response.isSuccessful) return Result.failure(IOException("Download failed with HTTP ${response.code}"))

            val totalBytes = response.body?.contentLength() ?: -1L
            val inputStream = response.body?.byteStream() ?: return Result.failure(IOException("No body stream"))

            FileOutputStream(destinationFile).use { fos ->
                val buffer = ByteArray(16 * 1024)
                var downloaded = 0L
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, totalBytes)
                }
                fos.flush()
            }
            Result.success(destinationFile)
        } catch (e: Exception) {
            if (destinationFile.exists()) destinationFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Refreshes an expired OAuth2 access token using the refresh token.
     */
    fun refreshAccessToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String
    ): Result<OAuthTokenResponse> {
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder()
            .url(OAUTH_TOKEN_URL)
            .header("User-Agent", USER_AGENT)
            .post(formBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return Result.failure(IOException("Token refresh failed: HTTP ${response.code}"))

            val body = response.body?.string() ?: return Result.failure(IOException("Empty token response"))
            val tokenResponse = gson.fromJson(body, OAuthTokenResponse::class.java)
            Result.success(tokenResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
