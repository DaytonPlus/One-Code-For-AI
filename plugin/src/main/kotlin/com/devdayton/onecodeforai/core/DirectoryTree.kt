package com.devdayton.onecodeforai.core

import java.io.File
import java.io.StringWriter
import java.io.Writer
import java.util.ArrayDeque

/**
 * Writes the directory tree header that precedes the file contents in
 * every generated dump, matching the familiar gitingest/code2prompt
 * representation.
 *
 * The header is a plain-text rendering of the directory structure with
 * box-drawing characters:
 *
 *  - the branch connector for a non-last entry on its branch,
 *  - the last connector for the last entry on its branch,
 *  - the continuation and indent characters for branching,
 *  - a trailing slash on directory names so they are visually distinct
 *    from files.
 *
 * ### Tree v2: stubs are marked in the header
 *
 * A stub is a file the export lists but does not read (see
 * [NodeState.STUB] and [CodeDumpGenerator]). The header is what lets the
 * consuming model tell at a glance which entries it has content for and
 * which it only has metadata for, so stubs are suffixed with a
 * reference marker. The rendered header looks like this:
 *
 * Directory structure:
 * └── my-project/
 *     ├── package.json
 *     ├── package-lock.json [ref]
 *     └── src/
 *         └── index.ts
 *
 * The marker is rendered here and not in the body because the body
 * already carries a content-omitted line inside each stub's block, and
 * repeating that verbosity in the header would hurt the skim-ability
 * that makes the header useful. The reference suffix is compact,
 * unambiguous, and does not require the model to have seen a stub block
 * yet to understand it.
 *
 * ### Two rendering paths
 *
 *  - [writeTo] walks the real filesystem from [root] and applies
 *    [filter] to decide what to render. Kept for callers that build a
 *    tree straight from the filesystem (e.g. the configuration
 *    dialog's preview, though that uses a different renderer).
 *  - [writePathsTo] builds the tree from a flat list of relative
 *    paths. This is the path the export uses: the export iterates a
 *    Map of path to [NodeState] produced by the tree selector, and
 *    the header has to match that selection exactly (including any
 *    files or directories that the user manually overrode), which a
 *    filesystem walk would not.
 *
 * Both paths share the same box-drawing primitives and the same
 * deterministic sort order (directories first, then files, each
 * case-insensitively) so the header looks identical regardless of how
 * the input was produced.
 *
 * ### Structure of the header
 *
 * The header always begins with the literal line "Directory structure:"
 * and the root entry, followed by its children. The root is rendered
 * with the last-connector even though it is technically a top-level
 * node, matching the visual language used by every other tree renderer
 * in the plugin.
 *
 * The header ends after the last descendant. It does not carry any
 * statistics, separator, or trailing marker: the file contents follow
 * immediately, separated by the same banner the body uses between
 * files. This is a deliberate choice: the model reads the header and
 * the body as two halves of the same document, and any closing
 * flourish at the end of the header would just be noise.
 *
 * ### Cancellation
 *
 * Neither [writeTo] nor [writePathsTo] is cancellable. This is
 * intentional: the header is built before the file bodies and is
 * bounded by the number of entries the user selected, which is at most
 * a few tens of thousands on realistic projects and completes in well
 * under a second. The export coordinator's cancellation checkpoints
 * bracket this call, so a cancel that arrives during the header build
 * is honoured as soon as the header finishes. The user-visible latency
 * of that is negligible.
 *
 * ### Performance
 *
 *  - [writeTo] uses an iterative traversal (an explicit [ArrayDeque] of
 *    [FileFrame]s) rather than recursion, because a deeply nested
 *    directory tree would otherwise recurse once per level and risk a
 *    StackOverflowError. Only one directory's children are materialized
 *    at a time.
 *  - [writePathsTo] builds an in-memory trie in a single pass over the
 *    input paths and then renders it iteratively with an explicit
 *    [ArrayDeque] of [SnapshotFrame]s, for the same reason.
 *  - Both paths sort their children once during construction and reuse
 *    the sorted list during rendering, so the sort is O(n log n) in the
 *    total number of entries instead of O(n log n) per directory.
 *
 * @param root   Project root, used only for the top-level label
 *   ([File.name]) and for the working-tree walk in [writeTo]. The
 *   export path only uses it for the label, since [writePathsTo] does
 *   not touch the filesystem.
 * @param filter Optional filter applied to filesystem entries in
 *   [writeTo]. When null, every non-symlink child is rendered
 *   regardless of state. This is used by the export path, which has
 *   already filtered the entries and only needs the walker to render
 *   the paths it was given. In practice the export path calls
 *   [writePathsTo], not [writeTo], so a null filter on the export side
 *   only ever matters if a caller explicitly invokes [writeTo].
 * @param stubPaths Relative paths (relative to [root]) that should be
 *   rendered with the reference suffix. Filesystem paths in [writeTo]
 *   and path-list entries in [writePathsTo] are both checked against
 *   this set, so a caller that mixes the two (e.g. a partial render
 *   plus a full render) does not have to duplicate the set. Empty by
 *   default so callers that predate Tree v2 render exactly the same
 *   output as before.
 */
class DirectoryTree(
    private val root: File,
    private val filter: FileFilter? = null,
    private val stubPaths: Set<String> = emptySet()
) {
    /** Convenience API retained for callers that need an in-memory string. */
    fun build(): String = StringWriter().also { writer -> writeTo(writer) }.toString()

    /**
     * Streams the tree from the filesystem instead of allocating one
     * large String for large projects.
     *
     * Every entry is rendered as-is except symlinks, which are skipped
     * to avoid cycles and duplicated subtrees. When [filter] is non-null,
     * directories and files it excludes are skipped as well; the filter
     * is consulted with respectExcludedPaths set to false so that a
     * directory the user manually excluded still appears in the header.
     * Otherwise the user would not be able to see, and therefore
     * re-include, the excluded directory from the tree selector.
     *
     * The reference suffix is applied to any path present in
     * [stubPaths]. Because [writeTo] walks the real filesystem, a stub
     * that no longer exists on disk is silently absent from the header,
     * matching the export's policy of omitting missing stubs rather than
     * producing a stale marker.
     */
    fun writeTo(writer: Writer) {
        writer.write(HEADER)
        writer.write("└── ${root.name.ifBlank { "root" }}/\n")

        val stack = ArrayDeque<FileFrame>()
        stack.addLast(FileFrame(ROOT_PREFIX, listChildren(root)))

        // Iterative traversal avoids recursion limits for unusually deep
        // directory trees.
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.nextIndex >= frame.children.size) {
                stack.removeLast()
                continue
            }

            val index = frame.nextIndex++
            val child = frame.children[index]
            val isLast = index == frame.children.lastIndex
            val directory = child.isDirectory

            writer.write(frame.prefix)
            writer.write(if (isLast) LAST_CONNECTOR else BRANCH_CONNECTOR)
            writer.write(child.name)
            if (directory) {
                writer.write("/")
            } else {
                val relative = relativePathOf(child)
                if (relative != null && relative in stubPaths) {
                    writer.write(STUB_SUFFIX)
                }
            }
            writer.write("\n")

            if (directory) {
                val nextPrefix = frame.prefix + if (isLast) INDENT else CONTINUATION
                val children = listChildren(child)
                if (children.isNotEmpty()) {
                    stack.addLast(FileFrame(nextPrefix, children))
                }
            }
        }
    }

    /**
     * Builds the tree header from a flat list of relative paths.
     *
     * This is the rendering the export actually uses. The path list is
     * the set of files the user has included or marked as stubs; the
     * directory nodes are inferred from the path segments so that the
     * header shows exactly the directories that contain at least one
     * selected file, no more and no less.
     *
     * ### Path normalization
     *
     * Every input path is normalized through the same rules used by
     * [FilterConfig.normalizeRelativePath] before being split into
     * segments: backslashes become forward slashes, leading and
     * trailing slashes are stripped, and internal duplicate slashes are
     * collapsed. Empty paths after normalization are silently dropped,
     * as are paths whose segments contain a dot or a double dot.
     * Neither can be a legitimate descendant of the project root, and
     * both would produce a malformed header.
     *
     * ### Ordering
     *
     * The trie preserves insertion order within each directory level,
     * and the renderer applies a stable sort (directories first, then
     * by lowercased name, with the raw name as a case-sensitive
     * tiebreaker) before emitting. This matches the ordering used by
     * [writeTo] and by the tree selector itself, so the header's layout
     * does not depend on the order in which the export happened to
     * enumerate files. That is a detail the user should not have to
     * think about.
     *
     * ### Reference marker
     *
     * A path is rendered with the reference suffix when it is present
     * in [stubPaths]. Directory paths can be in [stubPaths] as well,
     * but the marker is applied only to files: a directory stub is a
     * structural hint that produces per-file stubs for its children,
     * and the header already shows the directory itself. Marking the
     * directory and each of its children would be redundant. The
     * children of a stubbed directory therefore carry the marker
     * individually, which is the granularity the model actually needs,
     * because it can see at a glance which files inside the folder are
     * stubs.
     *
     * @param writer target writer. The caller owns it and is
     *   responsible for closing it; this method never closes or flushes
     *   it, so several writePathsTo calls can be chained into a single
     *   stream.
     * @param relativePaths flat list of paths to render. Duplicates are
     *   collapsed by the trie (two paths that normalize to the same
     *   value produce a single leaf) so a caller that accidentally
     *   passes the same path twice does not produce a duplicated
     *   header entry.
     */
    fun writePathsTo(writer: Writer, relativePaths: Iterable<String>) {
        val snapshotRoot = TreeNode(root.name.ifBlank { "root" })
        relativePaths.forEach { rawPath ->
            val path = rawPath
                .replace('\\', '/')
                .trim('/')
                .takeIf(String::isNotBlank)
                ?: return@forEach
            val segments = path.split('/').filter(String::isNotBlank)
            if (segments.isEmpty()) return@forEach
            if (segments.any { it == "." || it == ".." }) return@forEach

            var node = snapshotRoot
            segments.forEachIndexed { index, segment ->
                if (index == segments.lastIndex) {
                    node = node.files.getOrPut(segment) {
                        TreeNode(segment).apply { fullPath = path }
                    }
                } else {
                    node = node.directories.getOrPut(segment) {
                        TreeNode(segment).apply {
                            fullPath = segments.subList(0, index + 1).joinToString("/")
                        }
                    }
                }
            }
        }

        writer.write(HEADER)
        writer.write("└── ${snapshotRoot.name}/\n")

        val stack = ArrayDeque<SnapshotFrame>()
        stack.addLast(SnapshotFrame(ROOT_PREFIX, snapshotEntries(snapshotRoot)))
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.nextIndex >= frame.entries.size) {
                stack.removeLast()
                continue
            }

            val index = frame.nextIndex++
            val entry = frame.entries[index]
            val isLast = index == frame.entries.lastIndex

            writer.write(frame.prefix)
            writer.write(if (isLast) LAST_CONNECTOR else BRANCH_CONNECTOR)
            writer.write(entry.name)
            if (entry.directory) {
                writer.write("/")
            } else if (entry.fullPath in stubPaths) {
                writer.write(STUB_SUFFIX)
            }
            writer.write("\n")

            if (entry.directory) {
                val children = snapshotEntries(entry.node)
                if (children.isNotEmpty()) {
                    val nextPrefix = frame.prefix + if (isLast) INDENT else CONTINUATION
                    stack.addLast(SnapshotFrame(nextPrefix, children))
                }
            }
        }
    }

    /**
     * Lists the children of [directory] that should appear under it in
     * [writeTo].
     *
     * Symlinks are always skipped, both to avoid cycles and to avoid
     * duplicating a subtree that is reachable through two paths. When
     * [filter] is non-null, its resolveMode is consulted for each
     * remaining child with respectExcludedPaths set to false, and only
     * entries whose state is [NodeState.INCLUDE] or [NodeState.STUB] are
     * kept: the header is supposed to reflect the selection the user
     * made in the tree selector, which is exactly the set of files that
     * will show up in the body. A null filter is treated as "render
     * everything", which is what the export path would want if it ever
     * called [writeTo] with a pre-filtered directory. In practice the
     * export path uses [writePathsTo] instead, so this case is mostly
     * about keeping the API honest.
     *
     * The result is sorted deterministically (directories first, then
     * case-insensitive name, with raw name as tiebreaker) so that the
     * header's layout is stable across devices and filesystems. Without
     * the explicit sort, the order would depend on the underlying
     * readdir implementation, which differs between ext4, FUSE, and
     * network mounts. That is enough to make the header look different
     * on two runs of the same project.
     */
    private fun listChildren(directory: File): List<File> =
        directory.listFiles()
            ?.asSequence()
            ?.filterNot(::isSymbolicLink)
            ?.filter { child ->
                val current = filter ?: return@filter true
                val relative = relativePathOf(child) ?: return@filter false
                val mode = current.resolveMode(
                    relativePath = relative,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    respectExcludedPaths = false
                )
                mode == NodeState.INCLUDE || mode == NodeState.STUB
            }
            ?.sortedWith(
                compareBy<File> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.name }
            )
            ?.toList()
            .orEmpty()

    /**
     * Returns the path of [file] relative to [root], normalized to the
     * same form the rest of the plugin uses.
     *
     * Used only by [listChildren] and [writeTo] to look up the entry's
     * [NodeState] with the filter and its membership in [stubPaths].
     * Returns null when the file is not under the root, which the
     * callers treat as "skip this entry". A file outside the root cannot
     * be a legitimate descendant of the tree, so rendering it would
     * produce a header that does not match the project.
     */
    private fun relativePathOf(file: File): String? = runCatching {
        val canonical = file.canonicalFile
        if (canonical == root) "" else {
            val relative = canonical.toPath().let { root.toPath().relativize(it) }
            FilterConfig.normalizeRelativePath(relative.toString())
        }
    }.getOrNull()

    /**
     * Returns true when [file] is a symlink.
     *
     * Errors are treated as "not a symlink", which means a broken
     * symlink that cannot be stat'd will be rendered as an entry with
     * the name it has on disk. That is slightly wrong, but strictly
     * better than dropping it: a user who can see the symlink in the
     * tree selector expects it to appear in the header too, and the
     * worst case (the body's file-read attempt fails) produces a normal
     * read error rather than a silently missing entry.
     */
    private fun isSymbolicLink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Flattens a [TreeNode] into the list of entries the renderer will
     * emit for it, sorted by the plugin's standard order.
     *
     * Directories come before files at every level, then each group is
     * sorted case-insensitively by name with the raw name as a
     * tiebreaker. That tiebreaker matters for two files whose names
     * differ only in case (README.md and readme.md): without it, the
     * sort would be nondeterministic on some devices and the header
     * would flip between the two orders across runs.
     *
     * The fullPath stored in each [SnapshotEntry] is what the renderer
     * uses to look up membership in [stubPaths]; it is constructed
     * while the trie is being built (see [writePathsTo]) and carried
     * through the flattening so the renderer does not have to walk the
     * tree again to reconstruct it.
     */
    private fun snapshotEntries(node: TreeNode): List<SnapshotEntry> = buildList(
        node.directories.size + node.files.size
    ) {
        node.directories.forEach { (name, child) ->
            add(SnapshotEntry(name, true, child, child.fullPath))
        }
        node.files.forEach { (name, child) ->
            add(SnapshotEntry(name, false, child, child.fullPath))
        }
    }.sortedWith(
        compareBy<SnapshotEntry> { !it.directory }
            .thenBy { it.name.lowercase() }
            .thenBy { it.name }
    )

    /**
     * Frame for the iterative traversal in [writeTo].
     *
     * Holds the prefix already accumulated for the current depth (so
     * the renderer does not have to reconstruct it on every step) and
     * the sorted children of the directory being rendered. Mutable
     * [nextIndex] lets the same frame be reused as the loop advances
     * through its children.
     */
    private class FileFrame(
        val prefix: String,
        val children: List<File>,
        var nextIndex: Int = 0
    )

    /**
     * Frame for the iterative traversal in [writePathsTo].
     *
     * Same shape as [FileFrame] but over [SnapshotEntry]s instead of
     * [File]s, because the path-list renderer never touches the
     * filesystem after the trie has been built.
     */
    private class SnapshotFrame(
        val prefix: String,
        val entries: List<SnapshotEntry>,
        var nextIndex: Int = 0
    )

    /**
     * One rendered entry in [writePathsTo]'s output.
     *
     * [fullPath] is the slash-separated path relative to the project
     * root, precomputed when the trie is built so that the renderer can
     * check membership in [stubPaths] without walking the trie again.
     * [directory] drives the trailing slash and the child expansion.
     */
    private data class SnapshotEntry(
        val name: String,
        val directory: Boolean,
        val node: TreeNode,
        val fullPath: String
    )

    /**
     * A single node in the in-memory trie used by [writePathsTo].
     *
     * [directories] and [files] are kept separate so that the sort
     * order (directories first) can be applied structurally instead of
     * as a per-entry check. Both are linkedMapOf to preserve the
     * insertion order of the first occurrence of each segment, which is
     * then overridden by the explicit sort in [snapshotEntries]. The
     * preservation is only there so that the sort is stable across two
     * calls that produce the same set of paths in different orders.
     *
     * [fullPath] is set during the trie build (see [writePathsTo]) and
     * is the path used both for the reference marker lookup and for
     * [SnapshotEntry].
     */
    private class TreeNode(val name: String) {
        val directories = linkedMapOf<String, TreeNode>()
        val files = linkedMapOf<String, TreeNode>()
        var fullPath: String = ""
    }

    private companion object {
        const val HEADER = "Directory structure:\n"
        const val ROOT_PREFIX = "    "
        const val BRANCH_CONNECTOR = "├── "
        const val LAST_CONNECTOR = "└── "
        const val CONTINUATION = "│   "
        const val INDENT = "    "

        /**
         * Suffix appended to a stub file's name in the header.
         *
         * Kept as a single constant so the two render paths cannot drift
         * apart, and so the exact string is trivially greppable when
         * updating the plugin's documentation or the model's prompt
         * examples. The leading space is part of the constant because
         * it is what separates the marker from the file name; a caller
         * that writes it manually would inevitably forget it on at
         * least one path.
         */
        const val STUB_SUFFIX = " [ref]"
    }
}