package com.devdayton.onecodeforai

import java.io.File

class FileFilter(
    private val root: File,
    private val config: FilterConfig = FilterConfig()
) {
    private val excludedDirs = setOf(
        ".git", ".gradle", ".idea", "build", "out", "target",
        "node_modules", "dist", ".next", ".nuxt", "venv", ".venv",
        "__pycache__", ".mypy_cache", ".kotlin", ".cxx", "captures", ".externalNativeBuild"
    )

    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "tiff",
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "mp3", "wav", "ogg", "flac",
        "zip", "jar", "aar", "apk", "aab", "tar", "gz", "rar", "7z",
        "so", "dll", "dylib", "exe", "class", "dex",
        "ttf", "otf", "woff", "woff2", "pdf", "db", "sqlite", "lock", "bin"
    )

    private val ignoreRules: List<IgnoreRule> by lazy { if (config.respectGitignore) loadGitignore() else emptyList() }

    fun include(file: File): Boolean {
        if (file.isDirectory) {
            if (file.name in excludedDirs) return false
            if (isIgnored(file)) return false
            return true
        }
        if (!file.isFile) return false
        if (file.length() > config.maxSizeKb * 1024L) return false
        
        val ext = file.extension.lowercase()
        
        // 1. Exclusiones (Prioridad Máxima)
        if (config.exclusions.any { it.equals(ext, ignoreCase = true) || it == file.name }) return false
        
        // 2. Inclusiones
        if (config.inclusions.any { it.equals(ext, ignoreCase = true) || it == file.name }) return true
        
        // 3. Gitignore
        if (isIgnored(file)) return false
        
        // 4. Binarios por defecto
        if (config.excludeBinaries && ext in binaryExtensions) return false
        
        if (file.name.startsWith(".")) return false
        
        // Si llegó hasta aquí, solo incluirlo si tiene una extensión conocida
        val textExtensions = setOf("kt", "kts", "java", "xml", "toml", "gradle", "properties", "json", "yaml", "yml", "md", "txt", "pro", "cfg", "ini", "html", "css", "scss", "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "sh", "bat", "ps1", "sql", "proto", "graphql")
        if (ext.isNotEmpty() && ext !in textExtensions) return false
        
        return true
    }

    private fun isIgnored(file: File): Boolean {
        val rel = runCatching { file.relativeTo(root).path.replace('\\', '/') }.getOrNull() ?: return false
        return ignoreRules.any { it.matches(rel, file.isDirectory) }
    }

    fun loadGitignoreRules(): List<String> {
        val f = File(root, ".gitignore")
        if (!f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removeSuffix("/") }
    }

    private fun loadGitignore(): List<IgnoreRule> {
        val f = File(root, ".gitignore")
        if (!f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull(IgnoreRule::parse)
    }

    private data class IgnoreRule(val pattern: String, val dirOnly: Boolean, val anchored: Boolean) {
        fun matches(relativePath: String, isDirectory: Boolean): Boolean {
            if (dirOnly && !isDirectory) return false
            val path = relativePath.removeSuffix("/")
            if (anchored) return glob(pattern, path)
            return path.split('/').any { glob(pattern, it) } || glob(pattern, path)
        }
        companion object {
            fun parse(line: String): IgnoreRule? {
                var p = line
                if (p.isEmpty() || p.startsWith("!")) return null
                val dirOnly = p.endsWith("/")
                if (dirOnly) p = p.dropLast(1)
                val anchored = p.startsWith("/")
                if (anchored) p = p.drop(1)
                if (p.isEmpty()) return null
                return IgnoreRule(p, dirOnly, anchored)
            }
        }
    }
}

private fun glob(pattern: String, text: String): Boolean {
    if ('*' !in pattern && '?' !in pattern) return pattern == text
    val regex = buildString {
        append('^')
        pattern.forEach { c ->
            when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '+', '|', '^', '$', '\\', '{', '}', '[', ']' -> append('\\').append(c)
                else -> append(c)
            }
        }
        append('$')
    }
    return Regex(regex).matches(text)
}