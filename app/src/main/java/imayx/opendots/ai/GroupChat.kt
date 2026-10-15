package imayx.opendots.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Multi-participant AI group conversations.
 *
 * - Each participant is a configured AI provider + model with a display name.
 * - Modes: MENTION (only @mentioned agents reply), ROUND_ROBIN (every agent
 *   replies once per human message, in order), MODERATED (agents reply, then
 *   a moderator agent summarizes).
 * - Loop prevention: hard [maxTurns] cap per run, no agent speaks twice in a
 *   row without a human message in between (except the moderator's single
 *   summary), and a run always ends after one pass in ROUND_ROBIN.
 * - Shared group context: every agent sees the bounded shared transcript.
 *   Per-participant separate histories are kept in [GroupConversationState]
 *   for the UI; the upstream call always uses the shared transcript so
 *   agents stay in the same conversation.
 * - Cost: a shared [AiRouter.CostTracker] spans the whole run; the run stops
 *   when the USD cap is hit.
 * - No tools are invoked on this path (plain completions), so duplicate tool
 *   execution is impossible by construction.
 */

enum class GroupMode(val displayName: String) {
    MENTION("@mention replies"),
    ROUND_ROBIN("Round-robin"),
    MODERATED("Moderated discussion"),
}

@Serializable
data class GroupParticipant(
    val providerConfigId: String,
    val agentName: String,
    /** Model override; null = use the provider config's model. */
    val model: String? = null,
)

@Serializable
data class AiGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val participants: List<GroupParticipant> = emptyList(),
    val mode: GroupMode = GroupMode.ROUND_ROBIN,
    val moderatorConfigId: String? = null,
    val maxTurns: Int = 12,
    val maxCostUsd: Double? = null,
    val systemPrompt: String? = null,
)

@Serializable
data class GroupChatMessage(
    val id: String = UUID.randomUUID().toString(),
    /** "human" or the participant's agentName / "Moderator". */
    val author: String,
    val providerName: String? = null,
    val model: String? = null,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val fallbackOccurred: Boolean = false,
)

sealed interface GroupRunEvent {
    data class Message(val message: GroupChatMessage) : GroupRunEvent
    data class AgentTyping(val agentName: String) : GroupRunEvent
    data class AgentDelta(val agentName: String, val text: String) : GroupRunEvent
    data class AgentFailed(val agentName: String, val error: String) : GroupRunEvent
    data class RunEnded(val reason: RunEndReason) : GroupRunEvent
}

enum class RunEndReason { COMPLETED, TURN_LIMIT, COST_CAP, CANCELLED, NO_AGENTS, ERROR }

class GroupChatRunner(
    private val router: AiRouter,
    private val configsFor: (List<String>) -> List<AiProviderConfig>,
) {
    /**
     * Runs one human message through the group.
     *
     * @param history shared transcript so far (mutated: new messages appended).
     * @param settings router settings; group caps override cost/attempts.
     */
    suspend fun runTurn(
        group: AiGroup,
        history: MutableList<GroupChatMessage>,
        humanText: String,
        settings: AiRouterSettings,
        costTracker: AiRouter.CostTracker = AiRouter.CostTracker(),
        isCancelled: () -> Boolean = { false },
        onEvent: (GroupRunEvent) -> Unit,
    ) {
        if (group.participants.isEmpty()) {
            onEvent(GroupRunEvent.RunEnded(RunEndReason.NO_AGENTS))
            return
        }
        history.add(GroupChatMessage(author = "human", text = humanText))
        onEvent(GroupRunEvent.Message(history.last()))

        val effectiveSettings = settings.copy(
            maxCostUsd = group.maxCostUsd ?: settings.maxCostUsd,
        )
        // Who replies, in order. Loop prevention: each agent at most once per
        // turn here; multi-turn loops are bounded by maxTurns at the call site
        // (the UI counts runTurn calls per conversation).
        val responders: List<GroupParticipant> = when (group.mode) {
            GroupMode.MENTION -> {
                val mentioned = group.participants.filter { p ->
                    humanText.contains("@${p.agentName}", ignoreCase = true)
                }
                // No @mention and no default: nobody replies (no surprise loops).
                mentioned
            }
            GroupMode.ROUND_ROBIN, GroupMode.MODERATED -> group.participants
        }
        if (responders.isEmpty()) {
            onEvent(GroupRunEvent.RunEnded(RunEndReason.COMPLETED))
            return
        }

        var lastSpeaker: String? = "human"
        for (participant in responders) {
            if (isCancelled()) {
                onEvent(GroupRunEvent.RunEnded(RunEndReason.CANCELLED))
                return
            }
            // Never let the same agent speak twice in a row.
            if (participant.agentName == lastSpeaker) continue
            val cap = effectiveSettings.maxCostUsd
            if (cap != null && costTracker.spentUsd >= cap) {
                onEvent(GroupRunEvent.RunEnded(RunEndReason.COST_CAP))
                return
            }
            val configs = configsFor(listOf(participant.providerConfigId))
            val config = configs.firstOrNull()
            if (config == null || !config.enabled) {
                onEvent(
                    GroupRunEvent.AgentFailed(
                        participant.agentName,
                        "Provider not configured or disabled.",
                    )
                )
                continue
            }
            val effectiveConfig = participant.model?.let { config.copy(model = it) } ?: config
            onEvent(GroupRunEvent.AgentTyping(participant.agentName))
            val prompt = buildAgentPrompt(group, participant, history)
            val buffer = StringBuilder()
            var answered: AiAnswer? = null
            var failures: List<AiFailure> = emptyList()
            try {
                router.chat(
                    configs = listOf(effectiveConfig),
                    settings = effectiveSettings.copy(maxAttempts = 1, autoFailover = false),
                    messages = prompt,
                    costTracker = costTracker,
                    isCancelled = isCancelled,
                    onEvent = { e ->
                        when (e) {
                            is AiChatEvent.TextDelta -> {
                                buffer.append(e.text)
                                onEvent(GroupRunEvent.AgentDelta(participant.agentName, e.text))
                            }
                            is AiChatEvent.Answered -> answered = e.answer
                            is AiChatEvent.Failed -> failures = e.failures
                            is AiChatEvent.AttemptFailed -> failures = failures + e.failure
                        }
                    },
                )
            } catch (ce: CancellationException) {
                onEvent(GroupRunEvent.RunEnded(RunEndReason.CANCELLED))
                return
            }
            val a = answered
            if (a != null && buffer.isNotEmpty()) {
                val msg = GroupChatMessage(
                    author = participant.agentName,
                    providerName = a.providerName,
                    model = a.model,
                    text = buffer.toString(),
                    fallbackOccurred = a.fallbackOccurred,
                )
                history.add(msg)
                onEvent(GroupRunEvent.Message(msg))
                lastSpeaker = participant.agentName
            } else {
                onEvent(
                    GroupRunEvent.AgentFailed(
                        participant.agentName,
                        failures.firstOrNull()?.message ?: "No response.",
                    )
                )
            }
        }

        // Moderated mode: one summary from the moderator (or first participant).
        if (group.mode == GroupMode.MODERATED) {
            if (isCancelled()) {
                onEvent(GroupRunEvent.RunEnded(RunEndReason.CANCELLED))
                return
            }
            val modId = group.moderatorConfigId ?: group.participants.firstOrNull()?.providerConfigId
            val modConfig = modId?.let { configsFor(listOf(it)).firstOrNull() }
            if (modConfig != null && modConfig.enabled) {
                onEvent(GroupRunEvent.AgentTyping("Moderator"))
                val summaryPrompt = listOf(
                    AiMessage("system", "You are the moderator of a group discussion. Summarize the key points from each participant briefly and fairly."),
                    AiMessage(
                        "user",
                        history.takeLast(20).joinToString("\n") { "${it.author}: ${it.text}" } +
                            "\n\nSummarize the discussion above in a few bullet points.",
                    ),
                )
                val buffer = StringBuilder()
                try {
                    router.chat(
                        configs = listOf(modConfig),
                        settings = effectiveSettings.copy(maxAttempts = 1, autoFailover = false),
                        messages = summaryPrompt,
                        costTracker = costTracker,
                        isCancelled = isCancelled,
                        onEvent = { e ->
                            if (e is AiChatEvent.TextDelta) {
                                buffer.append(e.text)
                                onEvent(GroupRunEvent.AgentDelta("Moderator", e.text))
                            }
                        },
                    )
                    if (buffer.isNotEmpty()) {
                        val msg = GroupChatMessage(
                            author = "Moderator",
                            providerName = modConfig.name,
                            model = modConfig.model,
                            text = buffer.toString(),
                        )
                        history.add(msg)
                        onEvent(GroupRunEvent.Message(msg))
                    }
                } catch (ce: CancellationException) {
                    onEvent(GroupRunEvent.RunEnded(RunEndReason.CANCELLED))
                    return
                } catch (_: Exception) {
                    // Moderator summary is best-effort; the run still completed.
                }
            }
        }
        onEvent(GroupRunEvent.RunEnded(RunEndReason.COMPLETED))
    }

    private fun buildAgentPrompt(
        group: AiGroup,
        participant: GroupParticipant,
        history: List<GroupChatMessage>,
    ): List<AiMessage> {
        val system = buildString {
            append("You are ${participant.agentName}, a participant in a group chat called \"${group.name}\". ")
            append("Other participants: ${group.participants.filter { it.agentName != participant.agentName }.joinToString { it.agentName }}. ")
            append("Reply as ${participant.agentName} only, keep it concise, and stay in character as a helpful AI assistant. ")
            group.systemPrompt?.let { append(it) }
        }
        val transcript = history.takeLast(30).joinToString("\n") { "${it.author}: ${it.text}" }
        return listOf(
            AiMessage("system", system),
            AiMessage("user", "Group conversation so far:\n$transcript\n\nWrite your next message as ${participant.agentName}:"),
        )
    }
}
