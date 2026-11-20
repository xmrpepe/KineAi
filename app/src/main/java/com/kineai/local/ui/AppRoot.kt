package com.kineai.local.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.kineai.local.KineApp
import kotlinx.coroutines.launch

@Composable
fun AppRoot(app: KineApp) {
    val settings by app.settings.flow.collectAsState()
    var onboarded by remember { mutableStateOf(settings.onboarded) }
    if (!onboarded) { OnboardingScreen(app) { onboarded = true }; return }

    var dest by rememberSaveable { mutableStateOf(Dest.CHAT) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    // Wide (landscape) layouts: chat and workspace side by side.
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(app, dest, drawer.currentValue == DrawerValue.Open) { d -> dest = d; scope.launch { drawer.close() } }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when (dest) {
                Dest.CHAT ->
                    if (wide) Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { ChatScreen(app, onMenu = openMenu) { dest = Dest.MODELS } }
                        Box(Modifier.weight(1f).fillMaxHeight()) { WorkspaceScreen(app) }
                    } else ChatScreen(app, onMenu = openMenu) { dest = Dest.MODELS }
                else -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = openMenu) { Icon(Icons.Filled.Menu, "Menu") }
                        Text(dest.title, style = MaterialTheme.typography.titleLarge)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (dest) {
                            Dest.WORKSPACE -> WorkspaceScreen(app)
                            Dest.MODELS -> ModelsScreen(app)
                            Dest.DIAGNOSTICS -> DiagnosticsScreen(app)
                            else -> SettingsScreen(app)
                        }
                    }
                }
            }
        }
    }
}
