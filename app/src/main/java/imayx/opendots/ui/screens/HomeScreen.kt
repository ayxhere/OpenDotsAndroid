package imayx.opendots.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import imayx.opendots.api.ApiClient
import imayx.opendots.api.SetupStatus
import imayx.opendots.api.WorkspaceResponse
import imayx.opendots.auth.TokenStore
import imayx.opendots.node.NodeManager
import imayx.opendots.ui.components.OdEmpty
import imayx.opendots.ui.components.OdErrorCard
import imayx.opendots.ui.components.OdHeader
import imayx.opendots.ui.components.OdLoading
import imayx.opendots.ui.components.OdSectionCard
import imayx.opendots.ui.theme.OdSpacing
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    var workspace: WorkspaceResponse? = null
        private set
    var loading by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var errorDetails: String? by mutableStateOf(null)
        private set

    fun refresh(api: ApiClient) {
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            errorDetails = null
            when (val w = api.getWorkspace()) {
                is ApiClient.ApiResult.Ok -> workspace = w.value
                is ApiClient.ApiResult.HttpError -> {
                    error = "Couldn't load the workspace (HTTP ${w.code})."
                    errorDetails = w.message
                }
                is ApiClient.ApiResult.NetworkError -> {
                    error = "Cannot reach the backend."
                    errorDetails = w.message
                }
            }
            loading = false
        }
    }
}

@Composable
fun HomeScreen(
    nodeManager: NodeManager,
    onOpenSettings: () -> Unit,
    onViewLogs: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val backendState by nodeManager.state.collectAsState()
    val api = remember { ApiClient(TokenStore(context)) }

    LaunchedEffect(backendState) {
        nodeManager.ensureRunning()
        if (backendState is NodeManager.BackendState.Ready) vm.refresh(api)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OdSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(OdSpacing.m),
    ) {
        Spacer(Modifier.height(OdSpacing.s))
        OdHeader(
            title = "Workspace",
            subtitle = "Local-first AI workspace",
            actions = {
                IconButton(onClick = { vm.refresh(api) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
        )

        BackendStatusCard(
            state = backendState,
            onStart = { nodeManager.ensureRunning() },
            onRestartApp = { nodeManager.restartApp() },
            onViewLogs = onViewLogs,
        )

        when (backendState) {
            is NodeManager.BackendState.Ready -> {
                if (vm.loading && vm.workspace == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        OdLoading(
                            text = "Loading workspace…",
                            modifier = Modifier.fillMaxWidth().padding(OdSpacing.xl),
                        )
                    }
                }
                vm.error?.let {
                    OdErrorCard(
                        message = it,
                        details = vm.errorDetails,
                        onRetry = { vm.refresh(api) },
                    )
                }
                vm.workspace?.let { ws ->
                    StatsRow(ws)
                    SetupChecklistCard(setup = ws.setup, onOpenSettings = onOpenSettings)
                    RecentDotsCard(ws)
                }
            }
            else -> {
                OdEmpty(
                    title = "Backend not running",
                    body = "Workspace data appears once the backend is running.",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(OdSpacing.navClearance))
    }
}

@Composable
private fun BackendStatusCard(
    state: NodeManager.BackendState,
    onStart: () -> Unit,
    onRestartApp: () -> Unit,
    onViewLogs: () -> Unit,
) {
    OdSectionCard(title = "Backend") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint, label) = when (state) {
                is NodeManager.BackendState.Ready ->
                    Triple(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, "Backend running")
                is NodeManager.BackendState.Failed ->
                    Triple(Icons.Filled.Error, MaterialTheme.colorScheme.error, "Backend failed")
                is NodeManager.BackendState.Crashed ->
                    Triple(Icons.Filled.Error, MaterialTheme.colorScheme.error, "Backend crashed")
                else ->
                    Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.secondary, backendLabel(state))
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(OdSpacing.s))
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            backendDetail(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(OdSpacing.s)) {
            when (state) {
                is NodeManager.BackendState.Failed -> {
                    Button(onClick = onStart) { Text("Retry") }
                    OutlinedButton(onClick = onViewLogs) { Text("View logs") }
                }
                is NodeManager.BackendState.Crashed -> {
                    Button(onClick = onRestartApp) { Text("Restart app") }
                    OutlinedButton(onClick = onViewLogs) { Text("View logs") }
                }
                else -> {
                    OutlinedButton(onClick = onViewLogs) { Text("Runtime logs") }
                }
            }
        }
    }
}

private fun backendLabel(state: NodeManager.BackendState): String = when (state) {
    is NodeManager.BackendState.Stopped -> "Backend stopped"
    is NodeManager.BackendState.Extracting -> "Preparing backend…"
    is NodeManager.BackendState.Starting -> "Starting backend…"
    is NodeManager.BackendState.WaitingForHealth -> "Waiting for backend…"
    else -> "Backend"
}

private fun backendDetail(state: NodeManager.BackendState): String = when (state) {
    is NodeManager.BackendState.Ready ->
        "Embedded Node.js is serving the OpenDots API on 127.0.0.1:${NodeManager.BACKEND_PORT} (loopback only)."
    is NodeManager.BackendState.Failed -> state.reason
    is NodeManager.BackendState.Crashed ->
        "The Node runtime exited (code ${state.exitCode}). It cannot be restarted inside this process — restart the app to recover."
    is NodeManager.BackendState.Stopped -> "Tap retry to start the embedded backend."
    else -> "Booting the embedded Node.js runtime…"
}

@Composable
private fun StatsRow(ws: WorkspaceResponse) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OdSpacing.m),
    ) {
        StatCard("Spaces", ws.spaces.size.toString(), Modifier.weight(1f))
        StatCard("Dots", ws.dots.size.toString(), Modifier.weight(1f))
        StatCard("Chats", ws.conversations.size.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(OdSpacing.l)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupChecklistCard(setup: SetupStatus?, onOpenSettings: () -> Unit) {
    OdSectionCard(title = "AI setup") {
        if (setup == null) {
            Text("Checking configuration…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (setup.missing.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(OdSpacing.s))
                Text("AI services are configured.")
            }
        } else {
            Text(
                "To use AI conversations, configure: ${setup.missing.joinToString(", ")}.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenSettings) { Text("Open setup") }
        }
    }
}

@Composable
private fun RecentDotsCard(ws: WorkspaceResponse) {
    if (ws.dots.isEmpty()) return
    OdSectionCard(title = "Dots") {
        ws.dots.take(5).forEach { dot ->
            Column {
                Text(dot.name, fontWeight = FontWeight.Medium)
                if (dot.instructions.isNotBlank()) {
                    Text(
                        dot.instructions.take(120),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
