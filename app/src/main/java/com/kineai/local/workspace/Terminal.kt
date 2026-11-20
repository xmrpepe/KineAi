package com.kineai.local.workspace

import com.kineai.local.terminal.AnsiTerminalEmulator
import com.kineai.local.terminal.TerminalSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * A command terminal for a project folder. Each line the user submits runs as `sh -c` in the
 * current directory (with `cd` handled here); nothing runs unless the user types it, and the
 * model can never reach it. There is no PTY: full-screen programs (vim, top) are unsupported,
 * a limitation documented in docs/ANDROID_PORT.md. Ctrl-C ends the running command.
 */
class TerminalSession(private val root: File) {
    private val emulator = AnsiTerminalEmulator(columns = 100, rows = 40, maxLines = 5000)
    private val _snapshot = MutableStateFlow(emulator.snapshot())
    val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private var cwd: File = root
    private var proc: Process? = null

    val prompt get() = "${cwd.relativeToOrSelf(root.parentFile ?: root).path} $ "

    private fun emit(s: String) { emulator.feed(s); _snapshot.value = emulator.snapshot() }

    init { emit("KineAi terminal - runs in ${root.name}. Commands run only when you press enter.\r\n") }

    fun submit(line: String) {
        if (line.isBlank() || _running.value) return
        emit("$prompt$line\r\n")
        val t = line.trim()
        if (t == "clear") { emulator.clear(); _snapshot.value = emulator.snapshot(); return }
        if (t == "cd" || t.startsWith("cd ")) {
            val target = t.removePrefix("cd").trim().ifEmpty { root.path }
            val next = if (target.startsWith("/")) File(target) else File(cwd, target)
            val canon = runCatching { next.canonicalFile }.getOrNull()
            if (canon != null && canon.isDirectory && canon.path.startsWith(root.canonicalPath)) cwd = canon
            else emit("cd: $target: not a directory inside this project\r\n")
            return
        }
        _running.value = true
        Thread({
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", line).directory(cwd).redirectErrorStream(true)
                pb.environment().apply {
                    put("HOME", root.path); put("PWD", cwd.path); put("TERM", "xterm"); put("PATH", "/system/bin:/system/xbin")
                    put("TMPDIR", root.path)
                }
                val p = pb.start()
                proc = p
                p.outputStream.close()
                val buf = CharArray(4096)
                p.inputStream.reader().use { r ->
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        emit(String(buf, 0, n).replace("\n", "\r\n"))
                    }
                }
                val code = p.waitFor()
                if (code != 0) emit("[exit $code]\r\n")
            } catch (e: Exception) {
                emit("error: ${e.message}\r\n")
            } finally {
                proc = null
                _running.value = false
            }
        }, "terminal").start()
    }

    fun interrupt() { proc?.destroy() }

    fun close() { proc?.destroyForcibly() }
}
