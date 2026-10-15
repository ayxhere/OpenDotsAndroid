package imayx.opendots.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import imayx.opendots.api.ApiClient
import imayx.opendots.api.ApiClient.ApiResult
import imayx.opendots.api.Page
import imayx.opendots.auth.TokenStore
import imayx.opendots.node.NodeManager
import imayx.opendots.ui.components.OdErrorCard
import imayx.opendots.ui.components.OdHeader
import imayx.opendots.ui.components.OdLoading
import imayx.opendots.ui.theme.OdSpacing
import kotlinx.coroutines.launch

/**
 * Native page editor with optimistic revision handling.
 *
 * The backend enforces revision checks on PATCH: a stale revision returns
 * 409 and the edit is rejected. This screen surfaces that conflict honestly
 * instead of silently overwriting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(
    nodeManager: NodeManager,
    initialPage: Page,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var page by remember { mutableStateOf(initialPage) }
    var title by remember { mutableStateOf(initialPage.title) }
    var content by remember { mutableStateOf(initialPage.content) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var conflictMessage by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            val client = ApiClient(TokenStore(context))
            when (val r = client.getPage(page.spaceId, page.id)) {
                is ApiResult.Ok -> {
                    page = r.value
                    title = r.value.title
                    content = r.value.content
                    dirty = false
                    conflictMessage = null
                }
                is ApiResult.HttpError -> snackbar.showSnackbar("Error ${r.code}: ${r.message}")
                is ApiResult.NetworkError -> snackbar.showSnackbar("Backend not reachable")
            }
            loading = false
        }
    }

    LaunchedEffect(page.id) { refresh() }

    fun save() {
        scope.launch {
            saving = true
            val client = ApiClient(TokenStore(context))
            when (val r = client.patchPage(page.spaceId, page.id, title, content, page.revision)) {
                is ApiResult.Ok -> {
                    page = r.value
                    dirty = false
                    conflictMessage = null
                    snackbar.showSnackbar("Saved (revision ${r.value.revision})")
                }
                is ApiClient.ApiResult.HttpError -> {
                    if (r.code == 409) {
                        conflictMessage =
                            "This page changed elsewhere (your revision ${page.revision} is stale). " +
                                "Reload to see the latest version before editing — your unsaved text is kept above."
                    } else {
                        snackbar.showSnackbar("Error ${r.code}: ${r.message}")
                    }
                }
                is ApiResult.NetworkError -> snackbar.showSnackbar("Backend not reachable")
            }
            saving = false
        }
    }

    Scaffold(
        topBar = {
            OdHeader(
                title = if (title.isBlank()) "Untitled" else title,
                subtitle = "Revision ${page.revision}${if (dirty) " · unsaved changes" else ""}",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = OdSpacing.screen).padding(top = OdSpacing.s),
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !saving) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { showDelete = true }, enabled = !saving) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete page")
                    }
                    IconButton(
                        onClick = { save() },
                        enabled = dirty && !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Save",
                                tint = if (dirty) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                OdLoading(
                    text = "Loading page…",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(OdSpacing.screen),
                    verticalArrangement = Arrangement.spacedBy(OdSpacing.m),
                ) {
                    if (conflictMessage != null) {
                        OdErrorCard(
                            message = "This page changed elsewhere — your revision is stale.",
                            details = conflictMessage,
                            onRetry = { refresh() },
                        )
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; dirty = true },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        shape = MaterialTheme.shapes.small,
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it; dirty = true },
                        label = { Text("Content") },
                        modifier = Modifier.fillMaxSize(),
                        enabled = !saving,
                        shape = MaterialTheme.shapes.small,
                    )
                }
            }
        }
    }

    if (showDelete) {
        var deleting by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!deleting) showDelete = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Delete page?") },
            text = { Text("“${page.title}” will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            deleting = true
                            val client = ApiClient(TokenStore(context))
                            when (val r = client.deletePage(page.spaceId, page.id)) {
                                is ApiResult.Ok -> onDeleted()
                                is ApiResult.HttpError -> {
                                    snackbar.showSnackbar("Error ${r.code}: ${r.message}")
                                    showDelete = false
                                }
                                is ApiResult.NetworkError -> {
                                    snackbar.showSnackbar("Backend not reachable")
                                    showDelete = false
                                }
                            }
                            deleting = false
                        }
                    },
                    enabled = !deleting,
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }, enabled = !deleting) {
                    Text("Cancel")
                }
            },
        )
    }
}
