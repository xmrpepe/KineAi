package com.kineai.local.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import com.kineai.local.KineApp

private enum class Dest(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Filled.Chat), WORKSPACE("Workspace", Icons.Filled.Folder), MODELS("Models", Icons.Filled.Memory),
    DIAGNOSTICS("Diagnostics", Icons.Filled.Speed), SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun AppRoot(app: KineApp) {
    val settings by app.settings.flow.collectAsState()
    var onboarded by remember { mutableStateOf(settings.onboarded) }
    if (!onboarded) { OnboardingScreen(app) { onboarded = true }; return }

    var dest by rememberSaveable { mutableStateOf(Dest.CHAT) }
    // Wide (landscape) layouts: chat and workspace side by side.
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val content: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            when (dest) {
                Dest.CHAT -> if (wide) Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { ChatScreen(app) { dest = Dest.MODELS } }
                    Box(Modifier.weight(1f).fillMaxHeight()) { WorkspaceScreen(app) }
                } else ChatScreen(app) { dest = Dest.MODELS }
                Dest.WORKSPACE -> WorkspaceScreen(app)
                Dest.MODELS -> ModelsScreen(app)
                Dest.DIAGNOSTICS -> DiagnosticsScreen(app)
                Dest.SETTINGS -> SettingsScreen(app)
            }
        }
    }
    if (LocalConfiguration.current.screenWidthDp >= 600) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail { Dest.entries.forEach { d -> NavigationRailItem(dest == d, { dest = d }, { Icon(d.icon, d.label) }, label = { Text(d.label) }) } }
            content(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Scaffold(bottomBar = { NavigationBar { Dest.entries.forEach { d -> NavigationBarItem(dest == d, { dest = d }, { Icon(d.icon, d.label) }, label = { Text(d.label) }) } } }) { pad -> content(Modifier.padding(pad).fillMaxSize()) }
    }
}
