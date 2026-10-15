package imayx.opendots.api

import imayx.opendots.auth.TokenStore
import imayx.opendots.node.NodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Authenticated HTTP client for the embedded backend (127.0.0.1:4310 only).
 *
 * - Bearer <per-install token> on every call (matches the server's
 *   OWNER_TOKEN timingSafeEqual check).
 * - No header/query logging anywhere: the token must never reach logcat.
 * - Loopback-only base URL; the network security config forbids all other
 *   cleartext, and the server refuses non-loopback Hosts.
 */
class ApiClient(
    private val tokenStore: TokenStore,
    baseUrl: String = "http://127.0.0.1:${NodeManager.BACKEND_PORT}/api",
) {
    private val baseUrl = baseUrl.trimEnd('/')
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Long-lived client for the chat event stream (no read timeout). */
    val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed interface ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>
        data class HttpError(val code: Int, val message: String) : ApiResult<Nothing>
        data class NetworkError(val message: String) : ApiResult<Nothing>
    }

    private fun authed(path: String, method: String, body: String? = null): Request {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer ${tokenStore.getOrCreateOwnerToken()}")
            .header("Accept", "application/json")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> {
                val rb = (body ?: "{}").toRequestBody("application/json".toMediaType())
                when (method) {
                    "POST" -> builder.post(rb)
                    "PUT" -> builder.put(rb)
                    "PATCH" -> builder.patch(rb)
                    else -> builder.method(method, rb)
                }
            }
        }
        return builder.build()
    }

    @PublishedApi
    internal suspend fun raw(path: String, method: String = "GET", body: String? = null): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(authed(path, method, body)).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) ApiResult.Ok(text)
                    else ApiResult.HttpError(resp.code, extractMessage(text) ?: "HTTP ${resp.code}")
                }
            } catch (t: Throwable) {
                ApiResult.NetworkError(t.message ?: "Network failure")
            }
        }

    private fun extractMessage(body: String): String? = try {
        json.decodeFromString<ApiError>(body).let { it.error ?: it.message }
    } catch (_: Throwable) {
        null
    }

    suspend inline fun <reified T> getParsed(path: String): ApiResult<T> =
        when (val r = raw(path)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<T>(r.value))
            } catch (t: Throwable) {
                ApiResult.HttpError(-1, "Bad response: ${t.message}")
            }
            is ApiResult.HttpError -> r
            is ApiResult.NetworkError -> r
        }

    suspend inline fun <reified T> postParsed(path: String, body: String): ApiResult<T> =
        when (val r = raw(path, "POST", body)) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<T>(r.value))
            } catch (t: Throwable) {
                ApiResult.HttpError(-1, "Bad response: ${t.message}")
            }
            is ApiResult.HttpError -> r
            is ApiResult.NetworkError -> r
        }

    // ---- OpenDots endpoints ----

    suspend fun getHealth(): ApiResult<HealthResponse> = getParsed("/health")

    suspend fun getState(): ApiResult<ApiState> = getParsed("/state")

    suspend fun getWorkspace(): ApiResult<WorkspaceResponse> = getParsed("/workspace")

    suspend fun createSpace(name: String, description: String? = null): ApiResult<Space> {
        val body = buildJsonString("name" to name, "description" to description)
        return postParsed("/spaces", body)
    }

    suspend fun createDot(
        name: String,
        instructions: String,
        spaceId: String,
        researchAllowed: Boolean = false,
        memoryAllowed: Boolean = false,
    ): ApiResult<Dot> {
        val body = buildJsonString(
            "name" to name,
            "instructions" to instructions,
            "spaceId" to spaceId,
            "researchAllowed" to researchAllowed,
            "memoryAllowed" to memoryAllowed,
        )
        return postParsed("/dots", body)
    }

    suspend fun createConversation(dotId: String, title: String? = null): ApiResult<Conversation> =
        postParsed("/conversations", buildJsonString("dotId" to dotId, "title" to title))

    /**
     * Approves a page draft by creating the reviewed page.
     * The backend enforces TTL and idempotency via the toolCallId.
     */
    suspend fun postReviewedPage(
        conversationId: String,
        toolCallId: String,
        spaceId: String,
        title: String,
        content: String,
    ): ApiResult<Unit> =
        when (val r = raw(
            "/conversations/$conversationId/reviewed-page",
            "POST",
            buildJsonString(
                "toolCallId" to toolCallId,
                "spaceId" to spaceId,
                "title" to title,
                "content" to content
            )
        )) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.HttpError -> r
            is ApiResult.NetworkError -> r
        }

    /**
     * Approves a connection action.
     * The backend enforces TTL and idempotency via the toolCallId.
     */
    suspend fun postConnectionAction(
        conversationId: String,
        toolCallId: String,
    ): ApiResult<Unit> =
        when (val r = raw(
            "/conversations/$conversationId/connection-actions",
            "POST",
            buildJsonString("toolCallId" to toolCallId)
        )) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.HttpError -> r
            is ApiResult.NetworkError -> r
        }

    suspend fun listPages(spaceId: String): ApiResult<List<Page>> =
        getParsed("/spaces/$spaceId/pages")

    suspend fun getPage(spaceId: String, pageId: String): ApiResult<Page> =
        getParsed("/spaces/$spaceId/pages/$pageId")

    suspend fun createPage(spaceId: String, title: String, content: String = "", parentId: String? = null): ApiResult<Page> =
        postParsed("/spaces/$spaceId/pages", buildJsonString("title" to title, "content" to content, "parentId" to parentId))

    suspend fun patchPage(spaceId: String, pageId: String, title: String, content: String, expectedRevision: Int): ApiResult<Page> =
        when (val r = raw("/spaces/$spaceId/pages/$pageId", "PATCH",
            buildJsonString("title" to title, "content" to content, "expectedRevision" to expectedRevision))) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(json.decodeFromString<Page>(r.value))
            } catch (t: Throwable) {
                ApiResult.HttpError(-1, "Bad response: ${t.message}")
            }
            is ApiResult.HttpError -> r
            is ApiResult.NetworkError -> r
        }

    suspend fun deletePage(spaceId: String, pageId: String): ApiResult<Unit> =
        when (val r = raw("/spaces/$spaceId/pages/$pageId", "DELETE")) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.HttpError -> r
            is ApiResult.NetworkError -> r
        }

    private fun buildJsonString(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in pairs) {
            if (v == null) continue
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(k).append("\":")
            when (v) {
                is String -> sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                is Boolean, is Number -> sb.append(v.toString())
                else -> sb.append('"').append(v.toString().replace("\"", "\\\"")).append('"')
            }
        }
        return sb.append('}').toString()
    }
}
