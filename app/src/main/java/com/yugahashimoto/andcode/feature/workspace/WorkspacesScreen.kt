package com.yugahashimoto.andcode.feature.workspace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.runtime.WorkspaceRef

/** Projects on this phone: create, import a folder you pick, clone by URL, open, delete. */
@Composable
fun WorkspacesScreen(
    state: WorkspaceUiState,
    onOpenWorkspace: (WorkspaceRef) -> Unit,
    onCreate: (String) -> Unit,
    onImportFolder: (Uri) -> Unit,
    onClone: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var newName by remember { mutableStateOf<String?>(null) }
    var cloneUrl by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<WorkspaceRef?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(onImportFolder) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("Projects", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Projects live in app storage, separate from models and chats. A folder you import is copied; the app only sees the folder you choose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { newName = "" }) { Text("New") }
                    OutlinedButton(onClick = { pick.launch(null) }) { Text("Import folder") }
                    OutlinedButton(onClick = { cloneUrl = "" }) { Text("Clone") }
                }
                state.busy?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
            items(state.workspaces, key = { it.id }) { w ->
                Card(Modifier.fillMaxWidth().clickable { onOpenWorkspace(w) }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(w.name, style = MaterialTheme.typography.titleMedium)
                            Text(w.path.removePrefix(context.filesDir.absolutePath), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { confirmDelete = w }) { Icon(Icons.Filled.Delete, "Delete project") }
                    }
                }
            }
        }
    }
    newName?.let { n -> AlertDialog({ newName = null }, title = { Text("New project") }, text = { OutlinedTextField(n, { newName = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ onCreate(n); newName = null }) { Text("Create") } }, dismissButton = { TextButton({ newName = null }) { Text("Cancel") } }) }
    cloneUrl?.let { u -> AlertDialog({ cloneUrl = null }, title = { Text("Clone a repository") },
        text = { Column { OutlinedTextField(u, { cloneUrl = it }, singleLine = true, label = { Text("https:// URL") }); Text("This is the one Git action that uses the network, and only because you asked.", style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton({ onClone(u); cloneUrl = null }) { Text("Clone") } }, dismissButton = { TextButton({ cloneUrl = null }) { Text("Cancel") } }) }
    confirmDelete?.let { w -> AlertDialog({ confirmDelete = null }, title = { Text("Delete ${w.name}?") }, text = { Text("This deletes the project folder from the phone. Models and chats are not affected.") },
        confirmButton = { TextButton({ onDelete(w.path); confirmDelete = null }) { Text("Delete") } }, dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } }) }
}
