package com.devdayton.onecodeforai.core

import com.devdayton.onecodeforai.core.FilterConfig
import java.io.File
import java.nio.file.Files

/**
 * Single source of truth for deciding which project entries are part of an export.
 *
 * Both the project-tree preview and the dump generator use this class so that
 * the preview cannot silently apply different filtering rules from the export.
 *
 * The implementation is deliberately filesystem-based and does not follow
 * symbolic links. This prevents cycles and avoids exporting data outside the
 * selected project root.
 */
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
        if (this.config.respectGitignore) readIgnoreRules() else emptyList()
    }

    /**
     * Evaluates a filesystem entry. `respectExcludedPaths=false` is reserved
     * for the interactive tree, which must display manually excluded nodes so
     * that users can include them again.
     */
    fun include(file: File, respectExcludedPaths: Boolean = true): Boolean {
        if (isSymbolicLink(file) || (!file.isDirectory && !file.isFile)) return false
        val relativePath = relativePathOf(file) ?: return false
        return includePath(
            relativePath = relativePath,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isFile) file.length() else 0L,
            respectExcludedPaths = respectExcludedPaths
        )
    }

    /**
     * Applies the same rules to a path that is not necessarily present on disk.
     * This is used when exporting a Git tree at a branch, tag or commit.
     */
    fun includePath(
        relativePath: String,
        isDirectory: Boolean,
        sizeBytes: Long = 0L,
        respectExcludedPaths: Boolean = true
    ): Boolean {
        val path = FilterConfig.normalizeRelativePath(relativePath)
        if (path.isEmpty() || path.split('/').any { it == ".." || it == "." }) return false

        val segments = path.split('/')
        val name = segments.last()
        if (isDirectory) {
            if (name in excludedDirectoryNames) return false
            if (isIgnored(path, isDirectory = true)) return false
            if (respectExcludedPaths && isPathExcluded(path)) return false
            return true
        }

        if (sizeBytes < 0L || sizeBytes > config.maxFileSizeBytes) return false
        if (segments.dropLast(1).any { it in excludedDirectoryNames }) return false
        if (respectExcludedPaths && isPathExcluded(path)) return false

        val extension = name.substringAfterLast('.', "").lowercase()
        if (matchesAny(config.exclusions, path, name, extension)) return false
        if (matchesAny(config.inclusions, path, name, extension)) return true

        if (isIgnored(path, isDirectory = false)) return false
        if (config.excludeBinaries && extension in BINARY_EXTENSIONS) return false
        if (name.startsWith('.')) return false

        // Files without an extension remain eligible, matching the old behavior.
        return extension.isEmpty() || extension in TEXT_EXTENSIONS
    }

    /**
     * Returns included regular files in deterministic depth-first order.
     * Only one directory's children are materialized at a time.
     */
    fun walkIncludedFiles(): Sequence<File> = sequence {
        require(root.isDirectory) { "Project root is not a directory: ${root.path}" }

        suspend fun SequenceScope<File>.walk(directory: File) {
            val children = directory.listFiles()
                ?.asSequence()
                ?.filterNot(::isSymbolicLink)
                ?.sortedWith(
                    compareBy<File> { !it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                ?: return

            for (child in children) {
                if (child.isDirectory) {
                    if (include(child)) walk(child)
                } else if (include(child)) {
                    yield(child)
                }
            }
        }

        walk(root)
    }
    
    /**
     * Returns the number of files that would be included with the current
     * configuration. Used to drive the determinate progress bar in the
     * generation panel and the notification text.
     *
     * The walk is streamed: only one directory's children are held in memory
     * at a time. The cost is one extra filesystem traversal, which is far
     * cheaper than the actual export and lets us show accurate progress.
     */
    fun countEligibleFiles(): Int = walkIncludedFiles().count()

    /** Root .gitignore rules available for display in the configuration UI. */
    fun loadGitignoreRules(): List<String> {
        val gitignore = File(root, ".gitignore")
        if (!gitignore.isFile) return emptyList()

        return runCatching {
            gitignore.readLines(Charsets.UTF_8)
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') }
                .map { it.removePrefix("/").removeSuffix("/") }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun relativePathOf(file: File): String? = runCatching {
        val canonical = file.canonicalFile
        if (canonical == root) "" else {
            val relative = canonical.toPath().let { root.toPath().relativize(it) }
            FilterConfig.normalizeRelativePath(relative.toString())
        }
    }.getOrNull()

    private fun isPathExcluded(relativePath: String): Boolean {
        return config.excludedPaths.any { excluded ->
            val normalized = FilterConfig.normalizeRelativePath(excluded)
            relativePath == normalized || relativePath.startsWith("$normalized/")
        }
    }

    /**
     * .gitignore's last matching rule wins. Rules are loaded once per filter
     * instance, and ignored parent directories prevent traversal into children.
     */
    private fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean {
        val segments = relativePath.split('/').filter(String::isNotEmpty)
        if (segments.isEmpty()) return false

        // Check each ancestor first. An ignored directory cannot be bypassed
        // merely by matching a negated rule on a child path.
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

    private fun readIgnoreRules(): List<IgnoreRule> {
        val gitignore = File(root, ".gitignore")
        if (!gitignore.isFile) return emptyList()

        return runCatching {
            gitignore.readLines(Charsets.UTF_8)
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .mapNotNull(IgnoreRule::parse)
        }.getOrDefault(emptyList())
    }

    private fun matchesAny(
        rules: List<String>,
        path: String,
        fileName: String,
        extension: String
    ): Boolean = rules.any { rawRule ->
        val literalRule = rawRule.trim()
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

    companion object {
        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "tiff",
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "mp3", "wav", "ogg", "flac",
            "zip", "jar", "aar", "apk", "aab", "tar", "gz", "rar", "7z",
            "so", "dll", "dylib", "exe", "class", "dex",
            "ttf", "otf", "woff", "woff2", "pdf", "db", "sqlite", "lock", "bin"
        )

        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "xml", "toml", "gradle", "properties", "json",
            "yaml", "yml", "md", "txt", "pro", "cfg", "ini", "html", "css", "scss",
            "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h", "cpp",
            "hpp", "cs", "sh", "bat", "ps1", "sql", "proto", "graphql"
        )

        private fun globMatches(pattern: String, value: String): Boolean =
            globToRegex(pattern).matches(value)

        /** Supports *, ?, and ** without allowing * to cross path separators. */
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
                                // Git-style **/ also matches a file at the root of the pattern.
                                out.append("(?:.*/)?")
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
                    '.', '(', ')', '+', '|', '^', '$', '\\', '{', '}', '[', ']' ->
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
