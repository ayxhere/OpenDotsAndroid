package imayx.opendots

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import imayx.opendots.api.Page
import imayx.opendots.api.Space
import imayx.opendots.ai.AiGroup
import imayx.opendots.api.Dot
import imayx.opendots.node.NodeManager
import imayx.opendots.ui.components.OdBottomNav
import imayx.opendots.ui.components.OdNavDestination
import imayx.opendots.ui.screens.GroupChatScreen
import imayx.opendots.ui.screens.AiHubScreen
import imayx.opendots.ui.screens.AiProvidersScreen
import imayx.opendots.ui.screens.ChatScreen
import imayx.opendots.ui.screens.DotsScreen
import imayx.opendots.ui.screens.HomeScreen
import imayx.opendots.ui.screens.PageEditorScreen
import imayx.opendots.ui.screens.PagesScreen
import imayx.opendots.ui.screens.SettingsScreen
import imayx.opendots.ui.screens.SpacesScreen
import imayx.opendots.ui.theme.OpenDotsTheme
import imayx.opendots.ui.theme.ThemeMode

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("home", "Home", Icons.Filled.Home),
    Destination("spaces", "Spaces", Icons.Filled.Folder),
    Destination("dots", "Dots", Icons.Filled.SmartToy),
    Destination("ai", "AI Chat", Icons.Filled.ChatBubble),
    Destination("settings", "Settings", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Start the backend when the app opens ("only when needed" = on use).
        NodeManager.get(this).ensureRunning()
        setContent {
            var themeMode by remember { mutableStateOf(readThemeMode()) }
            OpenDotsTheme(mode = themeMode) {
                AppNav(
                    themeMode = themeMode,
                    onThemeModeChange = {
                        themeMode = it
                        saveThemeMode(it)
                    },
                )
            }
        }
    }

    private fun readThemeMode(): ThemeMode =
        when (getSharedPreferences("opendots_prefs", Context.MODE_PRIVATE).getString("theme_mode", "SYSTEM")) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }

    private fun saveThemeMode(mode: ThemeMode) {
        getSharedPreferences("opendots_prefs", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", mode.name).apply()
    }
}

@Composable
private fun AppNav(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val nodeManager = remember { NodeManager.get(appContext) }
    var showLogs by remember { mutableStateOf(false) }
    var selectedSpace by remember { mutableStateOf<Space?>(null) }
    var selectedPage by remember { mutableStateOf<Page?>(null) }
    var selectedDot by remember { mutableStateOf<Dot?>(null) }
    var selectedGroup by remember { mutableStateOf<AiGroup?>(null) }

    // Hide the bottom bar on detail screens (pages list, page editor, chat).
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in destinations.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                // Floating rounded bar: inset from the screen edges and above
                // the system navigation bar / gesture area.
                Box(
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                ) {
                    OdBottomNav(
                        destinations = destinations.map {
                            OdNavDestination(it.route, it.label, it.icon)
                        },
                        currentRoute = currentRoute,
                        onSelect = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner)) {
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        nodeManager = nodeManager,
                        onOpenSettings = { navController.navigate("settings") },
                        onViewLogs = {
                            showLogs = true
                            navController.navigate("settings")
                        },
                    )
                }
                composable("spaces") {
                    SpacesScreen(
                        nodeManager = nodeManager,
                        onOpenSpace = { space ->
                            selectedSpace = space
                            navController.navigate("pages")
                        },
                    )
                }
                composable("pages") {
                    val space = selectedSpace
                    if (space != null) {
                        PagesScreen(
                            nodeManager = nodeManager,
                            space = space,
                            onOpenPage = { page ->
                                selectedPage = page
                                navController.navigate("pageEditor")
                            },
                            onBack = { navController.popBackStack() },
                        )
                    } else {
                        navController.popBackStack()
                    }
                }
                composable("pageEditor") {
                    val page = selectedPage
                    if (page != null) {
                        PageEditorScreen(
                            nodeManager = nodeManager,
                            initialPage = page,
                            onBack = { navController.popBackStack() },
                            onDeleted = {
                                selectedPage = null
                                navController.popBackStack()
                            },
                        )
                    } else {
                        navController.popBackStack()
                    }
                }
                composable("dots") {
                    DotsScreen(
                        nodeManager = nodeManager,
                        onOpenDot = { dot ->
                            selectedDot = dot
                            navController.navigate("chat")
                        },
                    )
                }
                composable("chat") {
                    val dot = selectedDot
                    if (dot != null) {
                        ChatScreen(
                            nodeManager = nodeManager,
                            dot = dot,
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    } else {
                        navController.popBackStack()
                    }
                }
                composable("settings") {
                    SettingsScreen(
                        nodeManager = nodeManager,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        showLogs = showLogs,
                        onToggleLogs = { showLogs = !showLogs },
                        onOpenAiProviders = { navController.navigate("aiProviders") },
                    )
                }
                composable("aiProviders") {
                    AiProvidersScreen(onBack = { navController.popBackStack() })
                }
                composable("ai") {
                    AiHubScreen(
                        onOpenGroup = { group ->
                            selectedGroup = group
                            navController.navigate("groupChat")
                        },
                    )
                }
                composable("groupChat") {
                    val group = selectedGroup
                    if (group != null) {
                        GroupChatScreen(
                            group = group,
                            onBack = { navController.popBackStack() },
                        )
                    } else {
                        navController.popBackStack()
                    }
                }
            }
        }
    }
}
