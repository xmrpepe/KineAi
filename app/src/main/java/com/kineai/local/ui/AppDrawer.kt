package com.kineai.local.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kineai.local.KineApp

enum class Dest(val title: String) { CHAT("Chat"), WORKSPACE("Workspace"), MODELS("Models"), DIAGNOSTICS("Diagnostics"), SETTINGS("Settings") }

/** The side menu opened by the hamburger, laid out like AndCode's: new chat, chats, projects, then app pages. */
@Composable
fun DrawerContent(app: KineApp, dest: Dest, drawerOpen: Boolean, go: (Dest) -> Unit) {
    val chat by app.chat.ui.collectAsState()
    val project by app.selectedProject.collectAsState()
    var chatsOpen by remember { mutableStateOf(true) }
    var projectsOpen by remember { mutableStateOf(true) }
    // Re-read when the drawer opens or the conversation changes.
    val conversations = remember(drawerOpen, chat.conversation.id, chat.conversation.messages.size, chat.generating) { app.conversations.list().take(20) }
    val projects = remember(drawerOpen, dest, project) { app.projects.list() }
    val colors = NavigationDrawerItemDefaults.colors()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text("KineAi", Modifier.padding(start = 12.dp, top = 20.dp, bottom = 14.dp), style = MaterialTheme.typography.headlineSmall)
            NavigationDrawerItem(
                label = { Text("New chat") }, icon = { Icon(Icons.Filled.Chat, null) }, selected = false,
                onClick = { app.chat.newConversation(); go(Dest.CHAT) }, colors = colors,
            )

            SectionHeader("Chats", chatsOpen) { chatsOpen = !chatsOpen }
            if (chatsOpen) {
                if (conversations.isEmpty()) Text("No saved chats yet", Modifier.padding(start = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                conversations.forEach { c ->
                    NavigationDrawerItem(
                        label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = dest == Dest.CHAT && c.id == chat.conversation.id,
                        onClick = { app.chat.open(c.id); go(Dest.CHAT) },
                        badge = { IconButton(onClick = { app.chat.delete(c.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, "Delete chat", Modifier.size(18.dp)) } },
                        colors = colors,
                    )
                }
            }

            SectionHeader("Projects", projectsOpen) { projectsOpen = !projectsOpen }
            if (projectsOpen) {
                projects.forEach { p ->
                    NavigationDrawerItem(
                        label = { Column { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("/${p.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        icon = { Icon(Icons.Filled.Folder, null) },
                        selected = project?.canonicalPath == p.canonicalPath,
                        onClick = { app.selectedProject.value = p; go(Dest.WORKSPACE) },
                        colors = colors,
                    )
                }
                Row(Modifier.fillMaxWidth().clickable { app.selectedProject.value = null; go(Dest.WORKSPACE) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Add project", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            NavigationDrawerItem(label = { Text("Models") }, icon = { Icon(Icons.Filled.Memory, null) }, selected = dest == Dest.MODELS, onClick = { go(Dest.MODELS) }, colors = colors)
            NavigationDrawerItem(label = { Text("Diagnostics") }, icon = { Icon(Icons.Filled.Speed, null) }, selected = dest == Dest.DIAGNOSTICS, onClick = { go(Dest.DIAGNOSTICS) }, colors = colors)
            NavigationDrawerItem(label = { Text("Settings") }, icon = { Icon(Icons.Filled.Settings, null) }, selected = dest == Dest.SETTINGS, onClick = { go(Dest.SETTINGS) }, colors = colors)
        }
    }
}

@Composable
private fun SectionHeader(title: String, open: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(start = 8.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
