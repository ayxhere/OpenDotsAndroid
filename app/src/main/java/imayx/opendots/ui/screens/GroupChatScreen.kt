package imayx.opendots.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import imayx.opendots.ai.AiGroup
import imayx.opendots.ai.AiGroupStore
import imayx.opendots.ai.AiProviderStore
import imayx.opendots.ai.AiProxyTransport
import imayx.opendots.ai.AiRouter
import imayx.opendots.ai.AiRouterSettingsStore
import imayx.opendots.ai.GroupChatMessage
import imayx.opendots.ai.GroupChatRunner
import imayx.opendots.ai.GroupMode
import imayx.opendots.ai.GroupRunEvent
import imayx.opendots.ai.RunEndReason
import imayx.opendots.auth.TokenStore
import imayx.opendots.ui.components.OdHeader
import imayx.opendots.ui.components.formatInstant
import imayx.opendots.ui.theme.OdSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Group conversation: the human chats with several AI agents in one thread.
 * Shows participant badges (name · provider · model), streaming, typing
 * indicators, turn/cost caps and a stop control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    group: AiGroup,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val groupStore = remember { AiGroupStore(context) }
    val providerStore = remember { AiProviderStore(context) }
    val settingsStore = remember { AiRouterSettingsStore(context) }
    val tokenStore = remember { TokenStore(context) }
    val router = remember {
        AiRouter(AiProxyTransport(tokenStore)) { cfg -> tokenStore.getSecret(cfg.keyAlias) }
    }
    val costTracker = remember { AiRouter.CostTracker() }

    val history = remember { mutableStateListOf<GroupChatMessage>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var typingAgents by remember { mutableStateOf<Set<String>>(emptySet()) }
    var turnCount by remember { mutableStateOf(0) }
    var runNote by remember { mutableStateOf<String?>(null) }
    // Live deltas per agent while streaming.
    val liveDeltas = remember { mutableStateListOf<Pair<String, String>>() }

    LaunchedEffect(group.id) {
        history.clear()
        history.addAll(groupStore.loadHistory(group.id))
    }
    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1)
    }
    DisposableEffect(group.id) {
        onDispose {
            scope.launch { groupStore.saveHistory(group.id, history.toList()) }
        }
    }

    fun stop() {
        runJob?.cancel()
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || running) return
        if (turnCount >= group.maxTurns) {
            scope.launch { snackbar.showSnackbar("Turn limit (${group.maxTurns}) reached for this run.") }
            return
        }
        input = ""
        runNote = null
        running = true
        turnCount++

        runJob = scope.launch {
            try {
                val settings = settingsStore.load()
                val configs = providerStore.load()
                val liveRunner = GroupChatRunner(router) { ids ->
                    configs.filter { it.id in ids }
                }
                liveRunner.runTurn(
                    group = group,
                    history = history,
                    humanText = text,
                    settings = settings,
                    costTracker = costTracker,
                    isCancelled = { !running },
                    onEvent = { e ->
                        when (e) {
                            is GroupRunEvent.Message -> {
                                liveDeltas.removeAll { it.first == e.message.author }
                                typingAgents = typingAgents - e.message.author
                            }
                            is GroupRunEvent.AgentTyping -> {
                                typingAgents = typingAgents + e.agentName
                            }
                            is GroupRunEvent.AgentDelta -> {
                                val i = liveDeltas.indexOfFirst { it.first == e.agentName }
                                if (i >= 0) {
                                    liveDeltas[i] = e.agentName to (liveDeltas[i].second + e.text)
                                } else {
                                    liveDeltas.add(e.agentName to e.text)
                                }
                            }
                            is GroupRunEvent.AgentFailed -> {
                                typingAgents = typingAgents - e.agentName
                                liveDeltas.removeAll { it.first == e.agentName }
                                scope.launch {
                                    snackbar.showSnackbar("${e.agentName}: ${e.error.take(120)}")
                                }
                            }
                            is GroupRunEvent.RunEnded -> {
                                typingAgents = emptySet()
                                runNote = when (e.reason) {
                                    RunEndReason.COMPLETED -> null
                                    RunEndReason.TURN_LIMIT -> "Turn limit reached."
                                    RunEndReason.COST_CAP -> "Cost cap reached — run stopped."
                                    RunEndReason.CANCELLED -> "Stopped."
                                    RunEndReason.NO_AGENTS -> "No agents in this group."
                                    RunEndReason.ERROR -> "Run ended with errors."
                                }
                            }
                        }
                    },
                )
                groupStore.saveHistory(group.id, history.toList())
            } catch (ce: CancellationException) {
                runNote = "Stopped."
            } finally {
                running = false
                typingAgents = emptySet()
            }
        }
    }

    Scaffold(
        topBar = {
            OdHeader(
                title = group.name,
                subtitle = "${group.participants.size} agents · ${group.mode.displayName} · turn $turnCount/${group.maxTurns}",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = OdSpacing.screen).padding(top = OdSpacing.s),
                actions = {
                    if (costTracker.spentUsd > 0) {
                        AssistChip(
                            onClick = {},
                            label = { Text("$${"%.4f".format(costTracker.spentUsd)}") },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (group.mode == GroupMode.MENTION) {
                Text(
                    "Tip: @mention an agent by name to get a reply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = OdSpacing.screen, vertical = 4.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(OdSpacing.m),
                verticalArrangement = Arrangement.spacedBy(OdSpacing.s),
            ) {
                items(history, key = { it.id }) { msg ->
                    GroupMessageCard(msg)
                }
                // Live streaming cards
                items(liveDeltas, key = { it.first }) { (author, text) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(0.95f),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Column(Modifier.padding(OdSpacing.m)) {
                            Text(
                                "$author · typing…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (typingAgents.isNotEmpty()) {
                    item {
                        Text(
                            typingAgents.joinToString { "$it is thinking…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }

            runNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = OdSpacing.screen, vertical = 4.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(OdSpacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message the group…") },
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.width(OdSpacing.s))
                if (running) {
                    IconButton(onClick = { stop() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop")
                    }
                } else {
                    IconButton(
                        onClick = { send() },
                        enabled = input.trim().isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupMessageCard(msg: GroupChatMessage) {
    val isHuman = msg.author == "human" || msg.author == "Human"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isHuman) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isHuman) 0.85f else 0.95f),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (isHuman)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(Modifier.padding(OdSpacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isHuman) "You" else msg.author,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val ts = formatInstant(msg.timestampMs)
                    if (ts.isNotEmpty()) {
                        Text(
                            ts,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!isHuman && (msg.providerName != null || msg.model != null)) {
                    Text(
                        listOfNotNull(
                            msg.providerName, msg.model,
                            if (msg.fallbackOccurred) "fallback" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(msg.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
