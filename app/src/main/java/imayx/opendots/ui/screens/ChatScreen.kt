package imayx.opendots.ui.screens

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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import imayx.opendots.api.Dot
import imayx.opendots.auth.TokenStore
import imayx.opendots.chat.ChatClient
import imayx.opendots.node.NodeManager
import imayx.opendots.ui.components.ExpandableErrorText
import imayx.opendots.ui.components.OdErrorCard
import imayx.opendots.ui.components.OdHeader
import imayx.opendots.ui.theme.OdSpacing
import kotlinx.coroutines.launch

private data class ChatMessage(
    val id: String,
    val role: String, // "user" | "assistant" | "system"
    val text: String,
    val streaming: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    nodeManager: NodeManager,
    dot: Dot,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var threadId by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var setupError by remember { mutableStateOf<String?>(null) }

    // Create the conversation (thread) when the screen opens.
    LaunchedEffect(dot.id) {
        val client = ChatClient(TokenStore(context))
        val tid = client.createConversation(dot.id, "Chat with ${dot.name}")
        if (tid != null) {
            threadId = tid
        } else {
            setupError = "Could not start a conversation. " +
                "If AI is not configured, add your INTELLIGENCE_API_KEY in Settings."
        }
    }

    // Auto-scroll to the latest message.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun send() {
        val text = input.trim()
        val tid = threadId
        if (text.isEmpty() || sending || tid == null) return
        input = ""
        sending = true
        setupError = null

        val userMsg = ChatMessage(
            id = "u-${System.currentTimeMillis()}",
            role = "user",
            text = text,
        )
        val assistantMsg = ChatMessage(
            id = "a-${System.currentTimeMillis()}",
            role = "assistant",
            text = "",
            streaming = true,
        )
        messages.add(userMsg)
        messages.add(assistantMsg)
        val assistantIndex = messages.size - 1
        val responseBuffer = StringBuilder()

        scope.launch {
            val client = ChatClient(TokenStore(context))
            val error = client.runMessage(dot.id, tid, text) { event ->
                when (event) {
                    is ChatClient.ChatEvent.TextDelta -> {
                        responseBuffer.append(event.text)
                        messages[assistantIndex] =
                            assistantMsg.copy(text = responseBuffer.toString(), streaming = true)
                    }
                    is ChatClient.ChatEvent.RunFinished -> {
                        messages[assistantIndex] =
                            assistantMsg.copy(text = responseBuffer.toString(), streaming = false)
                    }
                    is ChatClient.ChatEvent.RunError -> {
                        messages[assistantIndex] = assistantMsg.copy(
                            text = responseBuffer.toString().ifEmpty { "Error: ${event.message}" },
                            streaming = false,
                        )
                    }
                    is ChatClient.ChatEvent.Started -> { /* no-op */ }
                }
            }
            if (error != null) {
                if (responseBuffer.isEmpty()) {
                    messages[assistantIndex] = assistantMsg.copy(
                        text = error,
                        streaming = false,
                    )
                }
                if (error.contains("INTELLIGENCE_API_KEY", ignoreCase = true)) {
                    setupError = error
                } else {
                    snackbar.showSnackbar(error.take(120))
                }
            }
            sending = false
        }
    }

    Scaffold(
        topBar = {
            OdHeader(
                title = dot.name,
                subtitle = "CopilotKit Intelligence",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = OdSpacing.screen).padding(top = OdSpacing.s),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (setupError != null && threadId == null) {
                Box(
                    Modifier.fillMaxSize().padding(OdSpacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(OdSpacing.m),
                    ) {
                        OdErrorCard(
                            message = "Could not start a conversation.",
                            details = setupError,
                        )
                        Button(onClick = onOpenSettings) {
                            Text("Open Settings")
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(OdSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(OdSpacing.s),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        val isUser = msg.role == "user"
                        val isError = msg.text.startsWith("Error:") && !msg.streaming
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 0.92f),
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        isError -> MaterialTheme.colorScheme.errorContainer
                                        isUser -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainer
                                    },
                                ),
                            ) {
                                Column(Modifier.padding(OdSpacing.m)) {
                                    if (isError) {
                                        ExpandableErrorText(msg.text)
                                    } else {
                                        Text(
                                            msg.text.ifEmpty { if (msg.streaming) "…" else "" },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (msg.streaming && msg.text.isNotEmpty()) {
                                            Text(
                                                "…",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (threadId == null) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(OdSpacing.l),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(OdSpacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Message ${dot.name}…") },
                        modifier = Modifier.weight(1f),
                        enabled = !sending && threadId != null,
                        maxLines = 4,
                        shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.width(OdSpacing.s))
                    IconButton(
                        onClick = { send() },
                        enabled = input.trim().isNotEmpty() && !sending && threadId != null,
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}
