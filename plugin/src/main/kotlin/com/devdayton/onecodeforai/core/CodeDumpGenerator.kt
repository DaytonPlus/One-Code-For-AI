package com.devdayton.onecodeforai.core

import java.io.File
import java.io.IOException
import java.io.Writer

fun interface FileProgressListener {
    fun onProgress(processed: Int, total: Int, currentPath: String, bytesProcessed: Long, totalBytes: Long)
}

class CodeDumpGenerator(
    private val root: File,
    private val nodes: Map<String, NodeState>,
    private val gitRef: String? = null,
    private val onProgress: FileProgressListener? = null,
    private val onReadError: ((String) -> Unit)? = null,
    private val totalLimitBytes: Long = 0L
) {
    companion object {
        private const val SEPARATOR = "================================================\n"
        private const val BUFFER_SIZE = 8 * 1024

        private const val STUB_MARKER_ESTIMATED_BYTES = 64L
    }

    @Throws(IOException::class)
    fun generateTo(writer: Writer) {
        require(root.isDirectory) { "Project root is not a directory: ${root.path}" }
        val selectedRef = gitRef?.trim()?.takeIf(String::isNotEmpty)
        if (selectedRef == null) generateWorkingTree(writer) else generateGitSnapshot(writer, selectedRef)
    }

    private fun generateWorkingTree(writer: Writer) {
        ensureActive()

        val includedPaths = nodes.entries
            .filter { it.value == NodeState.INCLUDE || it.value == NodeState.STUB }
            .map { it.key }
        DirectoryTree(root,  null, stubPaths = stubPathsFromNodes())
            .writePathsTo(writer, includedPaths)
        ensureActive()

        val entries = nodes.entries.toList()
        val total = entries.size
        val totalBytes = entries.sumOf { (path, state) ->
            val file = File(root, path)
            when (state) {
                NodeState.INCLUDE -> file.length()
                NodeState.STUB -> STUB_MARKER_ESTIMATED_BYTES
                NodeState.EXCLUDE, NodeState.LOCKED -> 0L
            }
        }
        ensureActive()

        var processed = 0
        var bytesAccum = 0L
        var truncated = false
        var skippedFiles = 0

        entries.forEach { (path, state) ->
            ensureActive()

            when (state) {
                NodeState.EXCLUDE, NodeState.LOCKED -> {

                    processed++
                }

                NodeState.STUB -> {
                    val file = File(root, path)
                    val stubBytes = estimateStubBytes(file)
                    if (totalLimitBytes > 0L && bytesAccum + stubBytes > totalLimitBytes) {
                        truncated = true
                        skippedFiles++
                        processed++
                        return@forEach
                    }
                    writeFileHeader(writer, path)
                    writeStubMarker(writer, file)
                    bytesAccum += stubBytes
                    processed++
                    onProgress?.onProgress(processed, total, path, bytesAccum, totalBytes)
                }

                NodeState.INCLUDE -> {
                    val file = File(root, path)
                    if (totalLimitBytes > 0L && bytesAccum + file.length() > totalLimitBytes) {
                        truncated = true
                        skippedFiles++
                        processed++
                        return@forEach
                    }
                    writeFileHeader(writer, path)
                    try {
                        file.bufferedReader(Charsets.UTF_8, BUFFER_SIZE).use { reader ->
                            copyNormalizedText(reader, writer)
                        }
                    } catch (interrupted: InterruptedException) {

                        throw interrupted
                    } catch (error: Exception) {
                        onReadError?.invoke(path)
                        writer.write("[File read error: ${error.message ?: error.javaClass.simpleName}]\n")
                    }
                    bytesAccum += file.length()
                    processed++
                    onProgress?.onProgress(processed, total, path, bytesAccum, totalBytes)
                }
            }
        }
        if (truncated) writeTruncationNotice(writer, skippedFiles)
    }

    private fun generateGitSnapshot(writer: Writer, ref: String) {
        GitUtils.withSnapshot(root, ref) { snapshot ->
            ensureActive()

            val includedPaths = nodes.entries
                .filter { it.value == NodeState.INCLUDE || it.value == NodeState.STUB }
                .map { it.key }
            val snapshotFilesByPath = snapshot.listFiles().associateBy { it.path }

            DirectoryTree(root,  null, stubPaths = stubPathsFromNodes())
                .writePathsTo(writer, includedPaths)
            ensureActive()

            val entries = nodes.entries.toList()
            val total = entries.size
            val totalBytes = entries.sumOf { (path, state) ->
                when (state) {
                    NodeState.INCLUDE -> snapshotFilesByPath[path]?.size ?: 0L
                    NodeState.STUB -> STUB_MARKER_ESTIMATED_BYTES
                    NodeState.EXCLUDE, NodeState.LOCKED -> 0L
                }
            }

            var processed = 0
            var bytesAccum = 0L
            var truncated = false
            var skippedFiles = 0

            entries.forEach { (path, state) ->
                ensureActive()

                when (state) {
                    NodeState.EXCLUDE, NodeState.LOCKED -> {
                        processed++
                    }

                    NodeState.STUB -> {
                        val size = snapshotFilesByPath[path]?.size ?: 0L
                        val stubBytes = estimateStubBytesBySize(size)
                        if (totalLimitBytes > 0L && bytesAccum + stubBytes > totalLimitBytes) {
                            truncated = true
                            skippedFiles++
                            processed++
                            return@forEach
                        }
                        writeFileHeader(writer, path)
                        writeStubMarkerBySize(writer, size)
                        bytesAccum += stubBytes
                        processed++
                        onProgress?.onProgress(processed, total, path, bytesAccum, totalBytes)
                    }

                    NodeState.INCLUDE -> {
                        val size = snapshotFilesByPath[path]?.size ?: 0L
                        if (totalLimitBytes > 0L && bytesAccum + size > totalLimitBytes) {
                            truncated = true
                            skippedFiles++
                            processed++
                            return@forEach
                        }
                        writeFileHeader(writer, path)
                        try {
                            if (!snapshot.writeFileContent(path, writer)) {
                                onReadError?.invoke(path)
                                writer.write("[Git read error: ${path}]\n")
                            }
                        } catch (interrupted: InterruptedException) {
                            throw interrupted
                        }
                        bytesAccum += size
                        processed++
                        onProgress?.onProgress(processed, total, path, bytesAccum, totalBytes)
                    }
                }
            }
            if (truncated) writeTruncationNotice(writer, skippedFiles)
        }
    }

    private fun writeFileHeader(writer: Writer, relativePath: String) {
        writer.write("\n")
        writer.write(SEPARATOR)
        writer.write("FILE: ")
        writer.write(relativePath)
        writer.write("\n")
        writer.write(SEPARATOR)
    }

    private fun writeStubMarker(writer: Writer, file: File) {
        if (!file.exists()) {

            return
        }
        writeStubMarkerBySize(writer, file.length())
    }

    private fun writeStubMarkerBySize(writer: Writer, sizeBytes: Long) {
        writer.write("[content omitted: ${formatSize(sizeBytes)} — reference only]\n")
    }

    private fun estimateStubBytes(file: File): Long {
        if (!file.exists()) return 0L
        return estimateStubBytesBySize(file.length())
    }

    private fun estimateStubBytesBySize(sizeBytes: Long): Long {

        return STUB_MARKER_ESTIMATED_BYTES + sizeBytes.coerceAtMost(0L)
    }

    private fun writeTruncationNotice(writer: Writer, skippedFiles: Int) {
        writer.write("\n")
        writer.write(SEPARATOR)
        writer.write(
            "[Export truncated: total size limit reached. " +
                "$skippedFiles file(s) not included.]\n"
        )
        writer.write(SEPARATOR)
    }

    private fun stubPathsFromNodes(): Set<String> =
        nodes.entries.filter { it.value == NodeState.STUB }.map { it.key }.toSet()

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Dump generation was interrupted.")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun copyNormalizedText(reader: java.io.Reader, writer: Writer) {
        val buffer = CharArray(BUFFER_SIZE)
        var pendingCarriageReturn = false
        var lastWasNewline = false
        while (true) {

            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Dump generation was interrupted.")
            }
            val count = reader.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val char = buffer[index]
                if (pendingCarriageReturn) {
                    writer.write('\n'.code)
                    lastWasNewline = true
                    pendingCarriageReturn = false
                    if (char == '\n') continue
                }
                if (char == '\r') pendingCarriageReturn = true
                else { writer.write(char.code); lastWasNewline = char == '\n' }
            }
        }
        if (pendingCarriageReturn) { writer.write('\n'.code); lastWasNewline = true }
        if (!lastWasNewline) writer.write('\n'.code)
    }
}