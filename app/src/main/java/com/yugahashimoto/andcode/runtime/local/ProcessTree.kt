package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Kills a whole process tree when the JVM only holds a handle to its root.
 *
 * `Process.destroy()`/`destroyForcibly()` signals a single OS process - the native PRoot binary.
 * Confirmed on a real device: killing that process, even with SIGKILL sent directly (not through the
 * JVM), left the guest processes it had exec'd into (`script`, `agy`) alive and reparented to init,
 * indefinitely - six such orphaned sign-in sessions accumulated over repeated attempts and kept
 * contending with every new one. PRoot's `--kill-on-exit` only cleans up when PRoot itself decides to
 * exit on its own terms; it is not a reaction to being killed from outside.
 *
 * Not specific to Antigravity: anything launched as a PRoot guest process needs this to be stopped,
 * the interactive terminal's shell (and the dev servers it started) included.
 *
 * [processId], [processTreePostOrder] and [readDirectChildPids] already exist in this package for
 * `LocalRuntimeProcessLauncher`'s equivalent OpenCode/Claude cleanup; this reuses them rather than
 * re-walking `/proc` a second way.
 */
internal fun killProcessTree(process: Process) {
    val rootPid = processId(process) ?: return
    processTreePostOrder(rootPid) { pid -> readDirectChildPids(pid) }
        .forEach { pid -> runCatching { android.os.Process.killProcess(pid.toInt()) } }
}

internal fun readDirectChildPids(
    parentPid: Long,
    procRoot: File = File("/proc"),
): List<Long> {
    val standardChildren = File(procRoot, "$parentPid/task/$parentPid/children")
    if (standardChildren.isFile) {
        return runCatching {
            standardChildren.readText()
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull(String::toLongOrNull)
                .sorted()
        }.getOrDefault(emptyList())
    }

    return procRoot.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            val pid = directory.name.toLongOrNull() ?: return@mapNotNull null
            val statusText =
                runCatching {
                    File(directory, "status").readText()
                }.getOrNull() ?: return@mapNotNull null
            pid.takeIf { parseParentProcessId(statusText) == parentPid }
        }
        .sorted()
        .toList()
}

internal fun parseParentProcessId(statusText: String): Long? =
    statusText.lineSequence()
        .firstOrNull { it.startsWith("PPid:") }
        ?.substringAfter("PPid:")
        ?.trim()
        ?.substringBefore(' ')
        ?.toLongOrNull()

internal fun processTreePostOrder(
    rootPid: Long,
    childrenReader: (Long) -> List<Long>,
): List<Long> {
    val visited = mutableSetOf<Long>()
    val result = mutableListOf<Long>()

    fun visit(pid: Long) {
        if (!visited.add(pid)) return
        childrenReader(pid).forEach(::visit)
        result += pid
    }

    visit(rootPid)
    return result
}

internal fun processId(process: Process): Long? {
    val methodValue =
        runCatching {
            process.javaClass.methods
                .firstOrNull { it.name == "pid" && it.parameterCount == 0 }
                ?.invoke(process)
        }.getOrNull()
    when (methodValue) {
        is Long -> return methodValue
        is Int -> return methodValue.toLong()
    }

    return sequenceOf("pid", "id")
        .mapNotNull { fieldName ->
            runCatching {
                process.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(process)
            }.getOrNull()
        }
        .mapNotNull { value ->
            when (value) {
                is Long -> value
                is Int -> value.toLong()
                else -> null
            }
        }
        .firstOrNull()
}

