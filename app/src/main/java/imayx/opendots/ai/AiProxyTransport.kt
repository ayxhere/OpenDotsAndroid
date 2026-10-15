package imayx.opendots.ai

import android.util.Log
import imayx.opendots.auth.TokenStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

private const val TAG = "AiProxyTransport"

/**
 * [AiChatTransport] that routes every attempt through the embedded backend's
 * `POST /api/ai/chat` proxy (authenticated with the OWNER_TOKEN).
 *
 * The API key travels in the POST body over loopback only, is never logged,
 * and is never persisted by the backend. Upstream SSE is normalized by the
 * backend to `data: {"type": ...}` events.
 */
class AiProxyTransport(
    private val tokenStore: TokenStore,
    private val baseUrl: String = "http://127.0.0.1:4310/api",
) : AiChatTransport {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // streaming; bounded by call timeout
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Serializable
    private data class ProxyMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val provider: String,
        val baseUrl: String? = null,
        val apiKey: String,
        val model: String,
        val messages: List<ProxyMessage>,
        val stream: Boolean = true,
    )

    @Serializable
    private data class ProxyEvent(
        val type: String,
        val text: String? = null,
        val model: String? = null,
        val usage: ProxyUsage? = null,
        val code: String? = null,
        val message: String? = null,
        val retryable: Boolean? = null,
    )

    @Serializable
    private data class ProxyUsage(
        val input: Int = 0,
        val output: Int = 0,
    )

    private fun providerId(type: AiProviderType): String = when (type) {
        AiProviderType.OPENAI -> "openai"
        AiProviderType.ANTHROPIC -> "anthropic"
        AiProviderType.GEMINI -> "gemini"
        AiProviderType.GROQ -> "groq"
        AiProviderType.OPENROUTER -> "openrouter"
        AiProviderType.CUSTOM -> "custom"
    }

    private fun failure(
        config: AiProviderConfig, code: String, message: String, retryable: Boolean,
    ) = AiFailure(
        code = code,
        message = message,
        retryable = retryable,
        providerId = config.id,
        model = config.model,
    )

    override suspend fun streamChat(
        config: AiProviderConfig,
        apiKey: String,
        messages: List<AiMessage>,
        timeoutSec: Int,
        onDelta: (String) -> Unit,
    ): AiChatTransport.Usage = suspendCancellableCoroutine { cont ->
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                provider = providerId(config.type),
                baseUrl = config.baseUrl?.takeIf { it.isNotBlank() },
                apiKey = apiKey,
                model = config.model,
                messages = messages.map { ProxyMessage(it.role, it.content) },
                stream = true,
            ),
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/ai/chat")
            .header("Authorization", "Bearer ${tokenStore.getOrCreateOwnerToken()}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val call: Call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        Thread {
            var usage = AiChatTransport.Usage(0, 0, config.model)
            var failed: AiTransportException? = null
            try {
                call.execute().use { response ->
                    if (response.code == 401) {
                        failed = AiTransportException(
                            failure(config, "backend_auth", "Backend rejected the owner token.", false)
                        )
                        return@use
                    }
                    val source = response.body?.source()
                    if (!response.isSuccessful || source == null) {
                        // Try to read a JSON error envelope from the proxy.
                        val errText = try { response.body?.string() } catch (_: Exception) { null }
                        val parsed = try {
                            errText?.let { json.decodeFromString<ProxyEvent>(it) }
                        } catch (_: Exception) { null }
                        failed = AiTransportException(
                            failure(
                                config,
                                parsed?.code ?: "upstream_error",
                                parsed?.message ?: "Backend proxy returned HTTP ${response.code}.",
                                parsed?.retryable ?: (response.code >= 500),
                            )
                        )
                        return@use
                    }
                    while (true) {
                        val line = try {
                            source.readUtf8Line() ?: break
                        } catch (e: Exception) {
                            if (cont.isCancelled) break
                            Log.w(TAG, "stream read error", e)
                            failed = AiTransportException(
                                failure(config, "network", "Stream interrupted: ${e.message}", true)
                            )
                            break
                        }
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") continue
                        val event = try {
                            json.decodeFromString<ProxyEvent>(data)
                        } catch (_: Exception) {
                            continue
                        }
                        when (event.type) {
                            "delta" -> event.text?.let { onDelta(it) }
                            "done" -> {
                                usage = AiChatTransport.Usage(
                                    inputTokens = event.usage?.input ?: 0,
                                    outputTokens = event.usage?.output ?: 0,
                                    model = event.model ?: config.model,
                                )
                            }
                            "error" -> {
                                failed = AiTransportException(
                                    failure(
                                        config,
                                        event.code ?: "upstream_error",
                                        event.message ?: "Provider error.",
                                        event.retryable ?: true,
                                    )
                                )
                                // Continue reading? No — proxy closes after error.
                                return@use
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (cont.isCancelled) return@Thread
                Log.w(TAG, "proxy call failed", e)
                failed = AiTransportException(
                    failure(config, "network", "Could not reach the local backend: ${e.message}", true)
                )
            }
            val f = failed
            if (f != null) cont.resumeWithException(f)
            else cont.resume(usage) { cause ->
                Log.d(TAG, "streamChat cancelled after completion: $cause")
            }
        }.start()
    }

    /** Non-streaming probe used by "Test connection". Never fabricates success. */
    suspend fun testConnection(
        type: AiProviderType,
        upstreamBaseUrl: String?,
        apiKey: String,
        model: String,
    ): TestResult = suspendCancellableCoroutine { cont ->
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                provider = when (type) {
                    AiProviderType.OPENAI -> "openai"
                    AiProviderType.ANTHROPIC -> "anthropic"
                    AiProviderType.GEMINI -> "gemini"
                    AiProviderType.GROQ -> "groq"
                    AiProviderType.OPENROUTER -> "openrouter"
                    AiProviderType.CUSTOM -> "custom"
                },
                baseUrl = upstreamBaseUrl?.takeIf { it.isNotBlank() },
                apiKey = apiKey,
                model = model,
                messages = emptyList(),
                stream = false,
            ),
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${this@AiProxyTransport.baseUrl}/ai/test")
            .header("Authorization", "Bearer ${tokenStore.getOrCreateOwnerToken()}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        Thread {
            try {
                call.execute().use { response ->
                    val text = response.body?.string() ?: ""
                    val parsed = try {
                        json.decodeFromString<TestResponse>(text)
                    } catch (_: Exception) { null }
                    if (parsed != null) {
                        cont.resume(
                            TestResult(
                                ok = parsed.ok,
                                message = if (parsed.ok)
                                    "OK · ${parsed.latencyMs}ms"
                                else
                                    "${parsed.code ?: "error"}: ${parsed.error ?: "failed"}",
                                retryable = parsed.retryable ?: false,
                            )
                        ) { _ -> }
                    } else {
                        cont.resume(
                            TestResult(false, "Backend returned HTTP ${response.code}.", false)
                        ) { _ -> }
                    }
                }
            } catch (e: Exception) {
                if (!cont.isCancelled) {
                    cont.resume(TestResult(false, "No route to backend: ${e.message}", true)) { _ -> }
                }
            }
        }.start()
    }

    @Serializable
    private data class TestResponse(
        val ok: Boolean,
        val model: String? = null,
        @SerialName("latencyMs") val latencyMs: Long? = null,
        val sample: String? = null,
        val error: String? = null,
        val code: String? = null,
        val retryable: Boolean? = null,
    )

    data class TestResult(val ok: Boolean, val message: String, val retryable: Boolean)

    /** Fetches the provider's model list through the proxy (key in body). */
    suspend fun fetchModels(
        type: AiProviderType,
        upstreamBaseUrl: String?,
        apiKey: String,
    ): Result<List<String>> = suspendCancellableCoroutine { cont ->
        val payload = json.encodeToString(
            ModelsRequest.serializer(),
            ModelsRequest(
                provider = when (type) {
                    AiProviderType.OPENAI -> "openai"
                    AiProviderType.ANTHROPIC -> "anthropic"
                    AiProviderType.GEMINI -> "gemini"
                    AiProviderType.GROQ -> "groq"
                    AiProviderType.OPENROUTER -> "openrouter"
                    AiProviderType.CUSTOM -> "custom"
                },
                baseUrl = upstreamBaseUrl?.takeIf { it.isNotBlank() },
                apiKey = apiKey,
            ),
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${this@AiProxyTransport.baseUrl}/ai/models")
            .header("Authorization", "Bearer ${tokenStore.getOrCreateOwnerToken()}")
            .header("Content-Type", "application/json")
            .post(payload)
            .build()
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        Thread {
            try {
                call.execute().use { response ->
                    val text = response.body?.string() ?: ""
                    try {
                        val parsed = json.decodeFromString<ModelsResponse>(text)
                        if (parsed.models != null) {
                            cont.resume(Result.success(parsed.models.map { it.id })) { _ -> }
                        } else {
                            cont.resume(
                                Result.failure(
                                    Exception(parsed.error ?: "Backend HTTP ${response.code}")
                                )
                            ) { _ -> }
                        }
                    } catch (e: Exception) {
                        cont.resume(Result.failure(Exception("Could not parse model list."))) { _ -> }
                    }
                }
            } catch (e: Exception) {
                if (!cont.isCancelled) cont.resume(Result.failure(e)) { _ -> }
            }
        }.start()
    }
}

@kotlinx.serialization.Serializable
private data class ModelsRequest(
    val provider: String,
    val baseUrl: String? = null,
    val apiKey: String,
)

@kotlinx.serialization.Serializable
private data class ModelsResponse(
    val models: List<ModelEntry>? = null,
    val error: String? = null,
    val code: String? = null,
)

@kotlinx.serialization.Serializable
private data class ModelEntry(val id: String, val name: String? = null)
