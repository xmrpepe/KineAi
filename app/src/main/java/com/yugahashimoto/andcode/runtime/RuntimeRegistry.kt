package com.yugahashimoto.andcode.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the single on-device runtime. There are no remote runtimes and no other agents. */
class RuntimeRegistry(private val localTarget: RuntimeTarget) {
    private val mutableTargets = MutableStateFlow(listOf(localTarget))
    val targets: StateFlow<List<RuntimeTarget>> = mutableTargets.asStateFlow()
    private val mutableSelected = MutableStateFlow<RuntimeTarget?>(localTarget)
    val selected: StateFlow<RuntimeTarget?> = mutableSelected.asStateFlow()

    fun target(id: String): RuntimeTarget? = localTarget.takeIf { it.id == id }

    fun targetFor(agent: LocalAgent): RuntimeTarget? = localTarget.takeIf { it.agent == agent }

    fun select(id: String?): Boolean = id == null || id == localTarget.id

    fun selectIfUnset(id: String): Boolean = false

    fun refresh() = Unit
}
