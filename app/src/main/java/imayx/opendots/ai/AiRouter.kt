package imayx.opendots.ai

import kotlinx.coroutines.CancellationException

/**
 * Reliable multi-provider chat router.
 *
 * - Tries providers in priority order (or the user's selected provider first,
 *   then the rest as fallback).
 * - Each provider is tried AT MOST ONCE per chat call: invalid credentials
 *   are never retried, on any provider.
 * - Failover triggers on timeouts, rate limits, outages and unavailable
 *   models. Auth/quota failures mark the provider bad for the session and
 *   move on to the next provider.
 * - Conversation [messages] are passed to every attempt unchanged, so
 *   context is preserved across failover. Attempts are strictly sequential
 *   (single-flight), so no duplicate messages are ever sent.
 * - This path is plain chat completions (no tool calls), so duplicate tool
 *   execution cannot happen here by construction.
 * - Cost guard: optional per-call USD cap; paid→paid fallback is only
 *   allowed when it does not breach the cap, and a free-model-only fallback
 *   mode is supported.
 * - When every provider fails, emits [AiChatEvent.Failed] — the UI must show
 *   the real errors, never a fabricated response.
 */
class AiRouter(
    private val transport: AiChatTransport,
    private val keyFor: (AiProviderConfig) -> String?,
) {
    /** Tracks spend across turns; share one instance for a whole conversation. */
    class CostTracker {
        var spentUsd: Double = 0.0
            private set
        var estimateUnreliable: Boolean = false
            private set

        fun add(model: String, inputTokens: Int, outputTokens: Int) {
            val est = AiPricing.estimateCostUsd(model, inputTokens, outputTokens)
            if (est == null) estimateUnreliable = true else spentUsd += est
        }
    }

    /**
     * Runs one chat turn with failover.
     *
     * @param configs all provider configs (any order; sorted by priority here).
     * @param selectedProviderId when set, that provider is tried first and the
     * rest become fallbacks; when null, pure priority order is used.
     * @param costTracker shared spend tracker (optional).
     * @param isCancelled cooperative cancellation checked between attempts.
     */
    suspend fun chat(
        configs: List<AiProviderConfig>,
        settings: AiRouterSettings,
        messages: List<AiMessage>,
        selectedProviderId: String? = null,
        costTracker: CostTracker = CostTracker(),
        isCancelled: () -> Boolean = { false },
        onEvent: (AiChatEvent) -> Unit,
    ) {
        val ordered = configs.filter { it.enabled }.sortedBy { it.priority }
        if (ordered.isEmpty()) {
            onEvent(
                AiChatEvent.Failed(
                    listOf(
                        AiFailure(
                            code = "no_providers",
                            message = "No AI providers are configured or enabled. Add one in Settings → AI Providers.",
                            retryable = false,
                            providerId = "",
                            model = "",
                        )
                    )
                )
            )
            return
        }
        val queue = if (selectedProviderId != null) {
            val sel = ordered.find { it.id == selectedProviderId }
            if (sel != null) listOf(sel) + ordered.filterNot { it.id == sel.id }
            else ordered
        } else ordered

        val failures = mutableListOf<AiFailure>()
        val authFailed = mutableSetOf<String>() // provider ids: never retry these
        var attempts = 0
        var fallbackOccurred = false

        for (config in queue) {
            if (isCancelled()) throw CancellationException("cancelled")
            if (attempts >= settings.maxAttempts) break
            if (config.id in authFailed) continue
            // Free-model-only fallback: after the first attempt, only free models.
            val isFallbackAttempt = attempts > 0
            if (isFallbackAttempt) fallbackOccurred = true
            if ((settings.freeModelOnlyFallback || config.freeOnly) && isFallbackAttempt &&
                !AiPricing.isFreeModel(config.model)
            ) {
                continue
            }
            // Cost guard: stop before breaching the cap.
            val cap = settings.maxCostUsd
            if (cap != null && costTracker.spentUsd >= cap) {
                failures.add(
                    AiFailure(
                        code = "cost_cap",
                        message = "Stopped: the USD %.2f cost cap for this run was reached.".format(cap),
                        retryable = false,
                        providerId = config.id,
                        model = config.model,
                    )
                )
                break
            }
            val apiKey = keyFor(config)
            if (apiKey.isNullOrEmpty()) {
                val f = AiFailure(
                    code = "missing_key",
                    message = "No API key stored for “${config.name}”.",
                    retryable = false,
                    providerId = config.id,
                    model = config.model,
                )
                failures.add(f)
                onEvent(AiChatEvent.AttemptFailed(f, willRetry = true))
                continue
            }
            attempts++
            val fullText = StringBuilder()
            try {
                val usage = transport.streamChat(
                    config = config,
                    apiKey = apiKey,
                    messages = messages, // unchanged across attempts: no duplicates
                    timeoutSec = settings.attemptTimeoutSec,
                    onDelta = { d ->
                        fullText.append(d)
                        onEvent(AiChatEvent.TextDelta(d))
                    },
                )
                costTracker.add(usage.model, usage.inputTokens, usage.outputTokens)
                onEvent(
                    AiChatEvent.Answered(
                        AiAnswer(
                            providerId = config.id,
                            providerName = config.name,
                            model = usage.model,
                            fallbackOccurred = fallbackOccurred,
                            attempts = attempts,
                        )
                    )
                )
                return
            } catch (ce: CancellationException) {
                throw ce // never convert cancellation into a failure event
            } catch (te: AiTransportException) {
                val f = te.failure
                failures.add(f)
                if (f.isAuthFailure) authFailed.add(config.id)
                val moreEligible = queue.any { it.id != config.id && it.id !in authFailed && it.enabled }
                val willRetry = settings.autoFailover &&
                    attempts < settings.maxAttempts &&
                    moreEligible &&
                    f.code != "cost_cap"
                onEvent(AiChatEvent.AttemptFailed(f, willRetry))
                if (!willRetry) break
            }
        }
        onEvent(AiChatEvent.Failed(failures))
    }
}

/** One proxied provider attempt. Implementations must honor coroutine cancellation. */
interface AiChatTransport {
    data class Usage(val inputTokens: Int, val outputTokens: Int, val model: String)

    @Throws(AiTransportException::class, CancellationException::class)
    suspend fun streamChat(
        config: AiProviderConfig,
        apiKey: String,
        messages: List<AiMessage>,
        timeoutSec: Int,
        onDelta: (String) -> Unit,
    ): Usage
}

/** Transport-level failure; the router converts these into [AiChatEvent]s. */
class AiTransportException(val failure: AiFailure) : Exception(failure.message)
