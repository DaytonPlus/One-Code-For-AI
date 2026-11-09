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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Encapsulates all JGit access for the plugin.
 *
 * Every operation opens and closes its repository inside the same call, so no
 * file handle is leaked to the caller and the plugin never holds a stale
 * `Repository` across UI events.
 *
 * All operations are synchronous and may block on disk I/O. Callers running on
 * the UI thread must dispatch to a background executor (see
 * `ExportCoordinator`, `ConfigurationDialog`, and `OneCodeForAIPlugin`).
 *
 * Exception policy
 * ----------------
 * Two opening helpers exist, with deliberately different exception handling:
 *
 *  - [withGit] **swallows every exception** and returns `null` / a default.
 *    It backs the *metadata* and *diff* helpers ([getBranches], [getTags],
 *    [getRecentCommits], [getRepoInfo], [getDiff], …). Those are UI-facing
 *    queries that are not cancellable from the export coordinator, so a
 *    missing repo, a locked index, or an interrupted thread should surface
 *    as an empty result rather than crash a spinner.
 *  - [withSnapshot] **propagates every exception**. It backs the actual
 *    export path, so a `SecurityException`, an [java.io.IOException], or an
 *    [InterruptedException] must reach [com.devdayton.onecodeforai.android.ExportCoordinator]
 *    unchanged. That is what lets the coordinator distinguish a real I/O
 *    failure from a user cancellation (see the `Cancellation` note below).
 *
 * Cancellation
 * ------------
 * Only the export path is cancellable. [GitSnapshot.writeFileContent] streams
 * each blob through [copyNormalizedText], which checks the worker thread's
 * interrupt flag **before every read** (roughly every [DEFAULT_BUFFER_SIZE]
 * characters). The thrown [InterruptedException] is not caught anywhere on the
 * call chain [GitSnapshot.writeFileContent] → [withSnapshot] → caller, so it
 * unwinds all the way to the export coordinator and terminates the run within
 * a single chunk — even in the middle of a multi-megabyte file.
 */
object GitUtils {

    /**
     * Opens a repository, runs [block], and always closes the repository.
     *
     * All exceptions raised by [block] are swallowed and reported as `null`,
     * including [InterruptedException]. That is intentional: this helper is
     * only used by the metadata and diff queries, which are not part of the
     * cancellable export path. See the class-level *Exception policy* note.
     */
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

    /**
     * Resolves a `Git` handle for [directory], walking up until a `.git` entry
     * is found. Supports:
     *  - normal repositories (a `.git` directory),
     *  - worktrees and submodules (a `.git` file pointing at the real dir),
     *  - project folders nested inside a larger repository.
     *
     * Returns `null` when the folder is not part of any repository or the
     * repository cannot be opened.
     */
    private fun openGit(directory: File): Git? = runCatching {
        val builder = FileRepositoryBuilder().findGitDir(directory.canonicalFile)
        if (builder.gitDir == null) return null
        Git(builder.build())
    }.getOrNull()

    /** Returns `true` if [dir] is inside a readable Git repository. */
    fun isGitRepo(dir: File): Boolean = withGit(dir) { true } ?: false

    /**
     * Walks parent directories from [startDir] and returns the closest one that
     * contains a `.git` marker, or `null` if none exists.
     */
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

    /** Current branch name without the `refs/heads/` prefix, or `null` in detached HEAD. */
    fun getCurrentBranch(dir: File): String? = withGit(dir) { git ->
        git.repository.fullBranch?.removePrefix(Constants.R_HEADS)
    }

    /** First seven characters of the HEAD commit hash, or `null` for an empty repository. */
    fun getCurrentCommit(dir: File): String? = withGit(dir) { git ->
        git.repository.resolve(Constants.HEAD)?.name?.take(7)
    }

    /**
     * Lists local and remote branches, sorted case-insensitively by name.
     * Remote `HEAD` aliases (e.g. `origin/HEAD`) are filtered out because they
     * are pointers to another branch, not checkable targets.
     */
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

    /** Lists tags with their abbreviated target hash, sorted case-insensitively. */
    fun getTags(dir: File): List<TagInfo> = withGit(dir) { git ->
        git.tagList().call().map { ref ->
            TagInfo(
                name = ref.name.removePrefix(Constants.R_TAGS),
                shortHash = ref.objectId.name.take(7)
            )
        }.sortedBy { it.name.lowercase() }
    } ?: emptyList()

    /**
     * Returns the most recent commits reachable from [ref], newest first.
     * When [ref] is `null` or blank, walks from HEAD.
     *
     * [limit] is clamped to `1..500` to keep the reference picker responsive.
     */
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

    /**
     * Formats a commit's author timestamp with a locale-independent pattern.
     *
     * `SimpleDateFormat` is chosen over `java.time` formatters because it is
     * available since API 1 and needs no desugaring; the plugin's `minSdk` is
     * 26, but keeping this dependency-free avoids a hidden coupling between
     * `core/` and the Android build configuration. Instances are created per
     * call (they are cheap and `SimpleDateFormat` is not thread-safe).
     */
    private fun toCommitInfo(commit: RevCommit): CommitInfo = CommitInfo(
        hash = commit.name,
        shortHash = commit.name.take(7),
        author = commit.authorIdent.name,
        // `PersonIdent.getWhenAsInstant()` was added in JGit 6.x. This plugin
        // targets JGit 5.13 (the last series that runs on Android without
        // requiring `readAllBytes` from API 33), where only `getWhen()` is
        // available. `getWhen()` returns a `Date`, which we format with the
        // same pattern that JGit's `getWhenAsInstant().toString()` would
        // produce, so the UI output is unchanged.
        date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(commit.authorIdent.`when`),
        message = commit.shortMessage
    )

    /**
     * Resolves [ref] to its commit and returns the abbreviated hash, or `null`
     * when the reference does not exist. Peeling with `^{commit}` makes tags
     * and annotated tags resolve to the commit they point at.
     */
    fun resolveRefShort(dir: File, ref: String): String? = withGit(dir) { git ->
        git.repository.resolve("${ref.trim()}^{commit}")?.name?.take(7)
    }

    /** Returns `true` if [ref] resolves to a commit in the repository. */
    fun refExists(dir: File, ref: String): Boolean = resolveRefShort(dir, ref) != null

    data class RepoInfo(
        val currentBranch: String?,
        val currentCommit: String?,
        val remoteUrl: String?,
        val isDetached: Boolean,
        val hasUncommittedChanges: Boolean
    )

    /**
     * Snapshot of the repository state used to render the Git section in the
     * configuration dialog.
     *
     * `hasUncommittedChanges` reflects both tracked modifications and untracked
     * files; the caller may want to show a warning before exporting a snapshot
     * that differs from the working tree.
     */
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

    /** Number of tracked modifications plus untracked files, for the warning badge. */
    fun countUncommittedChanges(dir: File): Int = withGit(dir) { git ->
        val status = git.status().call()
        status.uncommittedChanges.size + status.untracked.size
    } ?: 0

    // ---------------------------------------------------------------------
    // Git snapshot export
    // ---------------------------------------------------------------------

    data class GitSnapshotFile(val path: String, val size: Long)

    /**
     * Opens a single repository session for a complete Git export.
     *
     * Keeping the repository open across tree enumeration and blob streaming
     * avoids reopening JGit once per file in large projects.
     *
     * Unlike [withGit], this helper **does not swallow exceptions**: the actual
     * I/O failure (a closed repository, a `SecurityException`, an
     * [java.io.IOException], or an [InterruptedException] raised by
     * [GitSnapshot.writeFileContent]) propagates out of [operation] unchanged
     * so the coordinator can report it accurately instead of silently emitting
     * a partial snapshot.
     *
     * @throws java.io.IOException when the folder is not a readable repository
     *   or [ref] cannot be resolved to a tree.
     * @throws InterruptedException when the worker thread is interrupted while
     *   [operation] is streaming blob content; see [copyNormalizedText].
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

    /** Lists the regular blobs of [ref], or `null` when the repository or ref cannot be opened. */
    fun listFilesAtRef(dir: File, ref: String): List<GitSnapshotFile>? = runCatching {
        withSnapshot(dir, ref) { snapshot -> snapshot.listFiles() }
    }.getOrNull()

    /** Streams a single file from [ref] into [writer]. Returns `false` on any failure. */
    fun writeFileContent(dir: File, ref: String, path: String, writer: Writer): Boolean =
        runCatching {
            withSnapshot(dir, ref) { snapshot -> snapshot.writeFileContent(path, writer) }
        }.getOrDefault(false)

    /** Convenience wrapper returning the blob as a String; retained for callers that need it. */
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

    /**
     * Read-only view over one resolved Git tree.
     *
     * Instances are only valid inside [withSnapshot]: the underlying repository
     * is closed when the callback returns, so any reference retained by the
     * caller will fail on subsequent use.
     */
    class GitSnapshot internal constructor(
        private val repository: org.eclipse.jgit.lib.Repository,
        private val ref: String,
        private val treeId: org.eclipse.jgit.lib.ObjectId
    ) {
        /**
         * Walks the tree recursively and returns every regular blob. Submodules
         * (gitlinks) and symlinks are skipped: neither is safe to include in a
         * dump, and both would break the caller's path-to-content assumptions.
         */
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

        /**
         * Writes the content of [path] from this snapshot into [writer],
         * normalizing CRLF to LF so the dump is consistent across platforms.
         *
         * Returns `false` when the path is absent or does not resolve to a blob
         * — that is a *per-file* failure, and the caller is expected to write
         * an inline `[Git read error: …]` marker and keep going.
         *
         * Cancellation
         * ------------
         * An [InterruptedException] raised by [copyNormalizedText] (worker thread
         * interrupted while streaming the blob) is deliberately **not** caught:
         * it propagates out of this method, through [withSnapshot], and all the
         * way to the export coordinator, which aborts the run. That is what
         * makes the Cancel button responsive even in the middle of a large
         * file, and it is why cancellation is never reported as a per-file read
         * error.
         */
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

    /**
     * Per-file diff statistics between [ref] and the working tree.
     *
     * The diff is computed with rename detection enabled so a moved file is
     * reported once as a rename instead of an add + delete pair. Returns `null`
     * when the repository or ref cannot be read.
     */
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

    /**
     * Unified diff text between [ref] and the working tree, truncated to
     * [maxLines] to keep the preview dialog responsive on large changes.
     *
     * The truncation notice is appended in English so it reads consistently
     * regardless of the IDE locale.
     */
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
            lines.take(limit).joinToString("\n") + "\n\n... (${lines.size - limit} lines truncated)"
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

    /**
     * Copies [reader] to [writer] normalizing every line ending to `\n` and
     * guaranteeing the output ends with exactly one newline.
     *
     * Git blobs are stored with LF, but the working tree may contain CRLF on
     * Windows or with `core.autocrlf`; normalizing here keeps the dump uniform
     * regardless of the source.
     *
     * Cancellation
     * ------------
     * The worker thread's interrupt flag is checked **before every read**
     * (roughly every [DEFAULT_BUFFER_SIZE] characters). This is what lets
     * [com.devdayton.onecodeforai.android.ExportCoordinator.cancel] stop the
     * export within a single chunk even when the current blob is a
     * multi-megabyte file. The thrown [InterruptedException] is not caught
     * here or in [GitSnapshot.writeFileContent] / [withSnapshot]; it unwinds
     * all the way to the coordinator, which distinguishes it from a real I/O
     * failure.
     */
    private fun copyNormalizedText(reader: java.io.Reader, writer: Writer) {
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        var previousWasCarriageReturn = false
        var lastWasNewline = false

        while (true) {
            // Check every chunk so that a single large blob cannot block the
            // Cancel button. The caller's loop in ExportCoordinator only checks
            // between files, which is not enough for files bigger than a few
            // hundred KiB.
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Git dump generation was interrupted.")
            }
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