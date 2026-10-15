package imayx.opendots.ai

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Multi-provider AI system: provider types, configs, error classification,
 * and cost metadata. Provider API keys live in [imayx.opendots.auth.TokenStore]
 * under `provider_key_<configId>` — never in these configs, never logged.
 */

/** Provider backends the app can talk to (through the local AI proxy). */
enum class AiProviderType(val displayName: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic Claude"),
    GEMINI("Google Gemini"),
    GROQ("Groq"),
    OPENROUTER("OpenRouter"),
    CUSTOM("Custom (OpenAI-compatible)"),
}

/**
 * One configured provider: metadata + pointer to the Keystore-held key.
 * [keyAlias] is the TokenStore secret name, never the key itself.
 */
@Serializable
data class AiProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: AiProviderType,
    /** Only used for CUSTOM; known providers use fixed backend URLs. */
    val baseUrl: String? = null,
    val model: String,
    val keyAlias: String,
    val enabled: Boolean = true,
    /** Lower = tried first. */
    val priority: Int = 0,
    /** Free-model-only fallback: this entry is only eligible for free models. */
    val freeOnly: Boolean = false,
)

/** A single chat message in provider-neutral form. */
@Serializable
data class AiMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: String,
)

/** Classified failure from a provider attempt. */
data class AiFailure(
    /** Stable code from the backend proxy: invalid_key, quota_exceeded, rate_limited, model_not_found, upstream_error, timeout, blocked_url, invalid_request, network, cancelled. */
    val code: String,
    val message: String,
    /** True → the router may try the next provider. */
    val retryable: Boolean,
    val providerId: String,
    val model: String,
) {
    /** Invalid credentials / quota must never be retried, on any provider. */
    val isAuthFailure: Boolean get() = code == "invalid_key" || code == "quota_exceeded"
}

/** Which provider answered a chat, for the UI badge. */
data class AiAnswer(
    val providerId: String,
    val providerName: String,
    val model: String,
    val fallbackOccurred: Boolean,
    val attempts: Int,
)

/** Events emitted while a routed chat runs. */
sealed interface AiChatEvent {
    data class TextDelta(val text: String) : AiChatEvent
    data class Answered(val answer: AiAnswer) : AiChatEvent
    data class AttemptFailed(val failure: AiFailure, val willRetry: Boolean) : AiChatEvent
    data class Failed(val failures: List<AiFailure>) : AiChatEvent
}

/**
 * Rough per-1M-token USD pricing for common models, used ONLY for the
 * optional max-cost guard. Missing entries are treated as "price unknown"
 * (cost guard counts them as 0 and marks the estimate unreliable).
 * Values are approximate and informational, not billing data.
 */
object AiPricing {
    private data class Price(val inputPer1M: Double, val outputPer1M: Double)

    private val table = mapOf(
        // OpenAI
        "gpt-4o-mini" to Price(0.15, 0.60),
        "gpt-4o" to Price(2.50, 10.00),
        "gpt-4.1-mini" to Price(0.40, 1.60),
        "gpt-4.1" to Price(2.00, 8.00),
        // Anthropic
        "claude-haiku-4-5" to Price(1.00, 5.00),
        "claude-sonnet-4-5" to Price(3.00, 15.00),
        "claude-3-5-haiku-latest" to Price(0.80, 4.00),
        // Google
        "gemini-2.0-flash" to Price(0.10, 0.40),
        "gemini-2.5-flash" to Price(0.30, 2.50),
        "gemini-2.5-pro" to Price(1.25, 10.00),
        // Groq (many free / low cost)
        "llama-3.3-70b-versatile" to Price(0.0, 0.0),
        "llama-3.1-8b-instant" to Price(0.0, 0.0),
    )

    /** Models we know are free (price 0 entries + OpenRouter :free suffix). */
    fun isFreeModel(model: String): Boolean {
        if (model.endsWith(":free", ignoreCase = true)) return true
        return table[model]?.let { it.inputPer1M == 0.0 && it.outputPer1M == 0.0 } ?: false
    }

    /** Estimated USD cost, or null when the model has no pricing data. */
    fun estimateCostUsd(model: String, inputTokens: Int, outputTokens: Int): Double? {
        val p = table[model] ?: return null
        return (inputTokens * p.inputPer1M + outputTokens * p.outputPer1M) / 1_000_000.0
    }

    fun hasPricing(model: String): Boolean = table.containsKey(model)
}
