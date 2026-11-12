package com.namanbansal.forge.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RecentsViewModel
import com.namanbansal.forge.data.SettingsRepository
import com.namanbansal.forge.ui.toolkit.ForgeSnackbarHost
import com.namanbansal.forge.ui.toolkit.LocalForgeSnackbar
import com.namanbansal.forge.ui.toolkit.OpenWithSheet
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.data.Settings
import com.namanbansal.forge.tools.Category
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.ToolScreens
import com.namanbansal.forge.tools.ToolStatus
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.CategoryScreen
import com.namanbansal.forge.ui.screens.ComingSoonScreen
import com.namanbansal.forge.ui.screens.FilesScreen
import com.namanbansal.forge.ui.screens.HomeScreen
import com.namanbansal.forge.ui.screens.LicensesScreen
import com.namanbansal.forge.ui.screens.SearchScreen
import com.namanbansal.forge.ui.screens.SettingsScreen
import com.namanbansal.forge.ui.screens.UpdateSheet
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val FILES = "files"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val LICENSES = "licenses"
    const val CATEGORY = "category/{name}"
    const val TOOL = "tool/{code}"
    fun category(c: Category) = "category/${c.name}"
    fun tool(code: String) = "tool/${Uri.encode(code)}"
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(Routes.HOME, "Home", ForgeIcons.Home),
    NavItem(Routes.FILES, "Files", ForgeIcons.Folder),
    NavItem(Routes.SETTINGS, "Settings", ForgeIcons.Settings),
)

@Composable
fun ForgeRoot(
    app: ForgeApp,
    settings: Settings,
    incoming: List<PickedFile> = emptyList(),
    incomingIsView: Boolean = false,
    onIncomingHandled: () -> Unit = {},
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBottomBar = route in setOf(Routes.HOME, Routes.FILES, Routes.SETTINGS, Routes.CATEGORY)
    val c = ForgeTheme.colors
    val snackbar = remember { SnackbarHostState() }
    val recentsVm: RecentsViewModel = viewModel()
    val recents by recentsVm.items.collectAsStateWithLifecycle()
    LaunchedEffect(recentsVm) { recentsVm.messages.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    val recentActions = remember(recentsVm) { com.namanbansal.forge.ui.screens.RecentActions(onRename = { e, n -> recentsVm.rename(e, n) }, onDelete = { e -> recentsVm.deleteFile(e) }) }
    val togglePin: (String) -> Unit = { code ->
        scope.launch {
            if (app.settings.togglePin(code) == SettingsRepository.PinResult.LimitReached) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("You can pin up to ${ToolRegistry.MAX_PINS} tools. Unpin one first.")
            }
        }
    }
    /** Open a tool with files already loaded (share sheet, "Next" chips). */
    val openToolWith: (String, List<PickedFile>) -> Unit = { code, files ->
        app.pendingFiles.put(code, files)
        nav.navigate(Routes.tool(code))
    }

    CompositionLocalProvider(LocalForgeSnackbar provides snackbar) {
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
            NavHost(navController = nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    LaunchedEffect(Unit) { recentsVm.refresh() }
                    HomeScreen(
                        pins = settings.pins,
                        recents = recents,
                        onReorderPins = { order -> scope.launch { app.settings.setPins(order) } },
                        onSeeAllRecents = { nav.navigateTab(Routes.FILES) },
                        onRemoveRecent = recentsVm::remove,
                        recentActions = recentActions,
                        onSearch = { nav.navigate(Routes.SEARCH) },
                        onSettings = { nav.navigateTab(Routes.SETTINGS) },
                        onTool = { nav.navigate(Routes.tool(it)) },
                        onCategory = { nav.navigate(Routes.category(it)) },
                        onTogglePin = togglePin,
                    )
                }
                composable(Routes.FILES) { FilesScreen(recents, onRefresh = recentsVm::refresh, onRemove = recentsVm::remove, actions = recentActions) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(app = app, settings = settings, onLicenses = { nav.navigate(Routes.LICENSES) })
                }
                composable(Routes.LICENSES) { LicensesScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        recentSearches = settings.recentSearches,
                        onBack = { nav.popBackStack() },
                        onTool = { code, query ->
                            scope.launch { app.settings.addRecentSearch(query) }
                            nav.navigate(Routes.tool(code))
                        },
                        onSubmit = { q -> scope.launch { app.settings.addRecentSearch(q) } },
                        onClearRecent = { scope.launch { app.settings.clearRecentSearches() } },
                        onTogglePin = togglePin,
                    )
                }
                composable(Routes.CATEGORY, arguments = listOf(navArgument("name") { type = NavType.StringType })) { e ->
                    val cat = runCatching { Category.valueOf(e.arguments?.getString("name").orEmpty()) }.getOrDefault(Category.PDF)
                    CategoryScreen(
                        category = cat,
                        pins = settings.pins,
                        onBack = { nav.popBackStack() },
                        onTool = { nav.navigate(Routes.tool(it)) },
                        onTogglePin = togglePin,
                    )
                }
                composable(Routes.TOOL, arguments = listOf(navArgument("code") { type = NavType.StringType })) { e ->
                    val tool = ToolRegistry.get(e.arguments?.getString("code").orEmpty())
                    val onBack: () -> Unit = { nav.popBackStack() }
                    val screen = tool?.takeIf { it.status == ToolStatus.Available }?.let { ToolScreens.screens[it.code] }
                    when {
                        tool == null -> ComingSoonScreen(tool = null, pinned = false, onBack = onBack, onTogglePin = {})
                        screen != null -> screen(ToolHost(tool, onBack, openToolWith))
                        else -> ComingSoonScreen(tool, pinned = tool.code in settings.pins, onBack = onBack, onTogglePin = { togglePin(tool.code) })
                    }
                }
            }
        }
        ForgeSnackbarHost(snackbar)
        if (showBottomBar) BottomBar(currentRoute = route, onSelect = { nav.navigateTab(it) })
    }
    }

    // "Open with" on a single PDF goes straight to the viewer; anything else asks what to do.
    val direct = incomingIsView && incoming.size == 1 && incoming.first().kind == com.namanbansal.forge.core.FileKind.PDF
    LaunchedEffect(incoming, direct) {
        if (direct) {
            onIncomingHandled()
            openToolWith("P40", incoming)
        }
    }
    if (incoming.isNotEmpty() && !direct) {
        OpenWithSheet(
            files = incoming,
            onDismiss = onIncomingHandled,
            onSaveToPhone = {
                val files = incoming
                onIncomingHandled()
                scope.launch {
                    val saved = files.count { f -> runCatching { app.outputSaver.saveFromUri(f.uri, f.name, f.mime ?: "application/octet-stream") }.isSuccess }
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(if (saved == files.size) "Saved to Downloads/Forge" else if (saved == 0) "Couldn't save to your phone." else "Saved $saved of ${files.size} files to Downloads/Forge")
                }
            },
            onPick = { tool ->
                onIncomingHandled()
                openToolWith(tool.code, incoming)
            },
        )
    }

    UpdateSheet(app.updates)
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    val c = ForgeTheme.colors
    val selected = when (currentRoute) {
        Routes.FILES -> Routes.FILES
        Routes.SETTINGS -> Routes.SETTINGS
        else -> Routes.HOME
    }
    Column(Modifier.fillMaxWidth().background(c.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(thickness = 1.dp, color = c.border)
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            navItems.forEach { item ->
                val on = item.route == selected
                val tint = if (on) c.accent else c.text2
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { onSelect(item.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))
                    Text(item.label, style = ForgeType.Tiny.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = tint)
                }
            }
        }
    }
}
