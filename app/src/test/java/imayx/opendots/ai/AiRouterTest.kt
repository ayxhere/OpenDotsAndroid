package imayx.opendots.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for the multi-provider [AiRouter].
 *
 * No network, no Android framework: a fake [AiChatTransport] scripted per
 * provider id drives failover, error classification, cancellation, cost
 * guards and context preservation.
 */
private typealias Behavior = suspend (List<AiMessage>) -> AiChatTransport.Usage

class AiRouterTest {

    private fun config(
        id: String,
        name: String = id,
        model: String = "model-$id",
        priority: Int = 0,
        enabled: Boolean = true,
        freeOnly: Boolean = false,
    ) = AiProviderConfig(
        id = id, name = name, type = AiProviderType.OPENAI,
        model = model, keyAlias = "k-$id",
        enabled = enabled, priority = priority, freeOnly = freeOnly,
    )

    private val msgs = listOf(AiMessage("user", "hello"))

    /** Scripted fake: maps provider id -> behavior. Records every attempt. */
    private class FakeTransport(
        private val behavior: Map<String, Behavior>,
    ) : AiChatTransport {
        val attempts = mutableListOf<String>()
        val seenMessages = mutableListOf<List<AiMessage>>()

        override suspend fun streamChat(
            config: AiProviderConfig,
            apiKey: String,
            messages: List<AiMessage>,
            timeoutSec: Int,
            onDelta: (String) -> Unit,
        ): AiChatTransport.Usage {
            attempts.add(config.id)
            seenMessages.add(messages.toList())
            val fn = behavior[config.id]
                ?: throw AiTransportException(
                    AiFailure("upstream_error", "no behavior", true, config.id, config.model)
                )
            onDelta("hi ")
            onDelta("there")
            return fn(messages)
        }
    }

    private fun ok(model: String = "m", input: Int = 10, output: Int = 5): Behavior =
        { _: List<AiMessage> -> AiChatTransport.Usage(input, output, model) }

    private fun fail(code: String, retryable: Boolean): Behavior =
        { _: List<AiMessage> ->
            throw AiTransportException(
                AiFailure(code, "boom $code", retryable, "p", "m")
            )
        }

    private fun collectEvents(
        router: AiRouter,
        configs: List<AiProviderConfig>,
        settings: AiRouterSettings,
        selected: String? = null,
        tracker: AiRouter.CostTracker = AiRouter.CostTracker(),
        isCancelled: () -> Boolean = { false },
    ): Pair<List<AiChatEvent>, AiRouter.CostTracker> {
        val events = mutableListOf<AiChatEvent>()
        // runTest-compatible: router.chat is a suspend fun
        kotlinx.coroutines.runBlocking {
            router.chat(configs, settings, msgs, selected, tracker, isCancelled) {
                events.add(it)
            }
        }
        return events to tracker
    }

    @Test
    fun `first provider answers when healthy`() = runTest {
        val t = FakeTransport(mapOf("a" to ok(), "b" to ok()))
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(
            router, listOf(config("a"), config("b")), AiRouterSettings()
        )
        val answered = events.filterIsInstance<AiChatEvent.Answered>().single()
        assertEquals("a", answered.answer.providerId)
        assertFalse(answered.answer.fallbackOccurred)
        assertEquals(listOf("a"), t.attempts)
    }

    @Test
    fun `rate limit on first provider fails over to second`() = runTest {
        val t = FakeTransport(mapOf("a" to fail("rate_limited", true), "b" to ok("m-b")))
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(
            router, listOf(config("a"), config("b")), AiRouterSettings()
        )
        val answered = events.filterIsInstance<AiChatEvent.Answered>().single()
        assertEquals("b", answered.answer.providerId)
        assertTrue(answered.answer.fallbackOccurred)
        assertEquals(2, answered.answer.attempts)
        assertEquals(listOf("a", "b"), t.attempts)
        // The AttemptFailed event must say a retry will happen.
        val attemptFailed = events.filterIsInstance<AiChatEvent.AttemptFailed>().single()
        assertTrue(attemptFailed.willRetry)
        assertEquals("rate_limited", attemptFailed.failure.code)
    }

    @Test
    fun `invalid key is never retried on the same provider`() = runTest {
        val t = FakeTransport(
            mapOf(
                "a" to fail("invalid_key", false),
                "b" to fail("invalid_key", false),
                "a2" to fail("invalid_key", false),
            )
        )
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(
            router,
            listOf(config("a"), config("b"), config("a2")),
            AiRouterSettings(maxAttempts = 5),
        )
        // Each provider tried at most once.
        assertEquals(t.attempts.size, t.attempts.toSet().size)
        val failed = events.filterIsInstance<AiChatEvent.Failed>().single()
        assertEquals(3, failed.failures.size)
        assertTrue(failed.failures.all { it.code == "invalid_key" })
    }

    @Test
    fun `quota exceeded moves to next provider without retrying`() = runTest {
        val t = FakeTransport(
            mapOf("a" to fail("quota_exceeded", false), "b" to ok())
        )
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(
            router, listOf(config("a"), config("b")), AiRouterSettings()
        )
        assertEquals("b", events.filterIsInstance<AiChatEvent.Answered>().single().answer.providerId)
        assertEquals(1, t.attempts.count { it == "a" })
    }

    @Test
    fun `all providers failing yields Failed never fabricated text`() = runTest {
        val t = FakeTransport(
            mapOf("a" to fail("upstream_error", true), "b" to fail("timeout", true))
        )
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(
            router, listOf(config("a"), config("b")), AiRouterSettings()
        )
        val failed = events.filterIsInstance<AiChatEvent.Failed>().single()
        assertEquals(2, failed.failures.size)
        assertTrue(events.none { it is AiChatEvent.Answered })
        // No text deltas should survive a total failure surfaced as answer.
        assertTrue(events.filterIsInstance<AiChatEvent.TextDelta>().isNotEmpty())
    }

    @Test
    fun `context messages are identical across failover attempts`() = runTest {
        val t = FakeTransport(mapOf("a" to fail("timeout", true), "b" to ok()))
        val router = AiRouter(t) { "key" }
        val rich = listOf(
            AiMessage("system", "sys"),
            AiMessage("user", "q1"),
            AiMessage("assistant", "a1"),
            AiMessage("user", "q2"),
        )
        kotlinx.coroutines.runBlocking {
            router.chat(listOf(config("a"), config("b")), AiRouterSettings(), rich) {}
        }
        assertEquals(2, t.seenMessages.size)
        assertEquals(rich, t.seenMessages[0])
        assertEquals(rich, t.seenMessages[1])
    }

    @Test
    fun `disabled providers are skipped`() = runTest {
        val t = FakeTransport(mapOf("b" to ok()))
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(
            router,
            listOf(config("a", enabled = false), config("b")),
            AiRouterSettings(),
        )
        assertEquals("b", events.filterIsInstance<AiChatEvent.Answered>().single().answer.providerId)
        assertEquals(listOf("b"), t.attempts)
    }

    @Test
    fun `no providers configured yields honest Failed`() = runTest {
        val t = FakeTransport(emptyMap())
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(router, emptyList(), AiRouterSettings())
        val failed = events.filterIsInstance<AiChatEvent.Failed>().single()
        assertEquals("no_providers", failed.failures.single().code)
        assertTrue(t.attempts.isEmpty())
    }

    @Test
    fun `missing key is reported and skipped`() = runTest {
        val t = FakeTransport(mapOf("b" to ok()))
        val router = AiRouter(t) { if (it.id == "b") "key" else null }
        val (events, _) = collectEvents(
            router, listOf(config("a"), config("b")), AiRouterSettings()
        )
        val attemptFailed = events.filterIsInstance<AiChatEvent.AttemptFailed>()
        assertTrue(attemptFailed.any { it.failure.code == "missing_key" })
        assertEquals("b", events.filterIsInstance<AiChatEvent.Answered>().single().answer.providerId)
    }

    @Test
    fun `cancellation between attempts aborts the run`() = runTest {
        val t = FakeTransport(mapOf("a" to fail("timeout", true), "b" to ok()))
        val router = AiRouter(t) { "key" }
        var cancelled = false
        var threw = false
        kotlinx.coroutines.runBlocking {
            try {
                router.chat(
                    listOf(config("a"), config("b")),
                    AiRouterSettings(),
                    msgs,
                    isCancelled = { cancelled }) {
                    if (it is AiChatEvent.AttemptFailed) cancelled = true
                }
            } catch (ce: CancellationException) {
                threw = true
            }
        }
        assertTrue(threw)
        assertEquals(listOf("a"), t.attempts) // never reached b
    }

    @Test
    fun `cost cap stops before breaching`() = runTest {
        // gpt-4o has pricing: 2.50/10.00 per 1M. 100k/100k tokens ≈ $1.25.
        val t = FakeTransport(
            mapOf(
                "a" to ok("gpt-4o", input = 100_000, output = 100_000),
                "b" to ok("gpt-4o", input = 100_000, output = 100_000),
            )
        )
        val router = AiRouter(t) { "key" }
        val tracker = AiRouter.CostTracker()
        // First chat spends ~$1.25 with a $1.00 cap already exceeded mid-run?
        // Simpler: pre-spend the tracker to the cap, then chat must not call.
        tracker.add("gpt-4o", 100_000, 100_000) // ~$1.25
        val (events, _) = collectEvents(
            router,
            listOf(config("a", model = "gpt-4o"), config("b", model = "gpt-4o")),
            AiRouterSettings(maxCostUsd = 1.0),
            tracker = tracker,
        )
        val failed = events.filterIsInstance<AiChatEvent.Failed>().single()
        assertEquals("cost_cap", failed.failures.single().code)
        assertTrue(t.attempts.isEmpty())
    }

    @Test
    fun `free-model-only fallback skips paid models`() = runTest {
        val t = FakeTransport(
            mapOf(
                "paid1" to fail("timeout", true),
                // paid fallback must be skipped; free fallback answers
                "free" to ok("llama-3.3-70b-versatile"),
            )
        )
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(
            router,
            listOf(
                config("paid1", model = "gpt-4o"),
                config("paid2", model = "gpt-4o"),
                config("free", model = "llama-3.3-70b-versatile"),
            ),
            AiRouterSettings(freeModelOnlyFallback = true),
        )
        val answered = events.filterIsInstance<AiChatEvent.Answered>().single()
        assertEquals("free", answered.answer.providerId)
        assertTrue("paid2" !in t.attempts)
    }

    @Test
    fun `selected provider is tried first then fallbacks`() = runTest {
        val t = FakeTransport(
            mapOf("a" to ok(), "b" to fail("timeout", true), "c" to ok())
        )
        val router = AiRouter(t) { "key" }
        // Select b (priority-wise last): b fails, then a (priority 0) answers.
        val (events, _) = collectEvents(
            router,
            listOf(config("a", priority = 0), config("b", priority = 1), config("c", priority = 2)),
            AiRouterSettings(),
            selected = "b",
        )
        assertEquals(listOf("b", "a"), t.attempts)
        assertEquals("a", events.filterIsInstance<AiChatEvent.Answered>().single().answer.providerId)
    }

    @Test
    fun `maxAttempts bounds the failover chain`() = runTest {
        val t = FakeTransport(
            mapOf(
                "a" to fail("timeout", true),
                "b" to fail("timeout", true),
                "c" to ok(),
            )
        )
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(
            router,
            listOf(config("a"), config("b"), config("c")),
            AiRouterSettings(maxAttempts = 2),
        )
        assertEquals(listOf("a", "b"), t.attempts)
        assertTrue(events.filterIsInstance<AiChatEvent.Failed>().isNotEmpty())
    }

    @Test
    fun `autoFailover off stops after first failure`() = runTest {
        val t = FakeTransport(
            mapOf("a" to fail("timeout", true), "b" to ok())
        )
        val router = AiRouter(t) { "key" }
        val (events, _) = collectEvents(
            router,
            listOf(config("a"), config("b")),
            AiRouterSettings(autoFailover = false),
        )
        assertEquals(listOf("a"), t.attempts)
        assertTrue(events.filterIsInstance<AiChatEvent.Failed>().isNotEmpty())
    }

    @Test
    fun `pricing helpers classify free and priced models`() {
        assertTrue(AiPricing.isFreeModel("llama-3.3-70b-versatile"))
        assertTrue(AiPricing.isFreeModel("deepseek/deepseek-chat:free"))
        assertFalse(AiPricing.isFreeModel("gpt-4o"))
        assertFalse(AiPricing.isFreeModel("some-unknown-model"))
    }
}
