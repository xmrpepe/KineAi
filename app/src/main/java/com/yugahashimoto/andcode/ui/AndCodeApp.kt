package com.yugahashimoto.andcode.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.diagnostics.CrashLog
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.data.settings.DraftRepository
import com.yugahashimoto.andcode.feature.activity.ActivityViewModel
import com.yugahashimoto.andcode.feature.chat.ChatHomeScreen
import com.yugahashimoto.andcode.feature.chat.ChatViewModel
import com.yugahashimoto.andcode.feature.chat.LocalAttachments
import com.yugahashimoto.andcode.feature.settings.SettingsViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.components.SessionStatus
import com.yugahashimoto.andcode.ui.navigation.DRAWER_ROOT_ROUTES
import com.yugahashimoto.andcode.ui.navigation.ROUTE_CHAT
import com.yugahashimoto.andcode.ui.navigation.ROUTE_MODELS
import com.yugahashimoto.andcode.ui.navigation.ROUTE_ONBOARDING
import com.yugahashimoto.andcode.ui.navigation.ROUTE_WORKSPACES
import com.yugahashimoto.andcode.ui.navigation.settingsNavGraph
import com.yugahashimoto.andcode.ui.navigation.workspaceNavGraph
import com.yugahashimoto.andcode.ui.theme.AppTheme
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun relativeTimeLabel(
    context: android.content.Context,
    epochMillis: Long,
): String {
    val diffMillis = (System.currentTimeMillis() - epochMillis).coerceAtLeast(0L)
    val minutes = diffMillis / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> context.getString(R.string.drawer_time_just_now)
        hours < 1L -> context.getString(R.string.drawer_time_minutes_ago, minutes)
        days < 1L -> context.getString(R.string.drawer_time_hours_ago, hours)
        else -> context.getString(R.string.drawer_time_days_ago, days)
    }
}


@Composable
fun AndCodeApp(
    chatDeepLink: ChatDeepLink? = null,
    onChatDeepLinkConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val app = context.applicationContext as AndCodeApplication
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    var pendingSession by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedWorkspace by remember { mutableStateOf<WorkspaceRef?>(null) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var collapsedSections by remember { mutableStateOf(setOf<String>()) }

    val selectedRuntime by app.runtimeRegistry.selected.collectAsState()
    val runtimeTargets by app.runtimeRegistry.targets.collectAsState()
    val preferences by app.preferences.state.collectAsState()

    val workspaceViewModel: WorkspaceViewModel =
        viewModel(key = "workspaces", factory = ViewModelFactory { WorkspaceViewModel(app) })
    val workspaceState by workspaceViewModel.state.collectAsState()

    val activityViewModel: ActivityViewModel =
        viewModel(key = "activity", factory = ViewModelFactory { ActivityViewModel(catalog = app.catalogRepository, activity = app.activityRepository) })
    val activityState by activityViewModel.state.collectAsState()

    val settingsViewModel: SettingsViewModel =
        viewModel(key = "settings", factory = ViewModelFactory { SettingsViewModel(app.catalogRepository, app.preferences) })
    val settingsState by settingsViewModel.state.collectAsState()

    // A crash the user hit while away from a computer is only recoverable from here.
    var lastCrash by remember { mutableStateOf(CrashLog.read(context)) }
    lastCrash?.let { report ->
        CrashReportDialog(report = report, onCopied = { CrashLog.clear(context); lastCrash = null }, onDismiss = { lastCrash = null })
    }

    val visibleChatSessionId = remember { mutableStateOf<String?>(null) }

    val chatViewModel: ChatViewModel =
        viewModel(
            key = "chat-${selectedRuntime?.id ?: "none"}",
            factory =
                ViewModelFactory {
                    ChatViewModel(
                        backend = selectedRuntime,
                        eventFlow = app.activityRepository.events,
                        onPermissionResolved = app.activityRepository::resolvePermission,
                        onQuestionResolved = app.notifications::cancelQuestion,
                        onSessionCreated = app.catalogRepository::refreshSessionsOnly,
                        onSessionAborted = { sessionId ->
                            app.activityRepository.markSessionAborted(sessionId)
                            app.notifications.cancelStalled(sessionId)
                        },
                        onRunStateChanged = { sessionId, running ->
                            if (running) {
                                app.activityRepository.markSessionRunning(sessionId)
                            } else {
                                app.activityRepository.markSessionFinished(sessionId = sessionId, unread = visibleChatSessionId.value != sessionId)
                            }
                        },
                        draftRepo = DraftRepository(context.applicationContext),
                        // A local model has no network connection to probe, and a long prompt pass is
                        // not a stall, so both watchdogs stay off.
                        monitorConnectionQuality = false,
                        resolvedPermissionFlow = app.activityRepository.resolvedPermissions,
                        monitorStalls = false,
                        streamErrorFlow = app.activityRepository.state.map { it.streamError }.distinctUntilChanged(),
                        awaitForeground = { app.appForeground.foreground.first { visible -> visible } },
                        videoNotSupportedMessage = context.getString(R.string.error_video_not_supported),
                        workspaceAttachmentNoteTemplate = context.getString(R.string.chat_workspace_attachment_note),
                    )
                },
        )
    val chatState by chatViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    val attachmentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { LocalAttachments.import(context, uri) } }
                    .onSuccess { attachment: PromptAttachment -> chatViewModel.addAttachment(attachment) }
                    .onFailure { error ->
                        android.widget.Toast.makeText(context, error.message ?: context.getString(R.string.attachment_load_failed), android.widget.Toast.LENGTH_LONG).show()
                    }
            }
        }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Open a chat the drawer or a notification asked for, once the runtime exists.
    LaunchedEffect(pendingSession, selectedRuntime?.id) {
        val pending = pendingSession ?: return@LaunchedEffect
        if (selectedRuntime == null) return@LaunchedEffect
        chatViewModel.openSession(pending.first, pending.second)
        pendingSession = null
    }

    // The model the chat will use: validated against the local model list, with the first model as the fallback.
    LaunchedEffect(preferences.providerId, preferences.modelId, preferences.agentId, settingsState.providers, settingsState.agents) {
        val model = settingsState.providers.firstOrNull { it.id == preferences.providerId }?.models?.get(preferences.modelId)
        chatViewModel.selectConfiguration(
            preferences.providerId?.takeIf { model != null },
            preferences.modelId?.takeIf { model != null },
            preferences.agentId?.takeIf { id -> settingsState.agents.any { it.name == id } },
            model?.limit?.context ?: 0L,
        )
    }
    val activeProvider = settingsState.providers.firstOrNull { it.id == preferences.providerId }
    val fallbackProviderId = preferences.providerId?.takeIf { activeProvider != null } ?: settingsState.providers.firstOrNull()?.id
    val fallbackModelId =
        preferences.modelId?.takeIf { activeProvider?.models?.containsKey(it) == true }
            ?: settingsState.providers.firstOrNull { it.id == fallbackProviderId }?.models?.keys?.firstOrNull()

    LaunchedEffect(preferences.autoAcceptPermissions) { chatViewModel.setAutoAcceptPermissions(false) }

    LaunchedEffect(selectedRuntime?.id, workspaceState.workspaces, chatState.sessionId) {
        if (chatState.sessionId != null) return@LaunchedEffect
        if (chatState.selectedWorkspacePath == null && workspaceState.workspaces.isNotEmpty()) {
            chatViewModel.selectWorkspace(workspaceState.workspaces.first().path)
        }
    }

    // A notification tap opens its chat.
    LaunchedEffect(chatDeepLink) {
        val link = chatDeepLink ?: return@LaunchedEffect
        onChatDeepLinkConsumed()
        val knownSession = app.catalogRepository.allSessions.value.firstOrNull { it.session.id == link.sessionId }
        app.activityRepository.markSessionRead(link.sessionId)
        pendingSession = link.sessionId to (knownSession?.session?.title?.takeIf(String::isNotBlank) ?: link.sessionId)
        navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
    }

    val startDestination = remember { if (app.secureSettings.onboardingCompleted || app.settings.value.onboarded) ROUTE_CHAT else ROUTE_ONBOARDING }

    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    val allSessions by app.catalogRepository.allSessions.collectAsState()
    val recentSessions =
        remember(allSessions, activityState.activeSessionIds, activityState.completedSessionIds) {
            allSessions.filter { it.session.parentId == null }.take(25).map { ref ->
                val session = ref.session
                DrawerRecentSession(
                    id = session.id,
                    title = session.title.ifBlank { session.slug ?: session.id },
                    relativeTime = relativeTimeLabel(context, session.time.updated ?: session.time.created),
                    directory = session.directory,
                    isActive = session.id in activityState.activeSessionIds,
                    hasUnread = session.id in activityState.completedSessionIds,
                    status =
                        when {
                            session.id in activityState.activeSessionIds -> SessionStatus.RUNNING
                            session.id in activityState.completedSessionIds -> SessionStatus.COMPLETED_UNREAD
                            else -> SessionStatus.IDLE
                        },
                    runtimeId = ref.runtimeId,
                    agent = ref.agent,
                )
            }
        }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(currentRoute, chatState.sessionId) {
        visibleChatSessionId.value = chatState.sessionId?.takeIf { currentRoute == ROUTE_CHAT }
    }
    LaunchedEffect(currentRoute, chatState.sessionId, chatState.isRunning) {
        val sessionId = chatState.sessionId
        if (currentRoute == ROUTE_CHAT && sessionId != null && !chatState.isRunning) app.activityRepository.markSessionRead(sessionId)
    }

    fun openDrawer() {
        keyboardController?.hide()
        scope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(drawerState.isOpen) { if (drawerState.isOpen) keyboardController?.hide() }

    val drawerGesturesEnabled = currentRoute in DRAWER_ROOT_ROUTES

    AndCodeTheme(appTheme = AppTheme.fromKey(preferences.theme), uiFontSize = preferences.uiFontSize) {
        ModalNavigationDrawer(
            modifier = if (drawerGesturesEnabled) Modifier.onlyAllowDrawerEdgeSwipe(drawerIsOpen = drawerState.isOpen) else Modifier,
            drawerState = drawerState,
            gesturesEnabled = drawerGesturesEnabled,
            drawerContent = {
                ModalDrawerSheet {
                    AppDrawerContent(
                        recentSessions = recentSessions,
                        workspaces = workspaceState.workspaces,
                        selectedWorkspacePath = chatState.selectedWorkspacePath,
                        onNewChat = {
                            closeDrawer()
                            pendingSession = null
                            chatViewModel.newSession()
                            chatViewModel.selectWorkspace(null)
                            navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                        },
                        onSelectProject = { workspace ->
                            closeDrawer()
                            pendingSession = null
                            chatViewModel.newSession()
                            chatViewModel.selectWorkspace(workspace.path)
                            navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                        },
                        onOpenSession = { id, title, _ ->
                            closeDrawer()
                            if (id.isNotBlank()) {
                                app.activityRepository.markSessionRead(id)
                                pendingSession = id to title
                                navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                            }
                        },
                        onNavigate = { route ->
                            closeDrawer()
                            navController.navigate(route) { launchSingleTop = true }
                        },
                        onDeleteSession = { sessionId ->
                            scope.launch {
                                runCatching { app.runtimeRegistry.targets.value.forEach { it.deleteSession(sessionId) } }
                                app.catalogRepository.refreshSessionsOnly()
                            }
                        },
                        onBatchDelete = { ids ->
                            scope.launch {
                                runCatching { ids.forEach { id -> app.runtimeRegistry.targets.value.forEach { it.deleteSession(id) } } }
                                app.catalogRepository.refreshSessionsOnly()
                            }
                        },
                        collapsedSections = collapsedSections,
                        onToggleSection = { section -> collapsedSections = if (section in collapsedSections) collapsedSections - section else collapsedSections + section },
                    )
                }
            },
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                NavHost(navController = navController, startDestination = startDestination) {
                    composable(ROUTE_ONBOARDING) {
                        com.kineai.local.ui.OnboardingScreen(app) {
                            app.secureSettings.onboardingCompleted = true
                            navController.navigate(ROUTE_CHAT) { popUpTo(ROUTE_ONBOARDING) { inclusive = true } }
                        }
                    }

                    composable(ROUTE_CHAT) {
                        ChatHomeScreen(
                            state = chatState,
                            providers = settingsState.providers,
                            agents = settingsState.agents,
                            agentsLoading = settingsState.agentsLoading,
                            selectedProviderId = chatState.selectedProviderId ?: fallbackProviderId,
                            selectedModelId = chatState.selectedModelId ?: fallbackModelId,
                            selectedAgentId = chatState.selectedAgentId ?: settingsState.agentId,
                            runtimeTargets = runtimeTargets,
                            selectedRuntimeId = selectedRuntime?.id,
                            supportsPermissions = selectedRuntime?.capabilities?.permissions != false,
                            onSelectRuntime = {},
                            onOpenProviderSettings = { navController.navigate(ROUTE_MODELS) },
                            onSelectModel = settingsViewModel::selectModel,
                            onSelectAgent = settingsViewModel::selectAgent,
                            onRefreshAgents = app.catalogRepository::refreshAgents,
                            selectedVariant = chatState.selectedVariant,
                            onSelectVariant = chatViewModel::selectVariant,
                            onAttach = { attachmentLauncher.launch("*/*") },
                            onRemoveAttachment = chatViewModel::removeAttachment,
                            favoriteModelKeys = settingsState.favoriteModelKeys,
                            recentModelKeys = settingsState.recentModelKeys,
                            hiddenModelKeys = settingsState.hiddenModelKeys,
                            onToggleFavorite = settingsViewModel::toggleFavoriteModel,
                            onSelectQuestionAnswer = chatViewModel::selectQuestionAnswer,
                            onSubmitQuestion = chatViewModel::submitQuestion,
                            onCancelQuestion = chatViewModel::cancelQuestion,
                            onDismissQuestion = chatViewModel::dismissQuestion,
                            onDismissTodoBar = chatViewModel::dismissTodoBar,
                            autoAcceptPermissions = false,
                            enterToSend = preferences.enterToSend,
                            autoExpandReasoning = preferences.autoExpandReasoning,
                            onSendMessage = chatViewModel::sendMessage,
                            canEditMessages = selectedRuntime?.capabilities?.editMessages == true,
                            onEditLastMessage = chatViewModel::editLastUserMessage,
                            onEditDraftConsumed = chatViewModel::consumeEditDraft,
                            onPermission = chatViewModel::respondToPermission,
                            onAbort = chatViewModel::abort,
                            onNewChat = {
                                pendingSession = null
                                chatViewModel.newSession()
                            },
                            onOpenLocalSetup = { navController.navigate(ROUTE_MODELS) { launchSingleTop = true } },
                            onRefreshCatalog = app.catalogRepository::refreshProvidersOnly,
                            onOpenDrawer = {
                                app.catalogRepository.refreshSessionsOnly()
                                openDrawer()
                            },
                            onReturnToParentSession = {
                                pendingSession = null
                                chatViewModel.openParentSession()
                            },
                            onOpenPatchDiff = chatViewModel::openPatchDiff,
                            onDismissPatchDiff = chatViewModel::dismissPatchDiff,
                            onLoadDraft = { sessionId -> chatViewModel.loadDraft(sessionId)?.text },
                            onSaveDraft = { sessionId, text -> chatViewModel.saveDraft(sessionId, text) },
                        )
                    }

                    settingsNavGraph(
                        navController = navController,
                        settingsViewModel = settingsViewModel,
                        notificationsEnabled = { notificationsEnabled },
                        onToggleNotifications = { enabled ->
                            notificationsEnabled = enabled
                            if (enabled && android.os.Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        appVersion = appVersion,
                        onOpenDrawer = { openDrawer() },
                        preferences = { preferences },
                        appPreferences = app.preferences,
                        app = app,
                        context = context,
                    )

                    workspaceNavGraph(
                        navController = navController,
                        workspaceViewModel = workspaceViewModel,
                        selectedWorkspace = { selectedWorkspace },
                        onSelectWorkspace = { selectedWorkspace = it },
                        selectedRuntime = { selectedRuntime },
                        app = app,
                        context = context,
                    )
                }
            }
        }
    }
}

/** Shows the stack trace of the crash the app came back from, so it can be reported by hand. */
@Composable
private fun CrashReportDialog(
    report: String,
    onCopied: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_report_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.crash_report_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                onCopied()
            }) {
                Text(stringResource(R.string.crash_report_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_report_dismiss))
            }
        },
    )
}
