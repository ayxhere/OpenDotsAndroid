package imayx.opendots.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import imayx.opendots.ai.AiGroup
import imayx.opendots.ui.components.OdHeader
import imayx.opendots.ui.theme.OdSpacing

/**
 * Multi-provider AI hub: Chat tab (1:1 with failover + voice) and
 * Groups tab (multi-agent conversations).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHubScreen(
    onOpenGroup: (AiGroup) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize()) {
            // Note: padding from Scaffold is intentionally minimal here; the
            // inner screens manage their own layout.
            @Suppress("Unused")
            val ignored = padding
            OdHeader(
                title = "AI Chat",
                subtitle = if (tab == 1) "Groups" else "Multi-provider chat with failover",
                modifier = Modifier.padding(horizontal = OdSpacing.screen).padding(top = OdSpacing.s),
                onBack = if (tab == 1) {
                    { tab = 0 }
                } else null,
            )
            TabRow(
                selectedTabIndex = tab,
                modifier = Modifier.padding(top = OdSpacing.s),
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Groups") })
            }
            when (tab) {
                0 -> AiChatScreen()
                else -> GroupsScreen(
                    showHeader = false,
                    onBack = { tab = 0 },
                    onOpenGroup = onOpenGroup,
                )
            }
        }
    }
}
