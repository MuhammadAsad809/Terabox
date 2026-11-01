package com.terabox.vfs.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
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

class TeraBoxApiClient {

    companion object {
        const val BASE_URL = "https://www.terabox.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val MAX_PAGES = 5 // Up to 1000 items per directory
        private const val PAGE_SIZE = 200
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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
