package com.fallgist.nishinomiyalibrary.data.remote.openbd

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class OpenBdClient(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient(),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.openbd.jp/"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun coverUrl(isbn: String): String? {
        val request = Request.Builder()
            .url(
                baseUrl.newBuilder()
                    .addPathSegment("v1")
                    .addPathSegment("get")
                    .addQueryParameter("isbn", isbn)
                    .build(),
            )
            .get()
            .build()
        val body = try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw HttpFailure(response)
                    response.body?.string().orEmpty()
                }
            }
        } catch (exception: IOException) {
            throw LibraryError.Network(exception)
        } catch (exception: HttpFailure) {
            throw LibraryError.Network(exception)
        }
        return parseCoverUrl(body)
    }

    private fun parseCoverUrl(body: String): String? {
        val results = try {
            json.parseToJsonElement(body) as? JsonArray
                ?: throw LibraryError.Parse("openbd", "JSON配列ではありません")
        } catch (exception: LibraryError.Parse) {
            throw exception
        } catch (exception: Exception) {
            throw LibraryError.Parse("openbd", "JSONの解析に失敗しました")
        }
        val first = results.firstOrNull() as? JsonObject ?: return null
        val summary = first["summary"] as? JsonObject ?: return null
        return (summary["cover"] as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private class HttpFailure(response: Response) : Exception() {
        val statusCode: Int = response.code
    }
}
