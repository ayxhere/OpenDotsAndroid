package imayx.opendots.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import imayx.opendots.api.ApiClient
import imayx.opendots.api.ApiClient.ApiResult
import imayx.opendots.api.Dot
import imayx.opendots.api.Space
import imayx.opendots.auth.TokenStore
import imayx.opendots.node.NodeManager
import imayx.opendots.ui.components.OdEmpty
import imayx.opendots.ui.components.OdHeader
import imayx.opendots.ui.components.OdLoading
import imayx.opendots.ui.components.formatInstant
import imayx.opendots.ui.theme.OdSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotsScreen(
    nodeManager: NodeManager,
    onOpenDot: (Dot) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var dots by remember { mutableStateOf<List<Dot>>(emptyList()) }
    var spaces by remember { mutableStateOf<List<Space>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            val client = ApiClient(TokenStore(context))
            when (val r = client.getWorkspace()) {
                is ApiResult.Ok -> {
                    dots = r.value.dots
                    spaces = r.value.spaces
                }
                is ApiResult.HttpError -> snackbar.showSnackbar("Error ${r.code}: ${r.message}")
                is ApiResult.NetworkError -> snackbar.showSnackbar("Backend not reachable: ${r.message}")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            OdHeader(
                title = "Dots",
                subtitle = if (dots.isNotEmpty()) "${dots.size} AI assistants" else null,
                modifier = Modifier.padding(horizontal = OdSpacing.screen).padding(top = OdSpacing.s),
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New dot")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> OdLoading(
                    text = "Loading dots…",
                    modifier = Modifier.align(Alignment.Center),
                )
                dots.isEmpty() -> OdEmpty(
                    title = "No dots yet",
                    body = "A Dot is an AI assistant with its own instructions and tool permissions.",
                    icon = Icons.Filled.SmartToy,
                    actionLabel = "New dot",
                    onAction = { showCreate = true },
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = OdSpacing.screen,
                        end = OdSpacing.screen,
                        top = OdSpacing.m,
                        bottom = OdSpacing.navClearance,
                    ),
                    verticalArrangement = Arrangement.spacedBy(OdSpacing.s),
                ) {
                    items(dots, key = { it.id }) { dot ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenDot(dot) },
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(OdSpacing.l),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.padding(OdSpacing.s))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        dot.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (dot.instructions.isNotBlank()) {
                                        Text(
                                            dot.instructions,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        buildString {
                                            append(if (dot.researchAllowed) "Research on" else "Research off")
                                            append(" · ")
                                            append(if (dot.memoryAllowed) "Memory on" else "Memory off")
                                            val created = formatInstant(dot.createdAt)
                                            if (created.isNotEmpty()) append(" · $created")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var instructions by remember { mutableStateOf("") }
        var researchAllowed by remember { mutableStateOf(false) }
        var memoryAllowed by remember { mutableStateOf(false) }
        var selectedSpaceId by remember { mutableStateOf(spaces.firstOrNull()?.id ?: "") }
        var creating by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!creating) showCreate = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("New dot") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OdSpacing.s)) {
                    if (spaces.isEmpty()) {
                        Text(
                            "Create a Space first — a Dot must belong to a space.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        val selectedName = spaces.find { it.id == selectedSpaceId }?.name ?: "Select space"
                        Box {
                            OutlinedTextField(
                                value = selectedName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Space") },
                                trailingIcon = {
                                    IconButton(onClick = { expanded = true }) {
                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose space")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                spaces.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.name) },
                                        onClick = {
                                            selectedSpaceId = s.id
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    )
                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it.take(2000) },
                        label = { Text("Role instructions") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        shape = MaterialTheme.shapes.small,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Allow research tools", Modifier.weight(1f))
                        Switch(researchAllowed, { researchAllowed = it })
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Allow memory tools", Modifier.weight(1f))
                        Switch(memoryAllowed, { memoryAllowed = it })
                    }
                    Text(
                        "Tool permissions are explicit and stored with the Dot. They can be changed later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            creating = true
                            val client = ApiClient(TokenStore(context))
                            when (val r = client.createDot(
                                name.trim(), instructions.trim(), selectedSpaceId,
                                researchAllowed, memoryAllowed,
                            )) {
                                is ApiResult.Ok -> {
                                    showCreate = false
                                    refresh()
                                    snackbar.showSnackbar("Dot created")
                                }
                                is ApiResult.HttpError -> snackbar.showSnackbar("Error ${r.code}: ${r.message}")
                                is ApiResult.NetworkError -> snackbar.showSnackbar("Backend not reachable")
                            }
                            creating = false
                        }
                    },
                    enabled = name.trim().isNotEmpty() && instructions.trim().length >= 3 &&
                        selectedSpaceId.isNotEmpty() && !creating,
                ) { Text(if (creating) "Creating…" else "Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }, enabled = !creating) {
                    Text("Cancel")
                }
            },
        )
    }
}
