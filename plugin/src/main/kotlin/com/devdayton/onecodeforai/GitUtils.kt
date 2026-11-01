package com.devdayton.onecodeforai

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.io.ByteArrayOutputStream
import java.io.File

object GitUtils {
    // Abre el repositorio JGit de forma segura
    private fun openGit(dir: File): Git? {
        return try {
            Git.open(dir)
        } catch (_: Exception) {
            // Si el .git está en una carpeta superior (worktree o submódulo)
            try {
                Git.open(File(dir, ".git"))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun isGitRepo(dir: File): Boolean = openGit(dir) != null

    fun findGitRoot(startDir: File): File? {
        var current: File? = startDir
        while (current != null) {
            if (File(current, ".git").exists()) return current
            current = current.parentFile
        }
        return null
    }

    // ---------------- Ramas ----------------

    fun getCurrentBranch(dir: File): String? {
        openGit(dir)?.use { git ->
            return git.repository.fullBranch?.removePrefix("refs/heads/")
        }
        return null
    }

    fun getCurrentCommit(dir: File): String? {
        openGit(dir)?.use { git ->
            val head = git.repository.resolve(Constants.HEAD) ?: return null
            return head.name.take(7)
        }
        return null
    }

    fun getBranches(dir: File): List<BranchInfo> {
        val result = mutableListOf<BranchInfo>()
        openGit(dir)?.use { git ->
            val current = git.repository.fullBranch?.removePrefix("refs/heads/")
            // Ramas locales
            git.branchList().call().forEach { ref ->
                val name = ref.name.removePrefix("refs/heads/")
                result.add(BranchInfo(name, name == current, false))
            }
            // Ramas remotas
            git.branchList().setListMode(ListMode.REMOTE).call().forEach { ref ->
                val name = ref.name.removePrefix("refs/remotes/")
                if (!name.endsWith("/HEAD")) {
                    result.add(BranchInfo(name, false, true))
                }
            }
        }
        return result.sortedBy { it.name }
    }

    // ---------------- Tags ----------------

    fun getTags(dir: File): List<TagInfo> {
        val result = mutableListOf<TagInfo>()
        openGit(dir)?.use { git ->
            git.tagList().call().forEach { ref ->
                val name = ref.name.removePrefix("refs/tags/")
                val hash = ref.objectId.name.take(7)
                result.add(TagInfo(name, hash))
            }
        }
        return result.sortedBy { it.name }
    }

    // ---------------- Commits ----------------

    fun getRecentCommits(dir: File, ref: String? = null, limit: Int = 60): List<CommitInfo> {
        val result = mutableListOf<CommitInfo>()
        openGit(dir)?.use { git ->
            val logCommand = git.log().setMaxCount(limit)
            if (!ref.isNullOrBlank()) {
                logCommand.add(git.repository.resolve(ref))
            }
            logCommand.call().forEach { commit ->
                result.add(
                    CommitInfo(
                        hash = commit.name,
                        shortHash = commit.name.take(7),
                        author = commit.authorIdent.name,
                        date = commit.authorIdent.`when`.toString(),
                        message = commit.shortMessage
                    )
                )
            }
        }
        return result
    }

    // ---------------- Resolución de referencias ----------------

    fun resolveRefShort(dir: File, ref: String): String? {
        openGit(dir)?.use { git ->
            val obj = git.repository.resolve(ref) ?: return null
            return obj.name.take(7)
        }
        return null
    }

    fun refExists(dir: File, ref: String): Boolean = resolveRefShort(dir, ref) != null

    // ---------------- Información del repositorio ----------------

    data class RepoInfo(
        val currentBranch: String?,
        val currentCommit: String?,
        val remoteUrl: String?,
        val isDetached: Boolean,
        val hasUncommittedChanges: Boolean
    )

    fun getRepoInfo(dir: File): RepoInfo? {
        openGit(dir)?.use { git ->
            val branch = git.repository.fullBranch?.removePrefix("refs/heads/")
            val commit = git.repository.resolve(Constants.HEAD)?.name?.take(7)
            val remoteUrl = try {
                git.remoteList().call().firstOrNull()?.getURIs()?.firstOrNull()?.toString()
            } catch (_: Exception) { null }
            
            val status = git.status().call()
            val hasChanges = status.uncommittedChanges.isNotEmpty()
            
            return RepoInfo(
                currentBranch = branch,
                currentCommit = commit,
                remoteUrl = remoteUrl,
                isDetached = branch == null,
                hasUncommittedChanges = hasChanges
            )
        }
        return null
    }

    fun countUncommittedChanges(dir: File): Int {
        openGit(dir)?.use { git ->
            return git.status().call().uncommittedChanges.size
        }
        return 0
    }

    // ---------------- Diff ----------------

    data class DiffStats(
        val filesChanged: Int, val insertions: Int, val deletions: Int,
        val files: List<FileChange>
    )
    data class FileChange(
        val path: String, val status: Char,
        val insertions: Int, val deletions: Int
    )

    fun getDiffStats(dir: File, ref: String): DiffStats? {
        openGit(dir)?.use { git ->
            val output = ByteArrayOutputStream()
            git.diff()
                .setCached(false)
                .setOutputStream(output)
                .call()
            
            // Parsear el diff para obtener estadísticas (simplificado)
            val diffText = output.toString(Charsets.UTF_8.name())
            val lines = diffText.lines()
            var filesChanged = 0
            var insertions = 0
            var deletions = 0
            val fileList = mutableListOf<FileChange>()
            
            // (Aquí iría el parseo del diff. Para no sobrecargar, 
            // puedes usar git.diff().setPathFilter(...) o simplemente devolver un DiffStats vacío si el parseo es complejo)
            return DiffStats(0, 0, 0, emptyList()) // Placeholder, requiere parseo
        }
        return null
    }

    fun getDiff(dir: File, ref: String, maxLines: Int = 800): String? {
        openGit(dir)?.use { git ->
            val output = ByteArrayOutputStream()
            git.diff()
                .setCached(false)
                .setOutputStream(output)
                .call()
            val text = output.toString(Charsets.UTF_8.name())
            val lines = text.lines()
            return if (lines.size > maxLines) {
                lines.take(maxLines).joinToString("\n") + "\n\n... (${lines.size - maxLines} más)"
            } else {
                text
            }
        }
        return null
    }

    // ---------------- Contenido de archivos ----------------

    fun getFileContent(dir: File, ref: String, path: String): String? {
        openGit(dir)?.use { git ->
            val objId = git.repository.resolve("$ref:$path") ?: return null
            return try {
                val loader = git.repository.open(objId)
                String(loader.bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    // ---------------- Modelos de datos (mantén tus data classes existentes) ----------------
    data class BranchInfo(val name: String, val isCurrent: Boolean, val isRemote: Boolean)
    data class TagInfo(val name: String, val shortHash: String)
    data class CommitInfo(
        val hash: String, val shortHash: String,
        val author: String, val date: String, val message: String
    )
}