package imayx.opendots.api

import imayx.opendots.auth.TokenStore
import imayx.opendots.node.NodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    // Short-TTL in-memory cache for the workspace (spaces/dots/conversations).
    // Survives rapid tab switches without refetching; mutations invalidate it.
    // Honest by construction: TTL is 30s and every mutation clears it.
    private var workspaceCache: Pair<Long, WorkspaceResponse>? = null
    private val workspaceCacheTtlMs = 30_000L

    private fun invalidateWorkspaceCache() { workspaceCache = null }

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

    suspend fun getWorkspace(forceRefresh: Boolean = false): ApiResult<WorkspaceResponse> {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = workspaceCache
        if (!forceRefresh && cached != null && now - cached.first < workspaceCacheTtlMs) {
            return ApiResult.Ok(cached.second)
        }
        return when (val r = getParsed<WorkspaceResponse>("/workspace")) {
            is ApiResult.Ok -> {
                workspaceCache = now to r.value
                r
            }
            else -> r
        }
    }

    suspend fun createSpace(name: String, description: String? = null): ApiResult<Space> {
        val body = buildJsonString("name" to name, "description" to description)
        return when (val r = postParsed<Space>("/spaces", body)) {
            is ApiResult.Ok -> { invalidateWorkspaceCache(); r }
            else -> r
        }
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
        return when (val r = postParsed<Dot>("/dots", body)) {
            is ApiResult.Ok -> { invalidateWorkspaceCache(); r }
            else -> r
        }
    }

    suspend fun createConversation(dotId: String, title: String? = null): ApiResult<Conversation> =
        when (val r = postParsed<Conversation>("/conversations", buildJsonString("dotId" to dotId, "title" to title))) {
            is ApiResult.Ok -> { invalidateWorkspaceCache(); r }
            else -> r
        }

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

}

/**
 * Builds a JSON object string with correct escaping (newlines, quotes,
 * control characters). The previous hand-rolled builder emitted invalid JSON
 * for multiline page content. Top-level so unit tests can call it without an
 * [ApiClient] instance (which needs an Android Context for its TokenStore).
 */
internal fun buildJsonString(vararg pairs: Pair<String, Any?>): String =
    buildJsonObject {
        for ((k, v) in pairs) {
            when (v) {
                null -> Unit // skip absent optionals
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Double -> put(k, v)
                is Float -> put(k, v)
                is Number -> put(k, v.toDouble())
                else -> put(k, v.toString())
            }
        }
    }.toString()
