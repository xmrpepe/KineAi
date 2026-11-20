package com.yugahashimoto.andcode.feature.workspace

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kineai.local.KineApp
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class WorkspaceUiState(
    val workspaces: List<WorkspaceRef> = emptyList(),
    val busy: String? = null,
    val error: String? = null,
)

/** Projects are plain folders in app storage, separate from models and chats. */
class WorkspaceViewModel(private val app: KineApp) : ViewModel() {
    private val mutable = MutableStateFlow(WorkspaceUiState(workspaces = load()))
    val state: StateFlow<WorkspaceUiState> = mutable.asStateFlow()

    private fun load(): List<WorkspaceRef> = app.projects.list().map { WorkspaceRef(it.absolutePath, it.name, it.absolutePath) }

    fun refresh() = mutable.update { it.copy(workspaces = load()) }

    fun createProject(name: String): WorkspaceRef? {
        val dir = app.projects.create(name)
        refresh()
        return WorkspaceRef(dir.absolutePath, dir.name, dir.absolutePath)
    }

    /** Deletes one project folder only; models and chats are untouched. */
    fun deleteProject(path: String) {
        runCatching { app.projects.delete(File(path)) }.onFailure { e -> mutable.update { it.copy(error = e.message) } }
        refresh()
    }

    fun importFolder(context: Context, uri: Uri) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = "Importing…", error = null) }
            runCatching { app.projects.importTree(context.contentResolver, context, uri) { f -> mutable.update { s -> s.copy(busy = "Copying $f") } } }
                .onFailure { e -> mutable.update { it.copy(error = e.message) } }
            mutable.update { it.copy(busy = null) }
            refresh()
        }
    }

    /** User-requested clone over the network; the only Git action that uses it. */
    fun clone(url: String) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = "Cloning…", error = null) }
            withContext(Dispatchers.IO) {
                runCatching { app.git.clone(url, app.projects.create(url.trimEnd('/').substringAfterLast('/').removeSuffix(".git"))) }
            }.onFailure { e -> mutable.update { it.copy(error = "Clone failed: ${e.message}") } }
            mutable.update { it.copy(busy = null) }
            refresh()
        }
    }
}
