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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import imayx.opendots.api.ApiState
import imayx.opendots.api.WorkspaceResponse
import imayx.opendots.auth.TokenStore
import imayx.opendots.node.NodeManager
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    var workspace: WorkspaceResponse? = null
        private set
    var apiState: ApiState? = null
        private set
    var loading by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    fun refresh(api: ApiClient) {
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            when (val s = api.getState()) {
                is ApiClient.ApiResult.Ok -> apiState = s.value
                is ApiClient.ApiResult.HttpError -> error = "Backend error ${s.code}: ${s.message}"
                is ApiClient.ApiResult.NetworkError -> error = "Cannot reach backend: ${s.message}"
            }
            when (val w = api.getWorkspace()) {
                is ApiClient.ApiResult.Ok -> workspace = w.value
                is ApiClient.ApiResult.HttpError -> if (error == null) error = "Backend error ${w.code}: ${w.message}"
                is ApiClient.ApiResult.NetworkError -> if (error == null) error = "Cannot reach backend: ${w.message}"
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Workspace",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
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
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Loading workspace…")
                        }
                    }
                }
                vm.error?.let {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                            OutlinedButton(onClick = { vm.refresh(api) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
                vm.workspace?.let { ws ->
                    StatsRow(ws)
                    SetupChecklistCard(state = vm.apiState, onOpenSettings = onOpenSettings)
                    RecentDotsCard(ws)
                }
            }
            else -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Workspace data appears once the backend is running.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(72.dp)) // bottom nav clearance
    }
}

@Composable
private fun BackendStatusCard(
    state: NodeManager.BackendState,
    onStart: () -> Unit,
    onRestartApp: () -> Unit,
    onViewLogs: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Icon(icon, contentDescription = null, tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                backendDetail(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard("Spaces", ws.spaces.size.toString(), Modifier.weight(1f))
        StatCard("Dots", ws.dots.size.toString(), Modifier.weight(1f))
        StatCard("Chats", ws.conversations.size.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupChecklistCard(state: ApiState?, onOpenSettings: () -> Unit) {
    val setup = state?.configured
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (setup == null) {
                Text("Checking configuration…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (setup.missing.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
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
}

@Composable
private fun RecentDotsCard(ws: WorkspaceResponse) {
    if (ws.dots.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
}
