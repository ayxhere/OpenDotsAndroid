package imayx.opendots.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import imayx.opendots.api.ApiClient
import imayx.opendots.api.ApiClient.ApiResult
import imayx.opendots.auth.TokenStore
import imayx.opendots.node.NodeManager
import imayx.opendots.ui.theme.OdSpacing
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Approval request for a page draft or connection action.
 * These originate from AG-UI tool calls during chat that require user approval.
 */
@Serializable
data class ApprovalRequest(
    val toolCallId: String,
    val type: String, // "page-review" | "connection-action"
    val title: String,
    val description: String,
    val details: Map<String, String> = emptyMap(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalDialog(
    nodeManager: NodeManager,
    conversationId: String,
    request: ApprovalRequest,
    onDismiss: () -> Unit,
    onDecision: (approved: Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var processing by remember { mutableStateOf(false) }
    // Idempotency: track if we've already sent a decision for this toolCallId.
    var decided by remember { mutableStateOf(false) }

    fun sendDecision(approved: Boolean) {
        if (decided || processing) return // Duplicate-execution protection
        scope.launch {
            processing = true
            val client = ApiClient(TokenStore(context))
            val result = when (request.type) {
                "page-review" -> {
                    if (approved) {
                        // The page draft details contain spaceId, title, content.
                        // For now, we approve by creating the reviewed page.
                        // Full implementation needs the draft from the AG-UI event.
                        client.postReviewedPage(
                            conversationId,
                            request.toolCallId,
                            request.details["spaceId"] ?: "",
                            request.details["title"] ?: request.title,
                            request.details["content"] ?: ""
                        )
                    } else {
                        // Rejection is implicit: we just don't create the page.
                        // The backend TTL will expire the pending approval.
                        ApiResult.Ok(Unit)
                    }
                }
                "connection-action" -> {
                    // Connection actions are approved via the dedicated endpoint.
                    // Full implementation needs the action details from AG-UI.
                    if (approved) {
                        client.postConnectionAction(conversationId, request.toolCallId)
                    } else {
                        ApiResult.Ok(Unit)
                    }
                }
                else -> ApiResult.HttpError(-1, "Unknown approval type")
            }
            when (result) {
                is ApiResult.Ok -> {
                    decided = true
                    onDecision(approved)
                }
                is ApiResult.HttpError -> {
                    snackbar.showSnackbar("Error ${result.code}: ${result.message}")
                }
                is ApiResult.NetworkError -> {
                    snackbar.showSnackbar("Backend not reachable")
                }
            }
            processing = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!processing) onDismiss() },
        shape = MaterialTheme.shapes.large,
        title = { Text("Approval required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OdSpacing.s)) {
                Text(request.title, style = MaterialTheme.typography.titleMedium)
                Text(request.description, style = MaterialTheme.typography.bodyMedium)
                if (request.details.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    request.details.forEach { (k, v) ->
                        Text(
                            "$k: ${v.take(100)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "This action was requested by the AI. Review carefully before approving. " +
                        "Approvals expire after the TTL and cannot be executed twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { sendDecision(true) },
                enabled = !processing && !decided
            ) {
                if (processing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(4.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Text("Approve")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { sendDecision(false) },
                enabled = !processing && !decided
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
                Text("Reject")
            }
        }
    )
}
