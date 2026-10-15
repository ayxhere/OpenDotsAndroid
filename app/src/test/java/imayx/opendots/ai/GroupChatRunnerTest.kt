package imayx.opendots.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [GroupChatRunner] loop prevention and modes.
 */
class GroupChatRunnerTest {

    private fun config(id: String, model: String = "m-$id") = AiProviderConfig(
        id = id, name = "P-$id", type = AiProviderType.OPENAI,
        model = model, keyAlias = "k-$id", enabled = true, priority = 0,
    )

    /** Router whose transport always succeeds instantly per provider. */
    private fun scriptedRouter(
        failOn: Set<String> = emptySet(),
        onAttempt: (String) -> Unit = {},
    ): AiRouter {
        val transport = object : AiChatTransport {
            override suspend fun streamChat(
                config: AiProviderConfig,
                apiKey: String,
                messages: List<AiMessage>,
                timeoutSec: Int,
                onDelta: (String) -> Unit,
            ): AiChatTransport.Usage {
                onAttempt(config.id)
                if (config.id in failOn) {
                    throw AiTransportException(
                        AiFailure("timeout", "t/o", true, config.id, config.model)
                    )
                }
                onDelta("reply from ${config.id}")
                return AiChatTransport.Usage(5, 5, config.model)
            }
        }
        return AiRouter(transport) { "key" }
    }

    private fun group(
        vararg names: String,
        mode: GroupMode = GroupMode.ROUND_ROBIN,
        maxTurns: Int = 12,
        maxCostUsd: Double? = null,
        moderator: String? = null,
    ) = AiGroup(
        name = "g",
        participants = names.map { GroupParticipant(providerConfigId = it, agentName = "Agent-$it") },
        mode = mode,
        moderatorConfigId = moderator,
        maxTurns = maxTurns,
        maxCostUsd = maxCostUsd,
    )

    @Test
    fun `round robin each agent replies exactly once`() = runTest {
        val attempts = mutableListOf<String>()
        val runner = GroupChatRunner(scriptedRouter(onAttempt = { attempts.add(it) })) { ids ->
            ids.map { config(it) }
        }
        val history = mutableListOf<GroupChatMessage>()
        val events = mutableListOf<GroupRunEvent>()
        runner.runTurn(
            group("a", "b", "c"), history, "hello all",
            AiRouterSettings(), AiRouter.CostTracker(), { false }, { events.add(it) }
        )
        assertEquals(listOf("a", "b", "c"), attempts)
        val agentMsgs = history.filter { it.author.startsWith("Agent-") }
        assertEquals(3, agentMsgs.size)
        assertTrue(events.any { it is GroupRunEvent.RunEnded && it.reason == RunEndReason.COMPLETED })
    }

    @Test
    fun `no agent speaks twice in a row`() = runTest {
        // Two participants mapped to the same provider id would be skipped
        // the second time if names collided; here verify ordering instead:
        // with duplicate consecutive participants, the second is skipped.
        val attempts = mutableListOf<String>()
        val runner = GroupChatRunner(scriptedRouter(onAttempt = { attempts.add(it) })) { ids ->
            ids.map { config(it) }
        }
        val g = AiGroup(
            name = "g",
            participants = listOf(
                GroupParticipant("a", "Agent-a"),
                GroupParticipant("a", "Agent-a"), // same name twice in a row
                GroupParticipant("b", "Agent-b"),
            ),
            mode = GroupMode.ROUND_ROBIN,
        )
        val history = mutableListOf<GroupChatMessage>()
        runner.runTurn(g, history, "hi", AiRouterSettings(), AiRouter.CostTracker(), { false }) {}
        // Second identical consecutive speaker is skipped by loop prevention.
        assertEquals(listOf("a", "b"), attempts)
    }

    @Test
    fun `mention mode only mentioned agents reply`() = runTest {
        val attempts = mutableListOf<String>()
        val runner = GroupChatRunner(scriptedRouter(onAttempt = { attempts.add(it) })) { ids ->
            ids.map { config(it) }
        }
        val history = mutableListOf<GroupChatMessage>()
        runner.runTurn(
            group("a", "b", mode = GroupMode.MENTION),
            history, "hey @Agent-b what do you think?",
            AiRouterSettings(), AiRouter.CostTracker(), { false }) {}
        assertEquals(listOf("b"), attempts)
    }

    @Test
    fun `mention mode with no mentions nobody replies`() = runTest {
        val attempts = mutableListOf<String>()
        val runner = GroupChatRunner(scriptedRouter(onAttempt = { attempts.add(it) })) { ids ->
            ids.map { config(it) }
        }
        val history = mutableListOf<GroupChatMessage>()
        val events = mutableListOf<GroupRunEvent>()
        runner.runTurn(
            group("a", "b", mode = GroupMode.MENTION),
            history, "hello everyone",
            AiRouterSettings(), AiRouter.CostTracker(), { false }, { events.add(it) }
        )
        assertTrue(attempts.isEmpty())
        assertTrue(events.any { it is GroupRunEvent.RunEnded })
        // The human message is still recorded.
        assertEquals(1, history.size)
    }

    @Test
    fun `moderated mode adds exactly one moderator summary`() = runTest {
        val attempts = mutableListOf<String>()
        val runner = GroupChatRunner(scriptedRouter(onAttempt = { attempts.add(it) })) { ids ->
            ids.map { config(it) }
        }
        val history = mutableListOf<GroupChatMessage>()
        runner.runTurn(
            group("a", "b", mode = GroupMode.MODERATED, moderator = "a"),
            history, "discuss",
            AiRouterSettings(), AiRouter.CostTracker(), { false }) {}
        // a, b, then moderator summary from a = 3 attempts; exactly one Moderator msg.
        assertEquals(3, attempts.size)
        assertEquals(1, history.count { it.author == "Moderator" })
    }

    @Test
    fun `cost cap stops the run mid-turn`() = runTest {
        val attempts = mutableListOf<String>()
        val runner = GroupChatRunner(scriptedRouter(onAttempt = { attempts.add(it) })) { ids ->
            ids.map { config(it, model = "gpt-4o") }
        }
        val history = mutableListOf<GroupChatMessage>()
        val events = mutableListOf<GroupRunEvent>()
        // Pre-spend over the cap: no agent should be attempted.
        val tracker = AiRouter.CostTracker().apply { add("gpt-4o", 1_000_000, 1_000_000) }
        runner.runTurn(
            group("a", "b", maxCostUsd = 1.0),
            history, "hi", AiRouterSettings(), tracker, { false }, { events.add(it) }
        )
        assertTrue(attempts.isEmpty())
        assertTrue(events.any { it is GroupRunEvent.RunEnded && it.reason == RunEndReason.COST_CAP })
    }

    @Test
    fun `failing agent does not stop other agents`() = runTest {
        val runner = GroupChatRunner(scriptedRouter(failOn = setOf("a"))) { ids ->
            ids.map { config(it) }
        }
        val history = mutableListOf<GroupChatMessage>()
        val events = mutableListOf<GroupRunEvent>()
        runner.runTurn(
            group("a", "b"), history, "hi",
            AiRouterSettings(), AiRouter.CostTracker(), { false }, { events.add(it) }
        )
        assertTrue(history.any { it.author == "Agent-b" })
        assertTrue(events.any { it is GroupRunEvent.AgentFailed && it.agentName == "Agent-a" })
        assertTrue(events.any { it is GroupRunEvent.RunEnded && it.reason == RunEndReason.COMPLETED })
    }

    @Test
    fun `empty group ends immediately`() = runTest {
        val runner = GroupChatRunner(scriptedRouter()) { emptyList() }
        val history = mutableListOf<GroupChatMessage>()
        val events = mutableListOf<GroupRunEvent>()
        runner.runTurn(
            AiGroup(name = "empty", participants = emptyList()),
            history, "hi", AiRouterSettings(), AiRouter.CostTracker(), { false }, { events.add(it) }
        )
        assertTrue(events.any { it is GroupRunEvent.RunEnded && it.reason == RunEndReason.NO_AGENTS })
    }

    @Test
    fun `shared transcript is visible to every agent`() = runTest {
        val seen = mutableListOf<List<AiMessage>>()
        val transport = object : AiChatTransport {
            override suspend fun streamChat(
                config: AiProviderConfig,
                apiKey: String,
                messages: List<AiMessage>,
                timeoutSec: Int,
                onDelta: (String) -> Unit,
            ): AiChatTransport.Usage {
                seen.add(messages.toList())
                onDelta("ok")
                return AiChatTransport.Usage(1, 1, config.model)
            }
        }
        val runner = GroupChatRunner(AiRouter(transport) { "k" }) { ids -> ids.map { config(it) } }
        val history = mutableListOf(
            GroupChatMessage(author = "human", text = "first"),
            GroupChatMessage(author = "Agent-a", text = "reply-a"),
        )
        runner.runTurn(
            group("b"), history, "second",
            AiRouterSettings(), AiRouter.CostTracker(), { false }) {}
        // Agent b's prompt must contain the full shared transcript.
        val promptText = seen.single().joinToString("\n") { it.content }
        assertTrue(promptText.contains("first"))
        assertTrue(promptText.contains("reply-a"))
        assertTrue(promptText.contains("second"))
    }
}
