package com.kineai.local.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.ByteArrayOutputStream
import java.io.File

data class GitStatus(
    val branch: String?,
    val staged: List<String>,
    val modified: List<String>,
    val untracked: List<String>,
    val missing: List<String>,
    val conflicting: List<String>,
) {
    val clean get() = staged.isEmpty() && modified.isEmpty() && untracked.isEmpty() && missing.isEmpty() && conflicting.isEmpty()
}

data class GitCommit(val id: String, val message: String, val author: String, val time: Long)

/** Git in pure Java (JGit): works with no network and no external binaries. */
class GitService {
    fun isRepo(dir: File) = File(dir, ".git").exists()

    suspend fun init(dir: File) = withContext(Dispatchers.IO) { Git.init().setDirectory(dir).call().close() }

    suspend fun status(dir: File): GitStatus = withContext(Dispatchers.IO) {
        Git.open(dir).use { g ->
            val s = g.status().call()
            GitStatus(
                g.repository.branch,
                (s.added + s.changed + s.removed).sorted(),
                s.modified.sorted(),
                s.untracked.sorted(),
                s.missing.sorted(),
                s.conflicting.sorted(),
            )
        }
    }

    /** Unified diff of the working tree against the index (unstaged) or the index against HEAD (staged). */
    suspend fun diff(dir: File, staged: Boolean): String = withContext(Dispatchers.IO) {
        Git.open(dir).use { g ->
            val out = ByteArrayOutputStream()
            DiffFormatter(out).use { f ->
                f.setRepository(g.repository)
                val head = g.repository.resolve("HEAD^{tree}")
                if (staged) {
                    val reader = g.repository.newObjectReader()
                    val old = head?.let { CanonicalTreeParser().apply { reset(reader, it) } } ?: CanonicalTreeParser()
                    val cur = org.eclipse.jgit.dircache.DirCacheIterator(g.repository.readDirCache())
                    f.format(f.scan(old, cur))
                } else {
                    val cur = FileTreeIterator(g.repository)
                    val idx = org.eclipse.jgit.dircache.DirCacheIterator(g.repository.readDirCache())
                    f.format(f.scan(idx, cur))
                }
            }
            out.toString(Charsets.UTF_8).ifBlank { "(no ${if (staged) "staged" else "unstaged"} changes)" }
        }
    }

    suspend fun log(dir: File, max: Int = 30): List<GitCommit> = withContext(Dispatchers.IO) {
        Git.open(dir).use { g ->
            if (g.repository.resolve(Constants.HEAD) == null) emptyList()
            else g.log().setMaxCount(max).call().map { GitCommit(it.name.take(8), it.shortMessage, it.authorIdent.name, it.commitTime * 1000L) }
        }
    }

    suspend fun stageAll(dir: File) = withContext(Dispatchers.IO) {
        Git.open(dir).use { g -> g.add().addFilepattern(".").call(); g.add().setUpdate(true).addFilepattern(".").call() }
        Unit
    }

    suspend fun commit(dir: File, message: String, name: String, email: String) = withContext(Dispatchers.IO) {
        Git.open(dir).use { g -> g.commit().setMessage(message).setAuthor(PersonIdent(name, email)).setCommitter(PersonIdent(name, email)).call() }
        Unit
    }

    /** User-requested clone over the network. The only Git operation that uses it. */
    suspend fun clone(url: String, dir: File) = withContext(Dispatchers.IO) {
        Git.cloneRepository().setURI(url).setDirectory(dir).call().close()
    }
}
