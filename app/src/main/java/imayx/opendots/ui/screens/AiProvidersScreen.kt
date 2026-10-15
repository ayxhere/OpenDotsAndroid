package imayx.opendots.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import imayx.opendots.ai.AiProviderConfig
import imayx.opendots.ai.AiProviderStore
import imayx.opendots.ai.AiProviderType
import imayx.opendots.ai.AiProxyTransport
import imayx.opendots.ai.AiRouterSettings
import imayx.opendots.ai.AiRouterSettingsStore
import imayx.opendots.auth.TokenStore
import kotlinx.coroutines.launch
import java.util.UUID

private fun keyAlias(id: String) = "provider_key_$id"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProvidersScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val store = remember { AiProviderStore(context) }
    val settingsStore = remember { AiRouterSettingsStore(context) }
    val tokenStore = remember { TokenStore(context) }
    val transport = remember { AiProxyTransport(tokenStore) }

    var providers by remember { mutableStateOf<List<AiProviderConfig>>(emptyList()) }
    var settings by remember { mutableStateOf(AiRouterSettings()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<AiProviderConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showRouterSettings by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            providers = store.load().sortedBy { it.priority }
            settings = settingsStore.load()
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Providers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showRouterSettings = true }) { Text("Router") }
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add provider")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (providers.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No AI providers configured.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add OpenAI, Anthropic, Gemini, Groq, OpenRouter or any OpenAI-compatible endpoint. Keys are stored encrypted on this device only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { creating = true }) { Text("Add provider") }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Tried in order (top first). Use ↑ ↓ to change failover priority.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(providers, key = { it.id }) { p ->
                ProviderRow(
                    config = p,
                    isFirst = providers.firstOrNull()?.id == p.id,
                    isLast = providers.lastOrNull()?.id == p.id,
                    onMoveUp = {
                        val i = providers.indexOf(p)
                        if (i > 0) scope.launch {
                            val ids = providers.toMutableList()
                            val tmp = ids[i - 1]; ids[i - 1] = ids[i]; ids[i] = tmp
                            providers = store.reorder(ids.map { it.id })
                        }
                    },
                    onMoveDown = {
                        val i = providers.indexOf(p)
                        if (i < providers.size - 1) scope.launch {
                            val ids = providers.toMutableList()
                            val tmp = ids[i + 1]; ids[i + 1] = ids[i]; ids[i] = tmp
                            providers = store.reorder(ids.map { it.id })
                        }
                    },
                    onToggle = { scope.launch { providers = store.update(p.copy(enabled = !p.enabled)) } },
                    onEdit = { editing = p },
                    onDelete = {
                        scope.launch {
                            tokenStore.clearSecret(keyAlias(p.id))
                            providers = store.remove(p.id)
                            snackbar.showSnackbar("Removed ${p.name}")
                        }
                    },
                    onTest = { onResult ->
                        scope.launch {
                            val key = tokenStore.getSecret(keyAlias(p.id)) ?: ""
                            val r = transport.testConnection(p.type, p.baseUrl, key, p.model)
                            onResult(r.ok, r.message)
                        }
                    }
                )
            }
        }
    }

    if (creating || editing != null) {
        ProviderEditorDialog(
            existing = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, type, baseUrl, model, apiKey ->
                scope.launch {
                    val id = editing?.id ?: UUID.randomUUID().toString()
                    val alias = keyAlias(id)
                    if (apiKey.isNotBlank()) tokenStore.setSecret(alias, apiKey)
                    val config = AiProviderConfig(
                        id = id,
                        name = name,
                        type = type,
                        baseUrl = baseUrl.takeIf { it.isNotBlank() },
                        model = model,
                        keyAlias = alias,
                        enabled = editing?.enabled ?: true,
                        priority = editing?.priority ?: (providers.maxOfOrNull { it.priority }?.plus(1) ?: 0),
                        freeOnly = editing?.freeOnly ?: false,
                    )
                    providers = if (editing != null) store.update(config) else store.add(config)
                    creating = false; editing = null
                    snackbar.showSnackbar("Saved ${config.name}")
                }
            },
            onFetchModels = { type, baseUrl, apiKey, onDone ->
                scope.launch {
                    val r = transport.fetchModels(type, baseUrl.takeIf { it.isNotBlank() }, apiKey)
                    onDone(r)
                }
            },
            onTest = { type, baseUrl, model, apiKey, onDone ->
                scope.launch {
                    val r = transport.testConnection(type, baseUrl.takeIf { it.isNotBlank() }, apiKey, model)
                    onDone(r.ok, r.message)
                }
            }
        )
    }

    if (showRouterSettings) {
        RouterSettingsDialog(
            settings = settings,
            onDismiss = { showRouterSettings = false },
            onSave = { s ->
                scope.launch {
                    settingsStore.save(s)
                    settings = s
                    showRouterSettings = false
                }
            }
        )
    }
}

@Composable
private fun ProviderRow(
    config: AiProviderConfig,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: ((Boolean, String) -> Unit) -> Unit,
) {
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (config.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth().clickable { onEdit() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(config.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${config.type.displayName} · ${config.model}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (config.freeOnly) {
                        Text(
                            "free-fallback only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                IconButton(onClick = onMoveUp, enabled = !isFirst) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = !isLast) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
                Switch(checked = config.enabled, onCheckedChange = { onToggle() })
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        testing = true; testResult = null
                        onTest { ok, msg -> testing = false; testResult = ok to msg }
                    },
                    enabled = !testing
                ) { Text(if (testing) "Testing…" else "Test") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                }
                testResult?.let { (ok, msg) ->
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (ok) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        msg.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderEditorDialog(
    existing: AiProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, type: AiProviderType, baseUrl: String, model: String, apiKey: String) -> Unit,
    onFetchModels: (AiProviderType, String, String, (Result<List<String>>) -> Unit) -> Unit,
    onTest: (AiProviderType, String, String, String, (Boolean, String) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: AiProviderType.OPENAI) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var keyChangedNote by remember { mutableStateOf(false) }

    val canSave = name.trim().isNotEmpty() && model.trim().isNotEmpty() &&
        (existing != null || apiKey.isNotBlank()) &&
        (type != AiProviderType.CUSTOM || baseUrl.trim().isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit provider" else "Add provider") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item {
                    Column {
                        OutlinedTextField(
                            value = type.displayName, onValueChange = {},
                            readOnly = true, label = { Text("Provider") },
                            trailingIcon = {
                                IconButton(onClick = { typeMenu = true }) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            AiProviderType.values().forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.displayName) },
                                    onClick = { type = t; typeMenu = false; fetchedModels = emptyList() }
                                )
                            }
                        }
                    }
                }
                if (type == AiProviderType.CUSTOM) {
                    item {
                        OutlinedTextField(
                            value = baseUrl, onValueChange = { baseUrl = it },
                            label = { Text("Base URL (https)") },
                            placeholder = { Text("https://my-gateway.example.com/v1") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                }
                item {
                    Column {
                        OutlinedTextField(
                            value = model, onValueChange = { model = it },
                            label = { Text("Model ID") },
                            placeholder = { Text("e.g. gpt-4o-mini") },
                            trailingIcon = {
                                IconButton(onClick = { modelMenu = fetchedModels.isNotEmpty() }) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Models")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            fetchedModels.take(100).forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = { model = m; modelMenu = false }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    fetchingModels = true
                                    onFetchModels(type, baseUrl, apiKey) { r ->
                                        fetchingModels = false
                                        r.onSuccess {
                                            fetchedModels = it
                                            if (it.isNotEmpty()) modelMenu = true
                                        }
                                        r.onFailure { testMsg = "Model list failed: ${it.message}" }
                                    }
                                },
                                enabled = apiKey.isNotBlank() && !fetchingModels
                            ) { Text(if (fetchingModels) "Loading…" else "Fetch models from API") }
                        }
                        Text(
                            "Enter the key first, then fetch — or type the model ID manually.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it; keyChangedNote = existing != null },
                        label = { Text(if (existing != null) "API key (leave blank to keep)" else "API key") },
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { keyVisible = !keyVisible }) {
                                Text(if (keyVisible) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Text(
                        "Stored encrypted in the Android Keystore. Never leaves this device except to the provider via the local backend.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                testing = true; testMsg = null
                                onTest(type, baseUrl, model, apiKey) { ok, msg ->
                                    testing = false
                                    testMsg = (if (ok) "✓ " else "✗ ") + msg
                                }
                            },
                            enabled = apiKey.isNotBlank() && model.trim().isNotEmpty() && !testing
                        ) { Text(if (testing) "Testing…" else "Test connection") }
                    }
                    testMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name.trim(), type, baseUrl.trim(), model.trim(), apiKey) }, enabled = canSave) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RouterSettingsDialog(
    settings: AiRouterSettings,
    onDismiss: () -> Unit,
    onSave: (AiRouterSettings) -> Unit,
) {
    var autoFailover by remember { mutableStateOf(settings.autoFailover) }
    var freeOnly by remember { mutableStateOf(settings.freeModelOnlyFallback) }
    var maxCost by remember { mutableStateOf(settings.maxCostUsd?.toString() ?: "") }
    var maxAttempts by remember { mutableStateOf(settings.maxAttempts.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Router & failover") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Automatic failover", Modifier.weight(1f))
                    Switch(checked = autoFailover, onCheckedChange = { autoFailover = it })
                }
                Text(
                    "On timeout, rate limit, outage or missing model, try the next enabled provider in priority order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Free-model-only fallback", Modifier.weight(1f))
                    Switch(checked = freeOnly, onCheckedChange = { freeOnly = it })
                }
                Text(
                    "Fallback attempts only use models known to be free (e.g. OpenRouter :free models). Never falls back from a paid model to another paid model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = maxCost, onValueChange = { maxCost = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Max cost per run (USD, empty = no cap)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = maxAttempts, onValueChange = { maxAttempts = it.filter { c -> c.isDigit() }.take(1) },
                    label = { Text("Max provider attempts per message") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    AiRouterSettings(
                        autoFailover = autoFailover,
                        freeModelOnlyFallback = freeOnly,
                        maxCostUsd = maxCost.toDoubleOrNull(),
                        maxAttempts = maxAttempts.toIntOrNull()?.coerceIn(1, 9) ?: 3,
                        attemptTimeoutSec = settings.attemptTimeoutSec,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
