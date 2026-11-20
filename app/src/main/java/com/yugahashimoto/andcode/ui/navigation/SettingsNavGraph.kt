package com.yugahashimoto.andcode.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.kineai.local.ui.DiagnosticsScreen
import com.kineai.local.ui.ModelsScreen
import com.kineai.local.ui.SettingsScreen
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.data.settings.AppPreferences
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import com.yugahashimoto.andcode.feature.settings.ModelVisibilityScreen
import com.yugahashimoto.andcode.feature.settings.SettingsScreenV2
import com.yugahashimoto.andcode.feature.settings.SettingsViewModel

/** A page from the local-model layer, framed like AndCode's own screens (top bar with the drawer button). */
@Composable
private fun LocalPage(title: String, onOpenDrawer: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, "Menu") } },
        )
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

fun NavGraphBuilder.settingsNavGraph(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
    // Getters: NavHost remembers these lambdas, so a captured value would be frozen.
    notificationsEnabled: () -> Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    appVersion: String,
    onOpenDrawer: () -> Unit,
    preferences: () -> AppPreferences,
    appPreferences: AppPreferencesRepository,
    app: AndCodeApplication,
    context: Context,
) {
    composable(ROUTE_SETTINGS) {
        SettingsScreenV2(
            notificationsEnabled = notificationsEnabled(),
            onToggleNotifications = onToggleNotifications,
            appVersion = appVersion,
            onOpenDrawer = onOpenDrawer,
            onOpenModels = { navController.navigate(ROUTE_MODELS) },
            onOpenLocalModelSettings = { navController.navigate(ROUTE_LOCAL_SETTINGS) },
            onOpenWorkspaces = { navController.navigate(ROUTE_WORKSPACES) },
            onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
            onOpenLegal = { navController.navigate(ROUTE_SETTINGS_LEGAL) },
            onOpenModelVisibility = { navController.navigate(ROUTE_SETTINGS_MODEL_VISIBILITY) },
            currentTheme = preferences().theme,
            onThemeChange = { appPreferences.setTheme(it) },
            currentLanguage = preferences().language,
            onLanguageChange = { language ->
                appPreferences.setLanguage(language)
                (context as? android.app.Activity)?.recreate()
            },
            uiFontSize = preferences().uiFontSize,
            onUiFontSizeChange = { appPreferences.setUiFontSize(it) },
            codeFontSize = preferences().codeFontSize,
            onCodeFontSizeChange = { appPreferences.setCodeFontSize(it) },
            syntaxTheme = preferences().syntaxTheme,
            onSyntaxThemeChange = { appPreferences.setSyntaxTheme(it) },
            toolCallDetailLevel = preferences().toolCallDetailLevel,
            onToolCallDetailLevelChange = { appPreferences.setToolCallDetailLevel(it) },
            autoExpandReasoning = preferences().autoExpandReasoning,
            onAutoExpandReasoningChange = { appPreferences.setAutoExpandReasoning(it) },
            sendBehavior = preferences().sendBehavior,
            onSendBehaviorChange = { appPreferences.setSendBehavior(it) },
            enterToSend = preferences().enterToSend,
            onEnterToSendChange = { appPreferences.setEnterToSend(it) },
            autoArchiveStaleEnabled = preferences().autoArchiveStaleEnabled,
            onAutoArchiveStaleEnabledChange = appPreferences::setAutoArchiveStaleEnabled,
            autoArchiveStaleDays = preferences().autoArchiveStaleDays,
            onAutoArchiveStaleDaysChange = appPreferences::setAutoArchiveStaleDays,
            autoArchiveMaxSessionsEnabled = preferences().autoArchiveMaxSessionsEnabled,
            onAutoArchiveMaxSessionsEnabledChange = appPreferences::setAutoArchiveMaxSessionsEnabled,
            autoArchiveMaxSessions = preferences().autoArchiveMaxSessions,
            onAutoArchiveMaxSessionsChange = appPreferences::setAutoArchiveMaxSessions,
        )
    }

    // The model library (Hugging Face search and downloads, installed models), diagnostics and the
    // engine's own settings (context, threads, GPU/CPU). Same screens as before, framed like AndCode's.
    composable(ROUTE_MODELS) { LocalPage("Models", onOpenDrawer) { ModelsScreen(app) } }
    composable(ROUTE_DIAGNOSTICS) { LocalPage("Diagnostics", onOpenDrawer) { DiagnosticsScreen(app) } }
    composable(ROUTE_LOCAL_SETTINGS) { LocalPage("Local model", onOpenDrawer) { SettingsScreen(app) } }

    composable(ROUTE_SETTINGS_MODEL_VISIBILITY) {
        val settingsState by settingsViewModel.state.collectAsState()
        ModelVisibilityScreen(
            providers = settingsState.availableProviders.filter { it.id in settingsState.connectedProviderIds },
            hiddenModelKeys = settingsState.hiddenModelKeys,
            onToggleModelVisibility = settingsViewModel::toggleModelVisibility,
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_LEGAL) {
        com.yugahashimoto.andcode.feature.settings.LegalScreen(
            onOpenDocument = { document -> navController.navigate(settingsLegalDocumentRoute(document.name)) },
            onBack = { navController.popBackStack() },
        )
    }

    composable(SETTINGS_LEGAL_DOCUMENT_ROUTE_PATTERN) { backStackEntry ->
        val docId = backStackEntry.arguments?.getString(SETTINGS_LEGAL_DOCUMENT_ARG_ID)
        val document = com.yugahashimoto.andcode.feature.settings.LegalDocument.fromId(docId)
        if (document != null) {
            com.yugahashimoto.andcode.feature.settings.LegalDocumentScreen(
                document = document,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
