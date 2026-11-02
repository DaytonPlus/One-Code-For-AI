package com.devdayton.onecodeforai.core

import com.devdayton.onecodeforai.core.FileFilter
import java.io.File
import java.io.StringWriter
import java.io.Writer
import java.util.ArrayDeque

/** Writes the familiar gitingest/code2prompt directory-tree representation. */
class DirectoryTree(
    private val root: File,
    private val filter: FileFilter
) {
    /** Convenience API retained for callers that need an in-memory string. */
    fun build(): String = StringWriter().also { writer -> writeTo(writer) }.toString()

    /** Streams the tree instead of allocating one large String for large projects. */
    fun writeTo(writer: Writer) {
        writer.write(HEADER)
        writer.write("└── ${root.name.ifBlank { "root" }}/\n")

        val stack = ArrayDeque<FileFrame>()
        stack.addLast(FileFrame(ROOT_PREFIX, listChildren(root)))

        // Iterative traversal avoids recursion limits for unusually deep directory trees.
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
            if (directory) writer.write("/")
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
     * Builds a tree for a Git snapshot. The path list contains files only;
     * directory nodes are inferred from path segments.
     */
    fun writePathsTo(writer: Writer, relativePaths: Iterable<String>) {
        val snapshotRoot = TreeNode(root.name.ifBlank { "root" })
        relativePaths.forEach { rawPath ->
            val path = rawPath.replace('\\', '/').trim('/')
                .takeIf(String::isNotBlank) ?: return@forEach
            var node = snapshotRoot
            val segments = path.split('/').filter(String::isNotBlank)
            segments.forEachIndexed { index, segment ->
                node = if (index == segments.lastIndex) {
                    node.files.getOrPut(segment) { TreeNode(segment) }
                } else {
                    node.directories.getOrPut(segment) { TreeNode(segment) }
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
            if (entry.directory) writer.write("/")
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

    private fun listChildren(directory: File): List<File> =
        directory.listFiles()
            ?.filter { child -> filter.include(child) }
            ?.sortedWith(
                compareBy<File> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.name }
            )
            .orEmpty()

    private fun snapshotEntries(node: TreeNode): List<SnapshotEntry> = buildList(
        node.directories.size + node.files.size
    ) {
        node.directories.forEach { (name, child) -> add(SnapshotEntry(name, true, child)) }
        node.files.forEach { (name, child) -> add(SnapshotEntry(name, false, child)) }
    }.sortedWith(
        compareBy<SnapshotEntry> { !it.directory }
            .thenBy { it.name.lowercase() }
            .thenBy { it.name }
    )

    private class FileFrame(
        val prefix: String,
        val children: List<File>,
        var nextIndex: Int = 0
    )

    private class SnapshotFrame(
        val prefix: String,
        val entries: List<SnapshotEntry>,
        var nextIndex: Int = 0
    )

    private data class SnapshotEntry(val name: String, val directory: Boolean, val node: TreeNode)

    private class TreeNode(val name: String) {
        val directories = linkedMapOf<String, TreeNode>()
        val files = linkedMapOf<String, TreeNode>()
    }

    private companion object {
        const val HEADER = "Directory structure:\n"
        const val ROOT_PREFIX = "    "
        const val BRANCH_CONNECTOR = "├── "
        const val LAST_CONNECTOR = "└── "
        const val CONTINUATION = "│   "
        const val INDENT = "    "
    }
}
