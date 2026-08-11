package com.tdee.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val FOLDER_NAME = "TDEE Backups"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private val JSON_MEDIA = "application/json".toMediaType()
private const val MAX_ATTEMPTS = 3

/** Typed failure reasons for [DriveClient] operations. */
sealed interface DriveError {
    object NeedsAuth : DriveError
    object Network : DriveError
    object RateLimited : DriveError
    object Server : DriveError
    data class Unknown(val message: String) : DriveError
}

class DriveException(val error: DriveError, message: String) : Exception(message)

data class DriveFile(val id: String, val name: String, val createdTime: String, val size: Long?)

/**
 * Thin Google Drive REST v3 client (scope `drive.file`, so every query only ever sees files this
 * app created). Deliberately hand-rolled over OkHttp + `org.json` rather than the Drive Java SDK, to
 * keep one networking idiom in the codebase (see [com.tdee.app.addfood.LlmFoodParser], which this
 * mirrors: injectable [client]/base URLs as the MockWebServer seam, [executeWithRetry] for 429/5xx/
 * network retry with linear backoff).
 *
 * [token] is a suspend lambda (not a plain string) so the caller can refresh it silently — no OkHttp
 * [okhttp3.Interceptor] is used for auth because interceptors are blocking and the token fetch isn't.
 *
 * 401/403 handling is layered on top of, and composes with, the 429/5xx retry loop: within a single
 * attempt of that loop, a 401/403 triggers [onUnauthorized] (once per call) and one immediate retry
 * with a freshly-fetched token — this does NOT consume one of the [MAX_ATTEMPTS] 429/5xx retries. A
 * second 401/403 (after the one re-auth) is terminal and maps to [DriveError.NeedsAuth].
 *
 * The client does not cache the backup folder id — callers own that caching.
 */
class DriveClient(
    private val client: OkHttpClient,
    private val token: suspend () -> String,
    private val baseUrl: String = "https://www.googleapis.com",
    private val uploadUrl: String = "https://www.googleapis.com/upload",
    private val onUnauthorized: suspend () -> Unit = {},
    private val backoffMs: Long = 400L,
) {

    /** Finds the "TDEE Backups" folder, creating it if absent. Returns its file id. */
    suspend fun ensureFolder(): String {
        val q = "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
        val findUrl = (baseUrl.trimEnd('/') + "/drive/v3/files").toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id)")
            .build()
        val found = executeWithRetry { request(findUrl, "GET") }
        val existing = JSONObject(found).getJSONArray("files")
        if (existing.length() > 0) return existing.getJSONObject(0).getString("id")

        val createUrl = (baseUrl.trimEnd('/') + "/drive/v3/files").toHttpUrl()
        val body = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME)
            .toString().toRequestBody(JSON_MEDIA)
        val created = executeWithRetry { request(createUrl, "POST", body) }
        return JSONObject(created).getString("id")
    }

    /** Uploads [content] as a new file named [name] inside [folderId]. */
    suspend fun upload(folderId: String, name: String, content: String): DriveFile {
        val url = (uploadUrl.trimEnd('/') + "/drive/v3/files").toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,name,createdTime")
            .build()
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
            .toString()
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody(JSON_MEDIA))
            .addPart(content.toRequestBody(JSON_MEDIA))
            .build()
        val response = executeWithRetry { request(url, "POST", multipart) }
        val obj = JSONObject(response)
        return DriveFile(
            id = obj.getString("id"),
            name = obj.getString("name"),
            createdTime = obj.optString("createdTime"),
            size = if (obj.has("size")) obj.optLong("size") else null,
        )
    }

    /** Lists files in [folderId], newest first. */
    suspend fun list(folderId: String): List<DriveFile> {
        val q = "'$folderId' in parents and trashed = false"
        val url = (baseUrl.trimEnd('/') + "/drive/v3/files").toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("orderBy", "createdTime desc")
            .addQueryParameter("pageSize", "20")
            .addQueryParameter("fields", "files(id,name,createdTime,size)")
            .build()
        val response = executeWithRetry { request(url, "GET") }
        val files = JSONObject(response).getJSONArray("files")
        return (0 until files.length()).map { i ->
            val o = files.getJSONObject(i)
            DriveFile(
                id = o.getString("id"),
                name = o.getString("name"),
                createdTime = o.optString("createdTime"),
                size = if (o.has("size")) o.optLong("size") else null,
            )
        }
    }

    /** Downloads the raw content of [fileId]. */
    suspend fun download(fileId: String): String {
        val url = (baseUrl.trimEnd('/') + "/drive/v3/files/$fileId").toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()
        return executeWithRetry { request(url, "GET") }
    }

    /** Deletes [fileId]. */
    suspend fun delete(fileId: String) {
        val url = (baseUrl.trimEnd('/') + "/drive/v3/files/$fileId").toHttpUrl()
        executeWithRetry { request(url, "DELETE") }
    }

    /**
     * Keeps the newest [keep] files in [folderId] (by `createdTime desc`, i.e. Drive's own
     * ordering), deleting the rest. A delete failure is swallowed and does not stop the remaining
     * deletes — pruning must never fail a backup that already succeeded.
     */
    suspend fun prune(folderId: String, keep: Int = 10) {
        val files = list(folderId)
        for (file in files.drop(keep)) {
            runCatching { delete(file.id) }
        }
    }

    // -------------------------------------------------------------------
    // Request building + retry/auth
    // -------------------------------------------------------------------

    private suspend fun request(
        url: okhttp3.HttpUrl,
        method: String,
        body: RequestBody? = null,
    ): Request {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${token()}")
        return when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body!!)
            "DELETE" -> builder.delete()
            else -> error("unsupported method $method")
        }.build()
    }

    private sealed interface HttpOutcome {
        data class Body(val text: String) : HttpOutcome
        data class Error(val exception: DriveException) : HttpOutcome
    }

    /**
     * Sends the request built by [buildRequest] (called fresh on every attempt/retry so a refreshed
     * token or reauth is picked up), retrying up to [MAX_ATTEMPTS] times on 429/500-599/[IOException]
     * with linear backoff `backoffMs*(attempt+1)`.
     *
     * A 401/403 is handled *within* a single attempt, separately from that counter: on the first one
     * seen for this call, [onUnauthorized] is invoked and the request is rebuilt (fresh token) and
     * retried once immediately, without consuming a 429/5xx retry slot. A second 401/403 is terminal.
     */
    private suspend fun executeWithRetry(buildRequest: suspend () -> Request): String {
        val outcome = withContext(Dispatchers.IO) {
            var reauthed = false
            var lastError = HttpOutcome.Error(
                DriveException(DriveError.Unknown("no attempts made"), "no attempts made"),
            )
            for (attempt in 0 until MAX_ATTEMPTS) {
                try {
                    var response = client.newCall(buildRequest()).execute()
                    if ((response.code == 401 || response.code == 403) && !reauthed) {
                        response.close()
                        reauthed = true
                        onUnauthorized()
                        response = client.newCall(buildRequest()).execute()
                    }
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            return@withContext HttpOutcome.Body(resp.body?.string().orEmpty())
                        }
                        val code = resp.code
                        val retryable = code == 429 || code in 500..599
                        if (retryable && attempt < MAX_ATTEMPTS - 1) {
                            lastError = HttpOutcome.Error(mapHttpError(code, resp.body?.string().orEmpty()))
                        } else {
                            val errBody = resp.body?.string().orEmpty()
                            return@withContext HttpOutcome.Error(mapHttpError(code, errBody))
                        }
                    }
                } catch (e: IOException) {
                    val networkError = HttpOutcome.Error(DriveException(DriveError.Network, "Network error: ${e.message}"))
                    if (attempt >= MAX_ATTEMPTS - 1) return@withContext networkError
                    lastError = networkError
                }
                delay(backoffMs * (attempt + 1))
            }
            lastError
        }
        return when (outcome) {
            is HttpOutcome.Body -> outcome.text
            is HttpOutcome.Error -> throw outcome.exception
        }
    }

    private fun mapHttpError(code: Int, body: String): DriveException = when {
        code == 401 || code == 403 ->
            DriveException(DriveError.NeedsAuth, "Not authorized — sign in again.")
        code == 429 ->
            DriveException(DriveError.RateLimited, "Rate limited — try again in a moment.")
        code in 500..599 ->
            DriveException(DriveError.Server, "Google Drive had an error — try again.")
        else -> {
            val message = extractDriveError(body) ?: "Google Drive error (HTTP $code)."
            DriveException(DriveError.Unknown(message), message)
        }
    }

    private fun extractDriveError(body: String): String? =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }
            .getOrNull()?.takeIf { it.isNotBlank() }
}
