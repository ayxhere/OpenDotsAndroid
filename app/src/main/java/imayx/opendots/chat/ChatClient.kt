package imayx.opendots.chat

import android.util.Log
import imayx.opendots.api.ApiClient
import imayx.opendots.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "ChatClient"

/**
 * Minimal AG-UI client for the OpenDots CopilotKit endpoint.
 *
 * Talks to POST /api/copilotkit on the local backend, which proxies to the
 * real CopilotKit Intelligence gateway (Phoenix WS + AG-UI) server-side.
 * The backend returns 503 when INTELLIGENCE_API_KEY is missing — surfaced
 * honestly, never faked.
 */
class ChatClient(private val tokenStore: TokenStore) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // streaming
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed interface ChatEvent {
        data class TextDelta(val text: String) : ChatEvent
        data class RunFinished(val outcome: String?) : ChatEvent
        data class RunError(val message: String) : ChatEvent
        data object Started : ChatEvent
    }

    @Serializable
    private data class AgUiMessage(
        val id: String,
        val role: String,
        val content: String,
    )

    @Serializable
    private data class RunAgentInput(
        val threadId: String,
        val runId: String,
        val state: Map<String, String> = emptyMap(),
        val messages: List<AgUiMessage>,
        // CopilotKit runtime fields:
        val agentId: String? = null,
        val agentName: String? = null,
    )

    /**
     * Sends a user message and streams AG-UI events.
     * @param onEvent called for each parsed event on the caller's thread.
     * @return null on success, error message on failure.
     */
    suspend fun runMessage(
        dotId: String,
        threadId: String,
        userText: String,
        onEvent: (ChatEvent) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val token = tokenStore.getOrCreateOwnerToken()
        val input = RunAgentInput(
            threadId = threadId,
            runId = UUID.randomUUID().toString(),
            messages = listOf(
                AgUiMessage(
                    id = UUID.randomUUID().toString(),
                    role = "user",
                    content = userText,
                )
            ),
            agentId = dotId,
        )
        val body = json.encodeToString(RunAgentInput.serializer(), input)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("http://127.0.0.1:4310/api/copilotkit")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 503) {
                    val msg = try {
                        response.body?.string()?.let {
                            json.decodeFromString<Map<String, String>>(it)["error"]
                        }
                    } catch (_: Exception) { null }
                    return@withContext msg ?: "AI setup required: set INTELLIGENCE_API_KEY in Settings."
                }
                if (!response.isSuccessful) {
                    return@withContext "Backend error ${response.code}"
                }
                val source = response.body?.source()
                    ?: return@withContext "Empty response"
                onEvent(ChatEvent.Started)
                val buffer = StringBuilder()
                while (true) {
                    val line = try {
                        source.readUtf8Line() ?: break
                    } catch (e: Exception) {
                        Log.w(TAG, "Stream read error", e)
                        break
                    }
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        if (data.isEmpty()) continue
                        try {
                            val event = json.decodeFromString<Map<String, String>>(data)
                            when (event["type"]) {
                                "TEXT_MESSAGE_CONTENT" -> {
                                    event["delta"]?.let {
                                        if (it.isNotEmpty()) onEvent(ChatEvent.TextDelta(it))
                                    }
                                }
                                "RUN_FINISHED" -> {
                                    onEvent(ChatEvent.RunFinished(event["outcome"]))
                                    break
                                }
                                "RUN_ERROR" -> {
                                    onEvent(ChatEvent.RunError(event["message"] ?: "Unknown error"))
                                    return@withContext event["message"] ?: "Run failed"
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Skipping unparsable event: ${data.take(80)}")
                        }
                    } else if (line.isNotBlank()) {
                        buffer.append(line).append('\n')
                    }
                }
                // Fallback: some runtimes return JSON instead of SSE.
                if (buffer.isNotBlank()) {
                    try {
                        val obj = json.decodeFromString<Map<String, String>>(buffer.toString())
                        obj["response"]?.let { onEvent(ChatEvent.TextDelta(it)) }
                        onEvent(ChatEvent.RunFinished(null))
                    } catch (_: Exception) { }
                }
                return@withContext null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chat request failed", e)
            return@withContext "Connection failed: ${e.message}"
        }
    }

    /** Creates a conversation via the backend REST API. Returns the thread ID or null. */
    suspend fun createConversation(dotId: String, title: String): String? =
        withContext(Dispatchers.IO) {
            val api = ApiClient(tokenStore)
            when (val r = api.createConversation(dotId, title.take(120))) {
                is ApiClient.ApiResult.Ok -> r.value.id
                is ApiClient.ApiResult.HttpError -> {
                    Log.w(TAG, "createConversation HTTP ${r.code}: ${r.message}")
                    null
                }
                is ApiClient.ApiResult.NetworkError -> {
                    Log.w(TAG, "createConversation network: ${r.message}")
                    null
                }
            }
        }
}
