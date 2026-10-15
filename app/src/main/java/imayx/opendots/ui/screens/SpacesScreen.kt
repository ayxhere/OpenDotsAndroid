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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import imayx.opendots.api.ApiClient
import imayx.opendots.api.ApiClient.ApiResult
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
fun SpacesScreen(
    nodeManager: NodeManager,
    onOpenSpace: (Space) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var spaces by remember { mutableStateOf<List<Space>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            val client = ApiClient(TokenStore(context))
            when (val r = client.getWorkspace()) {
                is ApiResult.Ok -> spaces = r.value.spaces
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
                title = "Spaces",
                subtitle = if (spaces.isNotEmpty()) "${spaces.size} spaces" else null,
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
                Icon(Icons.Filled.Add, contentDescription = "New space")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> OdLoading(
                    text = "Loading spaces…",
                    modifier = Modifier.align(Alignment.Center),
                )
                spaces.isEmpty() -> OdEmpty(
                    title = "No spaces yet",
                    body = "Create one to organize pages and dots.",
                    icon = Icons.Filled.Folder,
                    actionLabel = "New space",
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
                    items(spaces, key = { it.id }) { space ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenSpace(space) },
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
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.padding(OdSpacing.s))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        space.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!space.description.isNullOrBlank()) {
                                        Text(
                                            space.description!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    val created = formatInstant(space.createdAt)
                                    if (created.isNotEmpty()) {
                                        Text(
                                            "Created $created",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
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
        var description by remember { mutableStateOf("") }
        var creating by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!creating) showCreate = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("New space") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OdSpacing.s)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(500) },
                        label = { Text("Description (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = MaterialTheme.shapes.small,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            creating = true
                            val client = ApiClient(TokenStore(context))
                            when (val r = client.createSpace(name.trim(), description.trim())) {
                                is ApiResult.Ok -> {
                                    showCreate = false
                                    refresh()
                                    snackbar.showSnackbar("Space created")
                                }
                                is ApiResult.HttpError -> snackbar.showSnackbar("Error ${r.code}: ${r.message}")
                                is ApiResult.NetworkError -> snackbar.showSnackbar("Backend not reachable")
                            }
                            creating = false
                        }
                    },
                    enabled = name.trim().isNotEmpty() && !creating,
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
