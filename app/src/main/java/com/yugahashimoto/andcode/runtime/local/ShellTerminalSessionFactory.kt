package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Opens a shell in a project folder using Android's own `/system/bin/sh`. There is no PTY and no
 * Linux userland (AndCode's proot guest is not part of this app), so full-screen programs do not
 * work; commands run only when the user submits them.
 */
class ShellTerminalSessionFactory(private val projectsRoot: File) : TerminalSessionFactory {
    override fun open(request: TerminalOpenRequest): TerminalSession {
        val dir = request.startDirectory?.let(::File)?.takeIf { it.isDirectory && it.canonicalPath.startsWith(projectsRoot.canonicalPath) } ?: projectsRoot
        val pb = ProcessBuilder("/system/bin/sh").directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", dir.absolutePath)
            put("PWD", dir.absolutePath)
            put("TERM", "xterm")
            put("PATH", "/system/bin:/system/xbin")
            put("TMPDIR", dir.absolutePath)
            put("PS1", "\$ ")
        }
        return ProcessTerminalSession(pb.start())
    }
}
