package com.yugahashimoto.andcode.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.selectablePrimaryAgents
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the chat's model picker and the settings screens read: the local models, favourites and preferences. */
data class SettingsUiState(
    val providers: List<OpenCodeProvider> = emptyList(),
    val availableProviders: List<OpenCodeProvider> = emptyList(),
    val connectedProviderIds: Set<String> = emptySet(),
    val agents: List<OpenCodeAgent> = emptyList(),
    val agentsLoading: Boolean = false,
    val providerId: String? = null,
    val modelId: String? = null,
    val agentId: String? = null,
    val autoAcceptPermissions: Boolean = false,
    val favoriteModelKeys: Set<String> = emptySet(),
    val recentModelKeys: List<String> = emptyList(),
    val hiddenModelKeys: Set<String> = emptySet(),
    val runtimeVersion: String? = null,
)

class SettingsViewModel(
    private val catalog: RuntimeCatalogRepository,
    private val preferences: AppPreferencesRepository,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> =
        combine(catalog.state, preferences.state) { runtime, prefs ->
            val providers: ProviderCatalog = runtime.providers
            SettingsUiState(
                providers = providers.all.filter { it.id in providers.connected },
                availableProviders = providers.all,
                connectedProviderIds = providers.connected.toSet(),
                agents = runtime.agents.selectablePrimaryAgents(),
                agentsLoading = runtime.agentsLoading,
                providerId = prefs.providerId,
                modelId = prefs.modelId,
                agentId = prefs.agentId,
                autoAcceptPermissions = prefs.autoAcceptPermissions,
                favoriteModelKeys = prefs.favoriteModelKeys,
                recentModelKeys = prefs.recentModelKeys,
                hiddenModelKeys = prefs.hiddenModelKeys,
                runtimeVersion = runtime.health?.version,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun selectModel(providerId: String, modelId: String) = preferences.selectModel(providerId, modelId)

    fun selectAgent(agentId: String) = preferences.selectAgent(agentId)

    fun setAutoAcceptPermissions(enabled: Boolean) = preferences.setAutoAcceptPermissions(enabled)

    fun toggleFavoriteModel(providerId: String, modelId: String) = preferences.toggleFavoriteModel(providerId, modelId)

    fun toggleModelVisibility(providerId: String, modelId: String) = preferences.toggleModelVisibility(providerId, modelId)
}
