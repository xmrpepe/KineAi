package com.yugahashimoto.andcode.ui.navigation

import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.feature.workspace.AndroidCodeViewerMessages
import com.yugahashimoto.andcode.feature.workspace.CodeViewerScreen
import com.yugahashimoto.andcode.feature.workspace.CodeViewerViewModel
import com.yugahashimoto.andcode.feature.workspace.isOpenable
import com.yugahashimoto.andcode.core.UrlLauncher
import com.yugahashimoto.andcode.feature.workspace.TerminalScreen
import com.yugahashimoto.andcode.feature.workspace.TerminalViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceExplorerScreen
import com.yugahashimoto.andcode.feature.workspace.WorkspaceExplorerViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspacesScreen
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.ViewModelFactory

fun NavGraphBuilder.workspaceNavGraph(
    navController: NavController,
    workspaceViewModel: WorkspaceViewModel,
    // Getters, not values: NavHost remembers these lambdas, so a captured value would stay stale.
    selectedWorkspace: () -> WorkspaceRef?,
    onSelectWorkspace: (WorkspaceRef?) -> Unit,
    selectedRuntime: () -> RuntimeTarget?,
    app: AndCodeApplication,
    context: Context,
) {
    composable(ROUTE_WORKSPACES) {
        val workspaceState by workspaceViewModel.state.collectAsState()
        WorkspacesScreen(
            state = workspaceState,
            onOpenWorkspace = { workspace ->
                onSelectWorkspace(workspace)
                navController.navigate(WORKSPACE_DETAIL_ROUTE)
            },
            onCreate = { name -> workspaceViewModel.createProject(name) },
            onImportFolder = { uri -> workspaceViewModel.importFolder(context, uri) },
            onClone = workspaceViewModel::clone,
            onDelete = workspaceViewModel::deleteProject,
            onBack = { navController.popBackStack() },
        )
    }

    composable(WORKSPACE_DETAIL_ROUTE) {
        val workspace = selectedWorkspace()
        val runtime = selectedRuntime()
        if (workspace == null || runtime == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val explorerViewModel: WorkspaceExplorerViewModel =
                viewModel(
                    key = "workspace-explorer-${runtime.id}-${workspace.id}",
                    factory = ViewModelFactory { WorkspaceExplorerViewModel(runtime, workspace) },
                )
            val explorerState by explorerViewModel.state.collectAsState()
            WorkspaceExplorerScreen(
                state = explorerState,
                onBack = { navController.popBackStack() },
                onRefresh = explorerViewModel::refresh,
                onOpenNode = { node ->
                    if (node.type == "directory") {
                        explorerViewModel.open(node)
                    } else {
                        navController.navigate(codeViewerRoute(runtime.id, explorerState.currentPath, node.path))
                    }
                },
                onNavigateUp = explorerViewModel::navigateUp,
                onSearch = explorerViewModel::search,
                onRefreshChanges = explorerViewModel::refreshChanges,
                onOpenTerminal = { navController.navigate(terminalRoute(workspace.path)) },
                onOpenChange = { change ->
                    if (change.isOpenable()) navController.navigate(codeViewerRoute(runtime.id, workspace.path, change.displayPath))
                },
            )
        }
    }

    composable(TERMINAL_ROUTE_PATTERN) { backStack ->
        val directory = backStack.arguments?.getString(TERMINAL_ARG_DIRECTORY)?.let { decodeRouteArg(it) }
        val terminalViewModel: TerminalViewModel =
            viewModel(
                key = "terminal",
                factory = ViewModelFactory { TerminalViewModel(app.terminalSessions, app.runtimeWork, directory) },
            )
        val terminalState by terminalViewModel.state.collectAsState()
        TerminalScreen(
            state = terminalState,
            onOpen = terminalViewModel::open,
            onInputChange = terminalViewModel::updateInput,
            onSubmit = terminalViewModel::submit,
            onKey = terminalViewModel::sendKey,
            onRestart = terminalViewModel::restart,
            onClear = terminalViewModel::clear,
            onOpenServer = { url -> UrlLauncher.openUrl(context, url) },
        )
    }

    composable(CODE_VIEWER_ROUTE_PATTERN) { backStack ->
        val arguments =
            runCatching {
                Triple(
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("runtimeId"))),
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("workspacePath"))),
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("filePath"))),
                )
            }.getOrNull()
        val runtime = arguments?.first?.let { app.runtimeRegistry.target(it) }
        if (arguments == null || runtime == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val viewerViewModel: CodeViewerViewModel =
                viewModel(
                    key = "code-viewer-${backStack.id}",
                    factory =
                        ViewModelFactory {
                            CodeViewerViewModel(
                                backend = runtime,
                                workspacePath = arguments.second,
                                filePath = arguments.third,
                                editableRoot = if (runtime.capabilities.fileEditing) selectedWorkspace()?.path else null,
                                messages = AndroidCodeViewerMessages(app),
                            )
                        },
                )
            val viewerState by viewerViewModel.state.collectAsState()
            val preferences by app.preferences.state.collectAsState()
            CodeViewerScreen(
                state = viewerState,
                syntaxThemeKey = preferences.syntaxTheme,
                onBack = { navController.popBackStack() },
                onStartEditing = viewerViewModel::startEditing,
                onDraftChange = viewerViewModel::updateDraft,
                onSave = viewerViewModel::save,
                onStopEditing = viewerViewModel::stopEditing,
            )
        }
    }
}
