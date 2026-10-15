package imayx.opendots.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import imayx.opendots.ai.AiGroup
import imayx.opendots.ai.AiGroupStore
import imayx.opendots.ai.AiProviderConfig
import imayx.opendots.ai.AiProviderStore
import imayx.opendots.ai.GroupMode
import imayx.opendots.ai.GroupParticipant
import imayx.opendots.ui.components.OdEmpty
import imayx.opendots.ui.components.OdHeader
import imayx.opendots.ui.components.OdLoading
import imayx.opendots.ui.theme.OdSpacing
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    onBack: () -> Unit,
    onOpenGroup: (AiGroup) -> Unit,
    showHeader: Boolean = true,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val groupStore = remember { AiGroupStore(context) }
    val providerStore = remember { AiProviderStore(context) }

    var groups by remember { mutableStateOf<List<AiGroup>>(emptyList()) }
    var providers by remember { mutableStateOf<List<AiProviderConfig>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<AiGroup?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            groups = groupStore.loadGroups()
            providers = providerStore.load().sortedBy { it.priority }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            if (showHeader) {
                OdHeader(
                    title = "AI Groups",
                    subtitle = if (groups.isNotEmpty()) "${groups.size} groups" else null,
                    onBack = onBack,
                    modifier = Modifier.padding(horizontal = OdSpacing.screen).padding(top = OdSpacing.s),
                    actions = {
                        IconButton(onClick = { creating = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "New group")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            loading -> OdLoading(
                text = "Loading groups…",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            groups.isEmpty() -> OdEmpty(
                title = "No groups yet",
                body = "Create a group, add AI agents from your configured providers, and chat with several AIs at once.",
                icon = Icons.Filled.Groups,
                actionLabel = "New group",
                onAction = { creating = true },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = OdSpacing.screen,
                    end = OdSpacing.screen,
                    top = OdSpacing.m,
                    bottom = OdSpacing.m,
                ),
                verticalArrangement = Arrangement.spacedBy(OdSpacing.s),
            ) {
                items(groups, key = { it.id }) { g ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenGroup(g) },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Row(
                            Modifier.padding(OdSpacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Groups,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(OdSpacing.s))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    g.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${g.participants.size} agents · ${g.mode.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    g.participants.joinToString { it.agentName },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { editing = g }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    groupStore.deleteGroup(g.id)
                                    groups = groupStore.loadGroups()
                                }
                            }) {
                                Icon(
                                    Icons.Filled.Delete, contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        GroupEditorDialog(
            existing = editing,
            providers = providers,
            onDismiss = { creating = false; editing = null },
            onSave = { group ->
                scope.launch {
                    groupStore.saveGroup(group)
                    groups = groupStore.loadGroups()
                    creating = false; editing = null
                    snackbar.showSnackbar("Saved ${group.name}")
                }
            },
        )
    }
}

@Composable
private fun GroupEditorDialog(
    existing: AiGroup?,
    providers: List<AiProviderConfig>,
    onDismiss: () -> Unit,
    onSave: (AiGroup) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var mode by remember { mutableStateOf(existing?.mode ?: GroupMode.ROUND_ROBIN) }
    var modeMenu by remember { mutableStateOf(false) }
    var participants by remember {
        mutableStateOf(existing?.participants ?: emptyList())
    }
    var maxTurns by remember { mutableStateOf(existing?.maxTurns?.toString() ?: "12") }
    var maxCost by remember { mutableStateOf(existing?.maxCostUsd?.toString() ?: "") }
    var addMenu by remember { mutableStateOf(false) }

    val canSave = name.trim().isNotEmpty() && participants.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(if (existing != null) "Edit group" else "New group") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(OdSpacing.s)) {
                item {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Group name") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = MaterialTheme.shapes.small,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mode: ", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { modeMenu = true }) { Text(mode.displayName) }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            GroupMode.values().forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.displayName) },
                                    onClick = { mode = m; modeMenu = false },
                                )
                            }
                        }
                    }
                    Text(
                        when (mode) {
                            GroupMode.MENTION -> "Only @mentioned agents reply."
                            GroupMode.ROUND_ROBIN -> "Every agent replies once per message, in order."
                            GroupMode.MODERATED -> "Agents discuss, then a moderator summarizes."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text("Agents (${participants.size})", style = MaterialTheme.typography.titleSmall)
                }
                items(participants, key = { it.agentName + it.providerConfigId }) { p ->
                    val cfg = providers.find { it.id == p.providerConfigId }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.agentName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${cfg?.name ?: "?"} · ${p.model ?: cfg?.model ?: "?"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { participants = participants - p }) {
                            Text("Remove")
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { addMenu = true },
                            enabled = providers.isNotEmpty(),
                        ) { Text("+ Add agent") }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            providers.filter { it.enabled }.forEach { cfg ->
                                DropdownMenuItem(
                                    text = { Text("${cfg.name} · ${cfg.model}") },
                                    onClick = {
                                        val base = cfg.name.replace(Regex("[^A-Za-z0-9]"), "")
                                        var n = base.ifEmpty { "Agent" }
                                        var i = 2
                                        while (participants.any { it.agentName == n }) n = "$base$i".also { i++ }
                                        participants = participants + GroupParticipant(
                                            providerConfigId = cfg.id,
                                            agentName = n,
                                        )
                                        addMenu = false
                                    },
                                )
                            }
                        }
                    }
                    if (providers.isEmpty()) {
                        Text(
                            "Configure providers first (Settings → AI Providers).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(OdSpacing.s)) {
                        OutlinedTextField(
                            value = maxTurns,
                            onValueChange = { maxTurns = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("Max turns") },
                            modifier = Modifier.weight(1f), singleLine = true,
                            shape = MaterialTheme.shapes.small,
                        )
                        OutlinedTextField(
                            value = maxCost,
                            onValueChange = { maxCost = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Max USD (empty=none)") },
                            modifier = Modifier.weight(1f), singleLine = true,
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                    Text(
                        "Turn and cost caps prevent runaway agent loops.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        (existing ?: AiGroup(name = name.trim())).copy(
                            name = name.trim(),
                            participants = participants,
                            mode = mode,
                            maxTurns = maxTurns.toIntOrNull()?.coerceIn(1, 50) ?: 12,
                            maxCostUsd = maxCost.toDoubleOrNull(),
                        ),
                    )
                },
                enabled = canSave,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
