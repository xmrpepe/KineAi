package com.yugahashimoto.andcode.runtime

import com.yugahashimoto.andcode.R

/** The only on-device "agent": the model Kinetic runs on this phone. */
enum class LocalAgent(
    val id: String,
    val displayNameRes: Int,
    val targetId: String,
    val iconRes: Int,
) {
    KINETIC("kinetic", R.string.agent_opencode_name, "kinetic-local", R.drawable.ic_agent_opencode),
    ;

    companion object {
        fun fromId(id: String): LocalAgent? = entries.firstOrNull { it.id == id }
    }
}
