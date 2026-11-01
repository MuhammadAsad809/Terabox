package com.terabox.vfs.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TeraBoxSessionExpiredException(
    message: String = "TeraBox session has expired (errno -6). Please re-authenticate."
) : IOException(message)

data class QuotaResponse(
    val errno: Int,
    val total: Long,
    val used: Long,
    val free: Long
)

data class TeraBoxItem(
    @SerializedName("fs_id") val fsId: Long,
    @SerializedName("server_filename") val filename: String,
    val path: String,
    val size: Long,
    val isdir: Int,
    @SerializedName("server_mtime") val mtime: Long,
    val category: Int = 0,
    val dlink: String? = null,
    val thumbs: JsonObject? = null
)

class ProgressRequestBody(
    private val inputStream: InputStream,
    private val totalBytes: Long,
    private val contentType: String = "application/octet-stream",
    private val onProgress: (uploaded: Long, total: Long) -> Unit
) : RequestBody() {
    override fun contentType() = contentType.toMediaTypeOrNull()
    override fun contentLength() = totalBytes

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(8192)
        var uploaded = 0L
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            sink.write(buffer, 0, read)
            uploaded += read
            onProgress(uploaded, totalBytes)
        }
    }
}

class TeraBoxApiClient {

    companion object {
        const val BASE_URL = "https://www.terabox.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val MAX_PAGES = 5 // Up to 1000 items per directory
        private const val PAGE_SIZE = 200
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Validates account authentication and retrieves storage quota.
     * Throws TeraBoxSessionExpiredException if session is invalid (errno -6).
     */
    fun getQuota(ndus: String): Result<QuotaResponse> {
        val url = "$BASE_URL/api/quota?checkexpire=1&checkfree=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "ndus=$ndus;")
            .header("Accept", "application/json")
            .build()

        return try {
            val response: Response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(IOException("HTTP Error ${response.code}"))
            }
            val body = response.body?.string() ?: return Result.failure(IOException("Empty response body"))
            val quota = gson.fromJson(body, QuotaResponse::class.java)

            when (quota.errno) {
                0 -> Result.success(quota)
                -6 -> Result.failure(TeraBoxSessionExpiredException())
                else -> Result.failure(IOException("TeraBox API error: errno=${quota.errno}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lists directory items for a given directory path with pagination support.
     * Safely handles empty directories, pagination, network errors, and expired sessions.
     */
    fun listFiles(ndus: String, dir: String = "/"): Result<List<TeraBoxItem>> {
        val allItems = mutableListOf<TeraBoxItem>()
        var currentPage = 1
        var hasMore = true

        while (hasMore && currentPage <= MAX_PAGES) {
            val encodedDir = URLEncoder.encode(dir, "UTF-8")
            val url = "$BASE_URL/api/list?dir=$encodedDir&order=name&desc=0&num=$PAGE_SIZE&page=$currentPage&showempty=0"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", "ndus=$ndus;")
                .header("Accept", "application/json")
                .header("Referer", "$BASE_URL/main")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return if (allItems.isNotEmpty()) {
                        Result.success(allItems)
                    } else {
                        Result.failure(IOException("HTTP Error ${response.code} during directory listing"))
                    }
                }

                val body = response.body?.string() ?: return Result.failure(IOException("Empty response from list API"))
                val json = gson.fromJson(body, JsonObject::class.java)
                val errno = json.get("errno")?.asInt ?: -1

                if (errno == -6) {
                    return Result.failure(TeraBoxSessionExpiredException())
                }
                if (errno != 0) {
                    return if (allItems.isNotEmpty()) Result.success(allItems) else Result.failure(IOException("TeraBox list API error: errno=$errno"))
                }

                val listArray = json.getAsJsonArray("list")
                if (listArray == null || listArray.size() == 0) {
                    // Empty directory
                    break
                }

                for (element in listArray) {
                    val item = gson.fromJson(element, TeraBoxItem::class.java)
                    allItems.add(item)
                }

                val remoteHasMore = json.get("has_more")?.asInt ?: 0
                hasMore = remoteHasMore == 1 && listArray.size() >= PAGE_SIZE
                currentPage++

            } catch (e: Exception) {
                return if (allItems.isNotEmpty()) {
                    Result.success(allItems)
                } else {
                    Result.failure(e)
                }
            }
        }

        return Result.success(allItems)
    }

    /**
     * Resolves a direct CDN download link for a given fs_id.
     */
    fun getDownloadLink(ndus: String, fsId: Long): Result<String> {
        val url = "$BASE_URL/api/download?fidlist=%5B$fsId%5D&type=dlink"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "ndus=$ndus;")
            .header("Accept", "application/json")
            .header("Referer", "$BASE_URL/main")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(IOException("HTTP Error ${response.code} resolving dlink"))
            }
            val body = response.body?.string() ?: return Result.failure(IOException("Empty response for dlink"))
            val json = gson.fromJson(body, JsonObject::class.java)
            val errno = json.get("errno")?.asInt ?: 0
            if (errno == -6) {
                return Result.failure(TeraBoxSessionExpiredException())
            }

            val dlinkArray = json.getAsJsonArray("dlink")
            if (dlinkArray != null && dlinkArray.size() > 0) {
                val dlink = dlinkArray[0].asJsonObject.get("dlink")?.asString
                if (!dlink.isNullOrEmpty()) {
                    return Result.success(dlink)
                }
            }
            Result.failure(IOException("No direct download link returned for fsId=$fsId"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads a file directly to a destination File on device storage with progress callback.
     */
    fun downloadFile(
        dlink: String,
        ndus: String,
        destFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> {
        val request = Request.Builder()
            .url(dlink)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "ndus=$ndus;")
            .header("Referer", "$BASE_URL/main")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(IOException("Download failed with HTTP ${response.code}"))
            }
            val body = response.body ?: return Result.failure(IOException("Empty download body"))
            val totalBytes = body.contentLength()
            val parent = destFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded, totalBytes)
                    }
                    output.flush()
                }
            }
            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uploads a local file/stream directly to TeraBox cloud with live progress reporting.
     */
    fun uploadFile(
        ndus: String,
        remotePath: String,
        filename: String,
        inputStream: InputStream,
        fileSize: Long,
        onProgress: (uploaded: Long, total: Long) -> Unit
    ): Result<Boolean> {
        val encodedPath = URLEncoder.encode(remotePath, "UTF-8")
        val url = "$BASE_URL/rest/2.0/pcs/file?method=upload&path=$encodedPath&ondup=overwrite"

        val progressBody = ProgressRequestBody(inputStream, fileSize) { up, tot ->
            onProgress(up, tot)
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, progressBody)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "ndus=$ndus;")
            .header("Referer", "$BASE_URL/main")
            .post(multipartBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            val errno = json.get("errno")?.asInt ?: 0
            if (errno == 0 || json.has("fs_id")) {
                Result.success(true)
            } else {
                Result.failure(IOException("Upload failed: errno=$errno, body=$body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates a new directory in TeraBox cloud.
     */
    fun createFolder(ndus: String, folderPath: String): Result<Boolean> {
        val url = "$BASE_URL/api/create"
        val formBody = FormBody.Builder()
            .add("path", folderPath)
            .add("isdir", "1")
            .add("size", "0")
            .add("uploadid", "")
            .add("block_list", "[]")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "ndus=$ndus;")
            .header("Referer", "$BASE_URL/main")
            .post(formBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            val errno = json.get("errno")?.asInt ?: -1
            if (errno == 0 || errno == -8) { // 0: success, -8: directory already exists
                Result.success(true)
            } else {
                Result.failure(IOException("Failed to create folder: errno=$errno"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes a file or directory in TeraBox cloud.
     */
    fun deleteItem(ndus: String, remotePath: String): Result<Boolean> {
        val url = "$BASE_URL/api/filemanager?opera=delete"
        val fileListJson = "[\"$remotePath\"]"
        val formBody = FormBody.Builder()
            .add("filelist", fileListJson)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "ndus=$ndus;")
            .header("Referer", "$BASE_URL/main")
            .post(formBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            val errno = json.get("errno")?.asInt ?: -1
            if (errno == 0) {
                Result.success(true)
            } else {
                Result.failure(IOException("Delete failed: errno=$errno"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Renames a file or directory in TeraBox cloud.
     */
    fun renameItem(ndus: String, remotePath: String, newName: String): Result<Boolean> {
        val url = "$BASE_URL/api/filemanager?opera=rename"
        val fileListJson = "[{\"path\":\"$remotePath\",\"newname\":\"$newName\"}]"
        val formBody = FormBody.Builder()
            .add("filelist", fileListJson)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "ndus=$ndus;")
            .header("Referer", "$BASE_URL/main")
            .post(formBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            val errno = json.get("errno")?.asInt ?: -1
            if (errno == 0) {
                Result.success(true)
            } else {
                Result.failure(IOException("Rename failed: errno=$errno"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes an HTTP Range request for seekable streaming chunks.
     */
    fun fetchRange(dlink: String, ndus: String, startByte: Long, endByte: Long): Result<ByteArray> {
        val request = Request.Builder()
            .url(dlink)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "ndus=$ndus;")
            .header("Range", "bytes=$startByte-$endByte")
            .header("Referer", "$BASE_URL/main")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val code = response.code
            if (code == 200 || code == 206) {
                val bytes = response.body?.bytes() ?: ByteArray(0)
                Result.success(bytes)
            } else {
                Result.failure(IOException("Range request failed with HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
