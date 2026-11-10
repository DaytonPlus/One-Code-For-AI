package com.devdayton.onecodeforai.core

import com.devdayton.onecodeforai.android.PluginLogger
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
 * ### Why swallowed exceptions are still logged
 * A swallowed exception in [withGit] used to disappear entirely. That made
 * the diff-preview path silently show "no changes detected" whenever
 * `scanDiff` threw — for example because `FileTreeIterator` could not read
 * the working tree on Android. Every catch site in [withGit], [openGit], and
 * [scanDiff] now logs the failure at WARN level with the directory and the
 * exception message, so the IDE console shows *why* a diff (or any other
 * query) came back empty instead of leaving the user with a green "nothing
 * to review" message that hides a real error.
 *
 * ### Partial and shallow clones
 * A repository cloned with `git clone --depth N` (shallow) or
 * `--filter=blob:none` (partial) does not contain every historical blob
 * locally. JGit 5.13 does not implement the promisor/fetch-on-demand
 * protocol, so opening such a blob throws
 * `org.eclipse.jgit.errors.MissingObjectException`. The diff preview is
 * designed to degrade gracefully in that situation:
 *
 *  - [getDiffStats] processes each [DiffEntry] independently. A file whose
 *    blob cannot be opened is still listed (with its change status) but
 *    with `0` insertions and `0` deletions, so the user sees *that* the
 *    file changed even though the line counts are unavailable.
 *  - [getDiff] writes an inline `[diff unavailable for <path>: …]` marker
 *    in place of the missing file's hunks, and continues with the rest.
 *
 * The user-facing remedy is `git fetch --unshallow` (shallow) or
 * `git fetch` (partial), which restores the missing objects. The plugin
 * cannot do this on the user's behalf without network access, which it
 * deliberately does not request.
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
     * Tag used for every diagnostic log emitted by this object. Kept short so
     * the IDE console stays readable when the tree walk emits dozens of lines.
     */
    private const val TAG = "GitUtils"

    /**
     * Opens a repository, runs [block], and always closes the repository.
     *
     * All exceptions raised by [block] are swallowed and reported as `null`,
     * including [InterruptedException]. That is intentional: this helper is
     * only used by the metadata and diff queries, which are not part of the
     * cancellable export path. See the class-level *Exception policy* note.
     *
     * Every swallowed exception is logged at WARN level with the directory and
     * the underlying message. Without this log, a failing diff computation
     * (e.g. `FileTreeIterator` hitting an unreadable working-tree file on
     * Android) would silently appear as "no changes detected" in the UI, which
     * is the exact failure mode that made the diff preview look broken.
     */
    private inline fun <T> withGit(directory: File, block: (Git) -> T): T? {
        val git = openGit(directory) ?: return null
        return try {
            block(git)
        } catch (error: Exception) {
            PluginLogger.w(
                TAG,
                "withGit: block failed for ${directory.absolutePath}: " +
                    (error.message ?: error.javaClass.simpleName),
                error
            )
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
     * repository cannot be opened. The failure mode is logged so that a
     * missing `.git` marker is distinguishable from a repository that exists
     * but fails to open (e.g. a corrupted `config` file).
     */
    private fun openGit(directory: File): Git? {
        return try {
            val builder = FileRepositoryBuilder().findGitDir(directory.canonicalFile)
            if (builder.gitDir == null) {
                PluginLogger.w(
                    TAG,
                    "openGit: no .git marker found for ${directory.absolutePath}"
                )
                return null
            }
            Git(builder.build())
        } catch (error: Exception) {
            PluginLogger.w(
                TAG,
                "openGit: failed for ${directory.absolutePath}: " +
                    (error.message ?: error.javaClass.simpleName),
                error
            )
            null
        }
    }

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
        val deletions: Int,
        /**
         * `true` when the file's change could be detected (the tree walk saw
         * it) but its line-level stats could not be computed — typically
         * because the repository is a shallow or partial clone and the
         * historical blob is missing locally. In that case [insertions] and
         * [deletions] are both `0` and the UI is expected to render the file
         * with a "stats unavailable" hint instead of a misleading `+0 -0`.
         */
        val statsUnavailable: Boolean = false
    )

    /**
     * Per-file diff statistics between [ref] and the working tree.
     *
     * The diff is computed with rename detection enabled so a moved file is
     * reported once as a rename instead of an add + delete pair. Returns `null`
     * when the repository, the ref, or the working tree cannot be read.
     *
     * ### Per-entry error handling
     * Each [DiffEntry] is processed independently inside a `runCatching`. On a
     * shallow or partial clone, `DiffFormatter.toFileHeader(...)` throws
     * `MissingObjectException` for any file whose historical blob is not
     * present in `.git/objects/`. Previously that exception aborted the whole
     * computation and surfaced as "could not compute the diff"; now the
     * affected file is still listed with its change status and a
     * [FileChange.statsUnavailable] flag, so the user sees *which* files
     * changed even when the line counts are unavailable.
     *
     * ### Why the failures are logged
     * The log line names the exact path and blob that could not be opened, so
     * the user can run `git fetch --unshallow` (or `git fetch` for a partial
     * clone) and then retry the preview.
     */
    fun getDiffStats(dir: File, ref: String): DiffStats? = withGit(dir) { git ->
        val entries = scanDiff(git, ref) ?: return@withGit null
        val changes = DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.setDetectRenames(true)
            entries.map { entry ->
                val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
                val status = entry.changeType.toStatusChar()
                runCatching {
                    val editList = formatter.toFileHeader(entry).toEditList()
                    val insertions = editList.sumOf { edit -> edit.endB - edit.beginB }
                    val deletions = editList.sumOf { edit -> edit.endA - edit.beginA }
                    FileChange(
                        path = path,
                        status = status,
                        insertions = insertions,
                        deletions = deletions,
                        statsUnavailable = false
                    )
                }.onFailure { error ->
                    PluginLogger.w(
                        TAG,
                        "getDiffStats: stats unavailable for '$path' " +
                            "(${entry.changeType}): " +
                            (error.message ?: error.javaClass.simpleName) +
                            " — this usually means the repository is a shallow " +
                            "or partial clone and the historical blob is missing " +
                            "locally. Run 'git fetch --unshallow' (or 'git fetch' " +
                            "for a partial clone) to restore it."
                    )
                }.getOrElse {
                    // Keep the entry visible with its change status so the user
                    // still sees which files changed, even though we cannot
                    // compute line-level stats for this one.
                    FileChange(
                        path = path,
                        status = status,
                        insertions = 0,
                        deletions = 0,
                        statsUnavailable = true
                    )
                }
            }
        }
        val stats = DiffStats(
            filesChanged = changes.size,
            insertions = changes.filterNot { it.statsUnavailable }.sumOf(FileChange::insertions),
            deletions = changes.filterNot { it.statsUnavailable }.sumOf(FileChange::deletions),
            files = changes.sortedBy { it.path.lowercase() }
        )
        PluginLogger.d(
            TAG,
            "getDiffStats: '$ref' -> ${stats.filesChanged} files, " +
                "+${stats.insertions} -${stats.deletions}, " +
                "${changes.count { it.statsUnavailable }} with unavailable stats"
        )
        stats
    }

    /**
     * Unified diff text between [ref] and the working tree, truncated to
     * [maxLines] to keep the preview dialog responsive on large changes.
     *
     * ### Per-entry error handling
     * Each [DiffEntry] is formatted inside its own `runCatching`. When a blob
     * is missing (shallow or partial clone), the diff for that one file is
     * replaced with an inline `[diff unavailable for <path>: …]` marker and
     * the loop continues with the next entry, so the user still gets the diff
     * for every file whose blobs *are* present.
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
            entries.forEach { entry ->
                val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
                runCatching { formatter.format(entry) }
                    .onFailure { error ->
                        PluginLogger.w(
                            TAG,
                            "getDiff: diff unavailable for '$path' " +
                                "(${entry.changeType}): " +
                                (error.message ?: error.javaClass.simpleName) +
                                " — this usually means the repository is a shallow " +
                                "or partial clone and the historical blob is missing " +
                                "locally. Run 'git fetch --unshallow' (or 'git fetch' " +
                                "for a partial clone) to restore it."
                        )
                        // Write an inline marker so the user sees *that* this
                        // file changed even though its hunks are unavailable.
                        // Best-effort: if even this write fails, the next
                        // entry's header will still be written correctly.
                        //
                        // The marker string is built into a local first and
                        // only then converted to bytes. Writing
                        // `output.write("…" + "…".toByteArray())` would parse
                        // as `String + ByteArray` because `.` binds tighter
                        // than `+`, and none of ByteArrayOutputStream's
                        // `write` overloads accept a String, which made the
                        // call fail to compile.
                        runCatching {
                            val marker = "\n[diff unavailable for $path: " +
                                "${error.message ?: error.javaClass.simpleName}]\n"
                            output.write(marker.toByteArray(Charsets.UTF_8))
                        }
                    }
            }
        }
        val lines = output.toString(Charsets.UTF_8.name()).lineSequence().toList()
        PluginLogger.d(
            TAG,
            "getDiff: '$ref' -> ${lines.size} lines, ${output.size()} bytes"
        )
        val limit = maxLines.coerceAtLeast(1)
        if (lines.size > limit) {
            lines.take(limit).joinToString("\n") + "\n\n... (${lines.size - limit} lines truncated)"
        } else {
            lines.joinToString("\n")
        }
    }

    /**
     * Computes the raw list of [DiffEntry] between [ref] and the working tree.
     *
     * ### Failure modes and why they are logged
     * The diff preview used to be silent about failures: any exception raised
     * here was swallowed by [withGit] and surfaced as "no changes detected"
     * in the dialog, which is indistinguishable from a genuinely clean diff.
     * On Android the two most common causes are:
     *
     *  - **Working tree unreachable.** `findGitDir` can succeed while
     *    `repository.workTree` still points at a directory that does not
     *    exist or is not readable — a submodule with a stale `gitdir`, a
     *    repository moved after the `.git` file was written, or a bare
     *    repository used as a project root. `FileTreeIterator(repository)`
     *    then throws during construction, and without an explicit check
     *    the exception would be swallowed by [withGit].
     *  - **Ref does not peel to a tree.** Rare, but possible with unusual
     *    ref expressions or a partially-cloned repository. `^{tree}` peels
     *    annotated tags and lightweight tags to the commit they point at,
     *    so a failure here genuinely means the ref is unusable.
     *
     * Both checks log at WARN and return `null`, which the caller maps to
     * the localized "could not compute the diff" message instead of the
     * misleading "no changes detected".
     *
     * ### Why the entry count is logged
     * When the diff *does* succeed, the number of entries is the single most
     * useful datum for diagnosing a UI-side issue (e.g. an empty file list
     * because a downstream filter dropped everything). Emitting it here
     * avoids having to re-run the computation to find out.
     *
     * ### What this method does *not* check
     * This method deliberately does not verify that the blobs referenced by
     * the entries are present in `.git/objects/`. On a shallow or partial
     * clone they may not be, and reading them happens downstream in
     * [getDiffStats] / [getDiff], where each entry is processed independently
     * so one missing blob cannot abort the whole preview. Doing the existence
     * check here would double the cost for repositories that *do* have all
     * their objects, which is the common case.
     *
     * @return the raw diff entries, or `null` when the diff cannot be
     *   computed for any of the reasons above.
     */
    private fun scanDiff(git: Git, ref: String): List<DiffEntry>? {
        val repository = git.repository

        // Resolve the ref to its tree. `^{tree}` peels annotated tags and
        // lightweight tags down to the commit's tree, and works for branch
        // names, remote branches, and full or abbreviated commit hashes.
        val treeId = repository.resolve("${ref.trim()}^{tree}")
        if (treeId == null) {
            PluginLogger.w(
                TAG,
                "scanDiff: '$ref' could not be resolved to a tree"
            )
            return null
        }

        // The working tree iterator needs a real, readable directory. On
        // Android this is the most common source of silent failures: a repo
        // whose work tree was not resolved by `findGitDir` (e.g. a submodule
        // with a relative `gitdir` file, or a bare repo) will make
        // `FileTreeIterator(repository)` throw, and without this explicit
        // check the exception would be swallowed by `withGit` and surface as
        // "no changes detected".
        val workTree = repository.workTree
        if (workTree == null || !workTree.isDirectory) {
            PluginLogger.w(
                TAG,
                "scanDiff: work tree is null or not a directory for '$ref' " +
                    "(repo dir: ${repository.directory})"
            )
            return null
        }

        return repository.newObjectReader().use { reader ->
            val oldTree = CanonicalTreeParser().apply { reset(reader, treeId) }
            DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(repository)
                formatter.setDetectRenames(true)
                val entries = formatter.scan(oldTree, FileTreeIterator(repository))
                PluginLogger.d(
                    TAG,
                    "scanDiff: '$ref' -> ${entries.size} entries " +
                        "against work tree ${workTree.absolutePath}"
                )
                entries
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