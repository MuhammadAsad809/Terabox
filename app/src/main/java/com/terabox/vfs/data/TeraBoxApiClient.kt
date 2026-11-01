package com.terabox.vfs.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

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
            val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
            val quota = gson.fromJson(body, QuotaResponse::class.java)
            if (quota.errno != 0) {
                Result.failure(IOException("API Error errno=${quota.errno}"))
            } else {
                Result.success(quota)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun listFiles(ndus: String, dir: String = "/"): Result<List<TeraBoxItem>> {
        val encodedDir = java.net.URLEncoder.encode(dir, "UTF-8")
        val url = "$BASE_URL/api/list?dir=$encodedDir&order=name&desc=0&num=200&page=1&showempty=0"
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
                return Result.failure(IOException("HTTP Error ${response.code}"))
            }
            val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
            val json = gson.fromJson(body, JsonObject::class.java)
            val errno = json.get("errno")?.asInt ?: -1
            if (errno != 0) {
                return Result.failure(IOException("API Error errno=$errno"))
            }

            val listArray = json.getAsJsonArray("list") ?: return Result.success(emptyList())
            val items = mutableListOf<TeraBoxItem>()
            for (element in listArray) {
                val item = gson.fromJson(element, TeraBoxItem::class.java)
                items.add(item)
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
                return Result.failure(IOException("HTTP Error ${response.code}"))
            }
            val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
            val json = gson.fromJson(body, JsonObject::class.java)
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
     * Executes an HTTP Range request for seekable streaming
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
