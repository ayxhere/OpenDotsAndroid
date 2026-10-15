package imayx.opendots.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import imayx.opendots.ai.AiAnswer
import imayx.opendots.ai.AiChatEvent
import imayx.opendots.ai.AiFailure
import imayx.opendots.ai.AiMessage
import imayx.opendots.ai.AiProviderConfig
import imayx.opendots.ai.AiProviderStore
import imayx.opendots.ai.AiProxyTransport
import imayx.opendots.ai.AiRouter
import imayx.opendots.ai.AiRouterSettingsStore
import imayx.opendots.auth.TokenStore
import imayx.opendots.ui.components.ExpandableErrorText
import imayx.opendots.ui.components.OdEmpty
import imayx.opendots.ui.theme.OdSpacing
import imayx.opendots.voice.VoiceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class UiChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val answer: AiAnswer? = null,
    val error: String? = null,
)

/**
 * Multi-provider chat: pick a provider (or Auto failover), chat with
 * streaming, see which provider/model answered and whether fallback
 * happened. Voice: mic for speech-to-text, speaker for read-aloud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val providerStore = remember { AiProviderStore(context) }
    val settingsStore = remember { AiRouterSettingsStore(context) }
    val tokenStore = remember { TokenStore(context) }
    val router = remember {
        AiRouter(AiProxyTransport(tokenStore)) { cfg ->
            tokenStore.getSecret(cfg.keyAlias)
        }
    }
    val costTracker = remember { AiRouter.CostTracker() }
    val voice = remember { VoiceEngine(context) }

    var providers by remember { mutableStateOf<List<AiProviderConfig>>(emptyList()) }
    var selectedProviderId by remember { mutableStateOf<String?>(null) } // null = Auto
    var providerMenu by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<UiChatMessage>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var voiceEnabled by remember { mutableStateOf(false) }
    var listeningText by remember { mutableStateOf("") }
    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micPermissionGranted = granted }

    DisposableEffect(Unit) {
        onDispose { voice.shutdown() }
    }
    LaunchedEffect(Unit) {
        providers = providerStore.load().sortedBy { it.priority }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Voice wiring: transcription -> input; TTS reads completed answers.
    LaunchedEffect(voiceEnabled) {
        if (!voiceEnabled) {
            voice.interrupt()
            return@LaunchedEffect
        }
        voice.onPartialResult = { listeningText = it }
        voice.onFinalResult = { text ->
            listeningText = ""
            if (text.isNotBlank()) input = text
        }
        voice.onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    }

    fun cancelRun() {
        runJob?.cancel()
        voice.interrupt()
    }

    fun send(overrideText: String? = null) {
        val text = (overrideText ?: input).trim()
        if (text.isEmpty() || running) return
        input = ""
        running = true

        val historyForProvider = messages
            .filter { it.error == null && !it.streaming }
            .map { AiMessage(role = it.role, content = it.text) } +
            AiMessage("user", text)

        val userMsg = UiChatMessage(
            id = "u-${System.currentTimeMillis()}", role = "user", text = text,
        )
        val assistantMsg = UiChatMessage(
            id = "a-${System.currentTimeMillis()}", role = "assistant",
            text = "", streaming = true,
        )
        messages.add(userMsg)
        messages.add(assistantMsg)
        val assistantIndex = messages.size - 1
        val buffer = StringBuilder()
        val failures = mutableListOf<AiFailure>()

        runJob = scope.launch {
            try {
                val settings = settingsStore.load()
                router.chat(
                    configs = providers,
                    settings = settings,
                    messages = historyForProvider,
                    selectedProviderId = selectedProviderId,
                    costTracker = costTracker,
                    isCancelled = { !running },
                    onEvent = { e ->
                        when (e) {
                            is AiChatEvent.TextDelta -> {
                                buffer.append(e.text)
                                messages[assistantIndex] =
                                    assistantMsg.copy(text = buffer.toString(), streaming = true)
                            }
                            is AiChatEvent.Answered -> {
                                messages[assistantIndex] = assistantMsg.copy(
                                    text = buffer.toString(), streaming = false,
                                    answer = e.answer,
                                )
                                if (voiceEnabled && buffer.isNotEmpty()) {
                                    voice.speak(buffer.toString())
                                }
                            }
                            is AiChatEvent.AttemptFailed -> {
                                failures.add(e.failure)
                            }
                            is AiChatEvent.Failed -> {
                                val detail = e.failures.joinToString("\n") {
                                    "• ${it.message}"
                                }.take(400)
                                messages[assistantIndex] = assistantMsg.copy(
                                    text = if (buffer.isNotEmpty()) buffer.toString() else "",
                                    streaming = false,
                                    error = if (detail.isNotBlank()) detail
                                    else "All providers failed.",
                                )
                            }
                        }
                    },
                )
            } catch (ce: CancellationException) {
                messages[assistantIndex] = assistantMsg.copy(
                    text = buffer.toString(), streaming = false,
                    error = if (buffer.isEmpty()) "Cancelled." else null,
                )
            } finally {
                running = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Provider picker row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = OdSpacing.m, vertical = OdSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { providerMenu = true }) {
                        val label = selectedProviderId?.let { id ->
                            providers.find { it.id == id }?.name
                        } ?: "Auto (failover)"
                        Text("Provider: $label")
                    }
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Auto (failover)") },
                            onClick = { selectedProviderId = null; providerMenu = false },
                        )
                        providers.filter { it.enabled }.forEach { p ->
                            DropdownMenuItem(
                                text = { Text("${p.name} · ${p.model}") },
                                onClick = { selectedProviderId = p.id; providerMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (costTracker.spentUsd > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("$${"%.4f".format(costTracker.spentUsd)}") },
                    )
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = { voiceEnabled = !voiceEnabled }) {
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = "Read answers aloud",
                        tint = if (voiceEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (providers.isEmpty()) {
                OdEmpty(
                    title = "No AI providers configured",
                    body = "Add providers in Settings → AI Providers → Manage.",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(OdSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(OdSpacing.s),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        val isUser = msg.role == "user"
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 0.95f),
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        msg.error != null -> MaterialTheme.colorScheme.errorContainer
                                        isUser -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainer
                                    },
                                ),
                            ) {
                                Column(Modifier.padding(OdSpacing.m)) {
                                    if (msg.answer != null) {
                                        Text(
                                            buildString {
                                                append(msg.answer.providerName)
                                                append(" · ${msg.answer.model}")
                                                if (msg.answer.fallbackOccurred) append(" · fallback")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    when {
                                        msg.error != null ->
                                            ExpandableErrorText(msg.error)
                                        msg.text.isEmpty() && msg.streaming ->
                                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                                        else -> Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Live transcription indicator
            if (listeningText.isNotEmpty()) {
                Text(
                    "🎤 $listeningText",
                    modifier = Modifier.padding(horizontal = OdSpacing.screen, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Input row
            Row(
                Modifier.fillMaxWidth().padding(OdSpacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (!micPermissionGranted) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            voice.startListening()
                        }
                    },
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = "Voice input")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask anything…") },
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.width(OdSpacing.s))
                if (running) {
                    IconButton(onClick = { cancelRun() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                } else {
                    IconButton(
                        onClick = { send() },
                        enabled = input.trim().isNotEmpty() && providers.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = OdSpacing.screen),
                horizontalArrangement = Arrangement.spacedBy(OdSpacing.s),
            ) {
                TextButton(onClick = { voice.replayLast() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Replay")
                }
                TextButton(onClick = { voice.interrupt() }) { Text("Stop speech") }
            }
        }
    }
}
