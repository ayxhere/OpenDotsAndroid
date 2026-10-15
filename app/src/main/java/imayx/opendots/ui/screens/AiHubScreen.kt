package imayx.opendots.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
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
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Groups") })
            }
            // Note: padding from Scaffold is intentionally minimal here; the
            // inner screens manage their own layout.
            @Suppress("Unused")
            val ignored = padding
            when (tab) {
                0 -> AiChatScreen()
                else -> GroupsScreen(onBack = { tab = 0 }, onOpenGroup = onOpenGroup)
            }
        }
    }
}
