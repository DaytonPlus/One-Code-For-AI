package com.devdayton.onecodeforai.core

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Writer

/**
 * Encapsulates all JGit access. Every operation opens and closes its repository
 * inside the same call, preventing leaked file handles on Android.
 */
object GitUtils {

    private inline fun <T> withGit(directory: File, block: (Git) -> T): T? {
        val git = openGit(directory) ?: return null
        return try {
            block(git)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { git.close() }
        }
    }

    /** Supports normal repositories, nested project folders and .git worktree files. */
    private fun openGit(directory: File): Git? = runCatching {
        val builder = FileRepositoryBuilder().findGitDir(directory.canonicalFile)
        if (builder.gitDir == null) return null
        Git(builder.build())
    }.getOrNull()

    fun isGitRepo(dir: File): Boolean = withGit(dir) { true } ?: false

    fun findGitRoot(startDir: File): File? {
        var current: File? = if (startDir.isFile) startDir.parentFile else startDir
        while (current != null) {
            val gitMarker = File(current, ".git")
            if (gitMarker.exists() || gitMarker.isFile) return current.canonicalFile
            current = current.parentFile
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Repository metadata and references
    // ---------------------------------------------------------------------

    fun getCurrentBranch(dir: File): String? = withGit(dir) { git ->
        git.repository.fullBranch?.removePrefix(Constants.R_HEADS)
    }

    fun getCurrentCommit(dir: File): String? = withGit(dir) { git ->
        git.repository.resolve(Constants.HEAD)?.name?.take(7)
    }

    fun getBranches(dir: File): List<BranchInfo> = withGit(dir) { git ->
        val currentBranch = git.repository.fullBranch?.removePrefix(Constants.R_HEADS)
        val local = git.branchList().call().map { ref ->
            val name = ref.name.removePrefix(Constants.R_HEADS)
            BranchInfo(name, name == currentBranch, isRemote = false)
        }
        val remote = git.branchList().setListMode(ListMode.REMOTE).call()
            .mapNotNull { ref ->
                val name = ref.name.removePrefix(Constants.R_REMOTES)
                if (name.endsWith("/HEAD")) null else BranchInfo(name, false, true)
            }
        (local + remote).distinctBy(BranchInfo::name).sortedBy { it.name.lowercase() }
    } ?: emptyList()

    fun getTags(dir: File): List<TagInfo> = withGit(dir) { git ->
        git.tagList().call().map { ref ->
            TagInfo(
                name = ref.name.removePrefix(Constants.R_TAGS),
                shortHash = ref.objectId.name.take(7)
            )
        }.sortedBy { it.name.lowercase() }
    } ?: emptyList()

    fun getRecentCommits(dir: File, ref: String? = null, limit: Int = 60): List<CommitInfo> =
        withGit(dir) { git ->
            val command = git.log().setMaxCount(limit.coerceIn(1, 500))
            if (!ref.isNullOrBlank()) {
                val objectId = git.repository.resolve("${ref.trim()}^{commit}")
                    ?: return@withGit emptyList()
                command.add(objectId)
            }
            command.call().map(::toCommitInfo)
        } ?: emptyList()

    private fun toCommitInfo(commit: RevCommit): CommitInfo = CommitInfo(
        hash = commit.name,
        shortHash = commit.name.take(7),
        author = commit.authorIdent.name,
        date = commit.authorIdent.whenAsInstant.toString(),
        message = commit.shortMessage
    )

    fun resolveRefShort(dir: File, ref: String): String? = withGit(dir) { git ->
        git.repository.resolve("${ref.trim()}^{commit}")?.name?.take(7)
    }

    fun refExists(dir: File, ref: String): Boolean = resolveRefShort(dir, ref) != null

    data class RepoInfo(
        val currentBranch: String?,
        val currentCommit: String?,
        val remoteUrl: String?,
        val isDetached: Boolean,
        val hasUncommittedChanges: Boolean
    )

    fun getRepoInfo(dir: File): RepoInfo? = withGit(dir) { git ->
        val repository = git.repository
        val fullBranch = repository.fullBranch
        val branch = fullBranch?.removePrefix(Constants.R_HEADS)
        val commit = repository.resolve(Constants.HEAD)?.name?.take(7)
        val remoteUrl = runCatching {
            git.remoteList().call().firstOrNull()?.getURIs()?.firstOrNull()?.toString()
        }.getOrNull()
        val status = git.status().call()
        val hasChanges = status.uncommittedChanges.isNotEmpty() || status.untracked.isNotEmpty()

        RepoInfo(
            currentBranch = branch,
            currentCommit = commit,
            remoteUrl = remoteUrl,
            isDetached = fullBranch?.startsWith(Constants.R_HEADS) != true,
            hasUncommittedChanges = hasChanges
        )
    }

    fun countUncommittedChanges(dir: File): Int = withGit(dir) { git ->
        val status = git.status().call()
        status.uncommittedChanges.size + status.untracked.size
    } ?: 0

    // ---------------------------------------------------------------------
    // Git snapshot export
    // ---------------------------------------------------------------------

    data class GitSnapshotFile(val path: String, val size: Long)

    /**
     * Opens one repository session for a complete Git export. Keeping the
     * repository open across tree enumeration and blob streaming avoids
     * reopening JGit once per file in large projects.
     *
     * Exceptions from [operation] are propagated so an export can report the
     * actual I/O failure instead of silently emitting a partial snapshot.
     */
    @Throws(java.io.IOException::class)
    fun <T> withSnapshot(
        dir: File,
        ref: String,
        operation: (GitSnapshot) -> T
    ): T {
        val git = openGit(dir)
            ?: throw java.io.IOException("The selected folder is not a readable Git repository.")
        try {
            val normalizedRef = ref.trim()
            val treeId = git.repository.resolve("$normalizedRef^{tree}")
                ?: throw java.io.IOException("Git reference '$normalizedRef' could not be resolved.")
            return operation(GitSnapshot(git.repository, normalizedRef, treeId))
        } finally {
            runCatching { git.close() }
        }
    }

    /** Lists regular blobs from the selected ref, not from the working tree. */
    fun listFilesAtRef(dir: File, ref: String): List<GitSnapshotFile>? = runCatching {
        withSnapshot(dir, ref) { snapshot -> snapshot.listFiles() }
    }.getOrNull()

    /** Streams a file from a Git object into the caller-owned writer. */
    fun writeFileContent(dir: File, ref: String, path: String, writer: Writer): Boolean =
        runCatching {
            withSnapshot(dir, ref) { snapshot -> snapshot.writeFileContent(path, writer) }
        }.getOrDefault(false)

    /** Compatibility helper retained for callers that require a String. */
    fun getFileContent(dir: File, ref: String, path: String): String? {
        val output = StringBuilder()
        val writer = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) {
                output.append(buffer, offset, length)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        return if (writeFileContent(dir, ref, path, writer)) output.toString() else null
    }

    /** A read-only view over one resolved Git tree; valid only inside withSnapshot. */
    class GitSnapshot internal constructor(
        private val repository: org.eclipse.jgit.lib.Repository,
        private val ref: String,
        private val treeId: org.eclipse.jgit.lib.ObjectId
    ) {
        fun listFiles(): List<GitSnapshotFile> {
            val files = mutableListOf<GitSnapshotFile>()
            TreeWalk(repository).use { walk ->
                walk.addTree(treeId)
                walk.isRecursive = true
                while (walk.next()) {
                    val mode = walk.getFileMode(0)
                    if (mode == FileMode.GITLINK || mode == FileMode.SYMLINK || mode.objectType != Constants.OBJ_BLOB) continue
                    val loader = repository.open(walk.getObjectId(0), Constants.OBJ_BLOB)
                    files += GitSnapshotFile(walk.pathString, loader.size)
                }
            }
            return files.sortedBy { it.path.lowercase() }
        }

        /** Returns false when a path is absent or does not resolve to a blob. */
        fun writeFileContent(path: String, writer: Writer): Boolean {
            val objectId = repository.resolve("$ref:$path") ?: return false
            val loader = runCatching { repository.open(objectId, Constants.OBJ_BLOB) }.getOrNull()
                ?: return false
            loader.openStream().bufferedReader(Charsets.UTF_8).use { reader ->
                copyNormalizedText(reader, writer)
            }
            return true
        }
    }

    // ---------------------------------------------------------------------
    // Diff: selected ref tree -> current working tree
    // ---------------------------------------------------------------------

    data class DiffStats(
        val filesChanged: Int,
        val insertions: Int,
        val deletions: Int,
        val files: List<FileChange>
    )

    data class FileChange(
        val path: String,
        val status: Char,
        val insertions: Int,
        val deletions: Int
    )

    fun getDiffStats(dir: File, ref: String): DiffStats? = withGit(dir) { git ->
        val entries = scanDiff(git, ref) ?: return@withGit null
        val changes = DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.setDetectRenames(true)
            entries.map { entry ->
                val editList = formatter.toFileHeader(entry).toEditList()
                val insertions = editList.sumOf { edit -> edit.endB - edit.beginB }
                val deletions = editList.sumOf { edit -> edit.endA - edit.beginA }
                FileChange(
                    path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath,
                    status = entry.changeType.toStatusChar(),
                    insertions = insertions,
                    deletions = deletions
                )
            }
        }
        DiffStats(
            filesChanged = changes.size,
            insertions = changes.sumOf(FileChange::insertions),
            deletions = changes.sumOf(FileChange::deletions),
            files = changes.sortedBy { it.path.lowercase() }
        )
    }

    fun getDiff(dir: File, ref: String, maxLines: Int = 800): String? = withGit(dir) { git ->
        val entries = scanDiff(git, ref) ?: return@withGit null
        val output = ByteArrayOutputStream()
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.setDetectRenames(true)
            entries.forEach { entry -> formatter.format(entry) }
        }
        val lines = output.toString(Charsets.UTF_8.name()).lineSequence().toList()
        val limit = maxLines.coerceAtLeast(1)
        if (lines.size > limit) {
            lines.take(limit).joinToString("\n") + "\n\n... (${lines.size - limit} líneas omitidas)"
        } else {
            lines.joinToString("\n")
        }
    }

    private fun scanDiff(git: Git, ref: String): List<DiffEntry>? {
        val repository = git.repository
        val baseTree = repository.resolve("${ref.trim()}^{tree}") ?: return null
        repository.newObjectReader().use { reader ->
            val oldTree = CanonicalTreeParser().apply { reset(reader, baseTree) }
            DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(repository)
                formatter.setDetectRenames(true)
                return formatter.scan(oldTree, FileTreeIterator(repository))
            }
        }
    }

    private fun DiffEntry.ChangeType.toStatusChar(): Char = when (this) {
        DiffEntry.ChangeType.ADD -> 'A'
        DiffEntry.ChangeType.DELETE -> 'D'
        DiffEntry.ChangeType.RENAME -> 'R'
        DiffEntry.ChangeType.COPY -> 'C'
        DiffEntry.ChangeType.MODIFY -> 'M'
    }

    private fun copyNormalizedText(reader: java.io.Reader, writer: Writer) {
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        var previousWasCarriageReturn = false
        var lastWasNewline = false

        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val char = buffer[index]
                if (previousWasCarriageReturn) {
                    writer.write('\n'.code)
                    lastWasNewline = true
                    previousWasCarriageReturn = false
                    if (char == '\n') continue
                }
                when (char) {
                    '\r' -> previousWasCarriageReturn = true
                    else -> {
                        writer.write(char.code)
                        lastWasNewline = char == '\n'
                    }
                }
            }
        }
        if (previousWasCarriageReturn) {
            writer.write('\n'.code)
            lastWasNewline = true
        }
        if (!lastWasNewline) writer.write('\n'.code)
    }

    data class BranchInfo(val name: String, val isCurrent: Boolean, val isRemote: Boolean)
    data class TagInfo(val name: String, val shortHash: String)
    data class CommitInfo(
        val hash: String,
        val shortHash: String,
        val author: String,
        val date: String,
        val message: String
    )

    private const val DEFAULT_BUFFER_SIZE = 8 * 1024
}
