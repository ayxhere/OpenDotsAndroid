package imayx.opendots.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import imayx.opendots.BuildConfig
import imayx.opendots.api.ApiClient
import imayx.opendots.api.SetupStatus
import imayx.opendots.auth.TokenStore
import imayx.opendots.node.BackendService
import imayx.opendots.node.NodeManager
import imayx.opendots.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    nodeManager: NodeManager,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    showLogs: Boolean,
    onToggleLogs: () -> Unit,
    onOpenAiProviders: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { ApiClient(TokenStore(context)) }
    var setup by remember { mutableStateOf<SetupStatus?>(null) }
    var storageInfo by remember { mutableStateOf("…") }
    var bundleVersion by remember { mutableStateOf("…") }
    val backendState by nodeManager.state.collectAsState()
    var backgroundMode by remember { mutableStateOf(BackendService.isEnabled(context)) }

    LaunchedEffect(backendState) {
        if (backendState is NodeManager.BackendState.Ready) {
            when (val s = api.getState()) {
                is ApiClient.ApiResult.Ok -> setup = s.value.configured
                else -> {}
            }
        }
        nodeManager.refreshLogTail()
        storageInfo = computeStorageInfo(nodeManager)
        bundleVersion = readBundleVersion(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SectionCard("Appearance") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                },
                            )
                        },
                    )
                }
            }
        }

        SectionCard("Backend") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Background tasks", fontWeight = FontWeight.Medium)
                    Text(
                        "Keep the backend alive for scheduled tasks. Off = backend stops with the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = backgroundMode,
                    onCheckedChange = {
                        backgroundMode = it
                        if (it) BackendService.start(context) else BackendService.stop(context)
                    },
                )
            }
            Text(
                "Storage: $storageInfo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Backend home: ${nodeManager.nodeHome.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "AI providers",
            action = {
                TextButton(onClick = onOpenAiProviders) { Text("Manage") }
            },
        ) {
            Text(
                "Multi-provider chat: add OpenAI, Anthropic, Gemini, Groq, OpenRouter or custom endpoints with automatic failover.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (backendState !is NodeManager.BackendState.Ready) {
                Text(
                    "Provider status is available once the backend is running.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (setup == null) {
                Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ProviderRow("CopilotKit Intelligence", setup!!.intelligence, "INTELLIGENCE_API_KEY — required for all AI chat")
                ProviderRow("Model", setup!!.model, "OPENAI_API_KEY + OPENAI_MODEL")
                ProviderRow("Web research", setup!!.browser || true, "Parallel (default)")
                ProviderRow("Voice", setup!!.voice, "VOICE_API_KEY + VOICE_MODEL")
                ProviderRow("Slack channel", setup!!.slack, "SLACK_* settings")
                if (setup!!.missing.isNotEmpty()) {
                    Text(
                        "Still needed: ${setup!!.missing.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Keys are entered in a later setup screen and stored only in this device's encrypted storage, passed to the backend — never shown back to the UI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("All configured.")
                    }
                }
            }
        }

        SectionCard(
            title = "Runtime logs",
            action = {
                Row {
                    TextButton(onClick = { nodeManager.refreshLogTail(); }) { Text("Refresh") }
                    TextButton(onClick = onToggleLogs) { Text(if (showLogs) "Hide" else "Show") }
                }
            },
        ) {
            if (showLogs) {
                val logs by nodeManager.logTail.collectAsState()
                SelectionContainer {
                    Text(
                        logs.takeLast(120).joinToString("\n").ifBlank { "(no log output yet)" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text(
                    "Node.js stdout/stderr, most recent first in the viewer. Never contains your API token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("Diagnostics") {
            DiagRow("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            DiagRow("Node runtime", "24.20.0 (digidem nodejs-mobile, full build)")
            DiagRow("Backend bundle", bundleVersion)
            DiagRow("Backend port", "127.0.0.1:${NodeManager.BACKEND_PORT} (loopback only)")
            DiagRow("Database", nodeManager.databaseFile.absolutePath)
            DiagRow("Backend state", backendState.javaClass.simpleName)
        }

        SectionCard("Privacy") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Local-first: your workspace database never leaves this phone except for the " +
                        "AI provider calls you configure. The per-install API token lives in " +
                        "Keystore-backed encrypted storage. Usage telemetry is off unless you enable it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun SectionCard(
    title: String,
    action: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun ProviderRow(name: String, configured: Boolean, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (configured) Icons.Filled.CheckCircle else Icons.Filled.Refresh,
            contentDescription = null,
            tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private suspend fun computeStorageInfo(nm: NodeManager): String = withContext(Dispatchers.IO) {
    fun dirSize(f: java.io.File): Long =
        if (!f.exists()) 0L else if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)
    val db = nm.databaseFile
    val dbSize = dirSize(db) + dirSize(java.io.File(db.absolutePath + "-wal"))
    val home = dirSize(nm.nodeHome)
    "database ${formatBytes(dbSize)}, backend home ${formatBytes(home)}"
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "${b / (1024 * 1024)} MB"
}

private fun readBundleVersion(context: Context): String = try {
    context.assets.open("opendots-backend-version.txt").bufferedReader().readText().trim()
} catch (_: Throwable) {
    "not packaged"
}
