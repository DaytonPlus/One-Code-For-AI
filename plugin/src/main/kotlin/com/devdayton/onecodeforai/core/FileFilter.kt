package com.devdayton.onecodeforai.core

import com.devdayton.onecodeforai.core.FilterConfig
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class FileFilter(
    root: File,
    config: FilterConfig = FilterConfig()
) {
    val root: File = root.canonicalFile
    val config: FilterConfig = config.normalized()

    private val excludedDirectoryNames = setOf(
        ".git", ".gradle", ".idea", "build", "out", "target",
        "node_modules", "dist", ".next", ".nuxt", "venv", ".venv",
        "__pycache__", ".mypy_cache", ".kotlin", ".cxx", "captures",
        ".externalNativeBuild"
    )

    private val ignoreRules: List<IgnoreRule> by lazy(LazyThreadSafetyMode.NONE) {
        if (this.config.respectGitignore) loadIgnoreRules() else emptyList()
    }

    fun hardExcludedDirectories(): Set<String> = excludedDirectoryNames

    fun binaryExtensions(): Set<String> = BINARY_EXTENSIONS

    fun resolveMode(
        relativePath: String,
        isDirectory: Boolean,
        sizeBytes: Long = 0L,
        respectExcludedPaths: Boolean = true,
        respectHardExcludedAncestors: Boolean = true
    ): NodeState {
        val path = FilterConfig.normalizeRelativePath(relativePath)
        if (path.isEmpty() || path.split('/').any { it == ".." || it == "." }) {
            return NodeState.EXCLUDE
        }

        val segments = path.split('/')
        val name = segments.last()

        if (isDirectory) {
            if (name in excludedDirectoryNames) return NodeState.EXCLUDE
            if (config.enableStubPatterns &&
                matchesAny(config.stubPatterns, path, name, extension = "")) return NodeState.STUB
            if (config.enableInclusions &&
                matchesAny(config.inclusions, path, name, extension = "")) return NodeState.INCLUDE
            if (config.enableExclusions &&
                matchesAny(config.exclusions, path, name, extension = "")) return NodeState.EXCLUDE
            if (isIgnored(path, isDirectory = true)) return NodeState.EXCLUDE
            return NodeState.INCLUDE
        }

        if (sizeBytes < 0L || sizeBytes > config.maxFileSizeBytes) return NodeState.EXCLUDE

        if (respectHardExcludedAncestors &&
            segments.dropLast(1).any { it in excludedDirectoryNames }) return NodeState.EXCLUDE

        val extension = name.substringAfterLast('.', "").lowercase()

        if (config.enableStubPatterns &&
            matchesAny(config.stubPatterns, path, name, extension)) return NodeState.STUB
        if (config.enableInclusions &&
            matchesAny(config.inclusions, path, name, extension)) return NodeState.INCLUDE
        if (config.enableExclusions &&
            matchesAny(config.exclusions, path, name, extension)) return NodeState.EXCLUDE

        if (isIgnored(path, isDirectory = false)) return NodeState.EXCLUDE
        if (config.excludeBinaries && extension in BINARY_EXTENSIONS) return NodeState.EXCLUDE
        if (name.startsWith('.')) return NodeState.EXCLUDE

        return if (extension.isEmpty() || extension in TEXT_EXTENSIONS) {
            NodeState.INCLUDE
        } else {
            NodeState.EXCLUDE
        }
    }

    fun include(file: File, respectExcludedPaths: Boolean = true): Boolean {
        if (isSymbolicLink(file) || (!file.isDirectory && !file.isFile)) return false
        val relativePath = relativePathOf(file) ?: return false
        return resolveMode(
            relativePath = relativePath,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isFile) file.length() else 0L,
            respectExcludedPaths = respectExcludedPaths
        ) == NodeState.INCLUDE
    }

    fun includePath(
        relativePath: String,
        isDirectory: Boolean,
        sizeBytes: Long = 0L,
        respectExcludedPaths: Boolean = true
    ): Boolean = resolveMode(relativePath, isDirectory, sizeBytes, respectExcludedPaths) == NodeState.INCLUDE

    fun walkIncludedAndStubbed(): Sequence<Pair<File, NodeState>> = sequence {
        require(root.isDirectory) { "Project root is not a directory: ${root.path}" }
        ensureActive()

        suspend fun SequenceScope<Pair<File, NodeState>>.walk(directory: File) {
            ensureActive()
            val children = directory.listFiles()
                ?.asSequence()
                ?.filterNot(::isSymbolicLink)
                ?.sortedWith(
                    compareBy<File> { !it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                ?: return

            for (child in children) {
                ensureActive()
                val relativePath = relativePathOf(child) ?: continue
                if (child.isDirectory) {
                    when (resolveMode(relativePath, isDirectory = true, sizeBytes = 0L)) {
                        NodeState.INCLUDE, NodeState.STUB -> walk(child)
                        NodeState.EXCLUDE, NodeState.LOCKED -> Unit
                    }
                } else {
                    when (resolveMode(relativePath, isDirectory = false, sizeBytes = child.length())) {
                        NodeState.INCLUDE -> yield(child to NodeState.INCLUDE)
                        NodeState.STUB -> yield(child to NodeState.STUB)
                        NodeState.EXCLUDE, NodeState.LOCKED -> Unit
                    }
                }
            }
        }

        walk(root)
    }

    fun walkIncludedFiles(): Sequence<File> =
        walkIncludedAndStubbed().filter { it.second == NodeState.INCLUDE }.map { it.first }

    fun countEligibleFiles(): Int = walkIncludedAndStubbed().count()

    fun loadGitignoreRules(): List<String> =
        readGitignoreEntry()?.displayRules ?: emptyList()

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("File walk was interrupted.")
        }
    }

    private fun relativePathOf(file: File): String? = runCatching {
        val canonical = file.canonicalFile
        if (canonical == root) "" else {
            val relative = canonical.toPath().let { root.toPath().relativize(it) }
            FilterConfig.normalizeRelativePath(relative.toString())
        }
    }.getOrNull()

    private fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean {
        val segments = relativePath.split('/').filter(String::isNotEmpty)
        if (segments.isEmpty()) return false

        for (index in segments.indices) {
            val candidate = segments.take(index + 1).joinToString("/")
            val candidateIsDirectory = index < segments.lastIndex || isDirectory
            if (isIgnoredPath(candidate, candidateIsDirectory)) return true
        }
        return false
    }

    private fun isIgnoredPath(path: String, isDirectory: Boolean): Boolean {
        var ignored = false
        ignoreRules.forEach { rule ->
            if (rule.matches(path, isDirectory)) ignored = !rule.negated
        }
        return ignored
    }

    private fun loadIgnoreRules(): List<IgnoreRule> =
        readGitignoreEntry()?.parsedRules ?: emptyList()

    private fun readGitignoreEntry(): GitignoreCacheEntry? {
        val gitignore = File(root, ".gitignore")
        if (!gitignore.isFile) return null

        val mtime = gitignore.lastModified()
        val key = gitignore.absolutePath

        gitignoreCache[key]?.let { entry ->
            if (entry.mtime == mtime) return entry
        }

        val lines = runCatching { gitignore.readLines(Charsets.UTF_8) }
            .getOrDefault(emptyList())
        val filtered = lines
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
        val parsedRules = filtered.mapNotNull(IgnoreRule::parse)

        val rawRules = filtered
            .filter { !it.startsWith('!') }
            .map { it.removePrefix("/").removeSuffix("/") }
            .distinct()

        val displayRules = filtered.distinct()

        val entry = GitignoreCacheEntry(mtime, rawRules, displayRules, parsedRules)
        if (gitignoreCache.size >= GITIGNORE_CACHE_MAX_ENTRIES) {
            gitignoreCache.keys.firstOrNull()?.let { gitignoreCache.remove(it) }
        }
        gitignoreCache[key] = entry
        return entry
    }

    private fun matchesAny(
        rules: List<String>,
        path: String,
        fileName: String,
        extension: String
    ): Boolean = rules.any { rawRule ->

        val literalRule = rawRule.trim().removeSuffix("/")
        if (literalRule.isEmpty()) return@any false
        val extensionRule = literalRule.removePrefix(".")
        literalRule.equals(fileName, ignoreCase = true) ||
            literalRule.equals(path, ignoreCase = true) ||
            extensionRule.equals(extension, ignoreCase = true) ||
            globMatches(literalRule, fileName) ||
            globMatches(literalRule, path)
    }

    private fun isSymbolicLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private data class IgnoreRule(
        val source: String,
        val negated: Boolean,
        val directoryOnly: Boolean,
        val anchored: Boolean,
        val hasSlash: Boolean,
        val regex: Regex
    ) {
        fun matches(path: String, isDirectory: Boolean): Boolean {
            if (directoryOnly && !isDirectory) return false
            return if (!anchored && !hasSlash) {
                path.split('/').any(regex::matches)
            } else {
                regex.matches(path)
            }
        }

        companion object {
            fun parse(line: String): IgnoreRule? {
                var pattern = line.trim()
                if (pattern.isEmpty() || pattern == "!") return null

                val negated = pattern.startsWith('!')
                if (negated) pattern = pattern.drop(1)

                val directoryOnly = pattern.endsWith('/')
                pattern = pattern.removeSuffix("/")

                val anchored = pattern.startsWith('/')
                pattern = pattern.removePrefix("/")
                if (pattern.isEmpty()) return null

                val hasSlash = '/' in pattern
                return IgnoreRule(
                    source = line,
                    negated = negated,
                    directoryOnly = directoryOnly,
                    anchored = anchored,
                    hasSlash = hasSlash,
                    regex = globToRegex(pattern)
                )
            }
        }
    }

    private data class GitignoreCacheEntry(
        val mtime: Long,
        val rawRules: List<String>,
        val displayRules: List<String>,
        val parsedRules: List<IgnoreRule>
    )

    companion object {
        private val gitignoreCache = ConcurrentHashMap<String, GitignoreCacheEntry>()

        private const val GITIGNORE_CACHE_MAX_ENTRIES = 32

        fun invalidateGitignoreCache(root: File) {
            val gitignore = File(root.canonicalFile, ".gitignore")
            gitignoreCache.remove(gitignore.absolutePath)
        }

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff",
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "mp3", "wav", "ogg", "flac",
            "zip", "jar", "aar", "apk", "aab", "tar", "gz", "rar", "7z",
            "so", "dll", "dylib", "exe", "class", "dex",
            "ttf", "otf", "woff", "woff2", "pdf", "db", "sqlite", "bin"
        )

        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "xml", "toml", "gradle", "properties", "json",
            "yaml", "yml", "md", "txt", "pro", "cfg", "ini", "html", "css", "scss",
            "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h", "cpp",
            "hpp", "cs", "sh", "bat", "ps1", "sql", "proto", "graphql", "svg"
        )

        private fun globMatches(pattern: String, value: String): Boolean =
            globToRegex(pattern).matches(value)

        private fun globToRegex(pattern: String): Regex {
            val out = StringBuilder("^")
            var index = 0
            while (index < pattern.length) {
                val char = pattern[index]
                when (char) {
                    '*' -> {
                        val doubleStar = index + 1 < pattern.length && pattern[index + 1] == '*'
                        if (doubleStar) {
                            val followedBySlash = index + 2 < pattern.length && pattern[index + 2] == '/'
                            if (followedBySlash) {

                                out.append("(?:^|.*/)")
                                index += 2
                            } else {
                                out.append(".*")
                                index++
                            }
                        } else {
                            out.append("[^/]*")
                        }
                    }
                    '?' -> out.append("[^/]")
                    '[' -> {

                        out.append('[')
                        if (index + 1 < pattern.length && pattern[index + 1] == '!') {
                            out.append('^')
                            index++
                        }
                    }
                    ']' -> out.append(']')
                    '.', '(', ')', '+', '|', '^', '$', '\\', '{', '}' ->
                        out.append('\\').append(char)
                    else -> out.append(char)
                }
                index++
            }
            out.append('$')
            return Regex(out.toString())
        }
    }
}