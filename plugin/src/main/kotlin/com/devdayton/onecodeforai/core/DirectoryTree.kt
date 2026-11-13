package com.devdayton.onecodeforai.core

import java.io.File
import java.io.StringWriter
import java.io.Writer
import java.util.ArrayDeque

class DirectoryTree(
    private val root: File,
    private val filter: FileFilter? = null,
    private val stubPaths: Set<String> = emptySet()
) {

    fun build(): String = StringWriter().also { writer -> writeTo(writer) }.toString()

    fun writeTo(writer: Writer) {
        writer.write(HEADER)
        writer.write("└── ${root.name.ifBlank { "root" }}/\n")

        val stack = ArrayDeque<FileFrame>()
        stack.addLast(FileFrame(ROOT_PREFIX, listChildren(root)))

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

    private fun relativePathOf(file: File): String? = runCatching {
        val canonical = file.canonicalFile
        if (canonical == root) "" else {
            val relative = canonical.toPath().let { root.toPath().relativize(it) }
            FilterConfig.normalizeRelativePath(relative.toString())
        }
    }.getOrNull()

    private fun isSymbolicLink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

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

    private data class SnapshotEntry(
        val name: String,
        val directory: Boolean,
        val node: TreeNode,
        val fullPath: String
    )

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

        const val STUB_SUFFIX = " [ref]"
    }
}