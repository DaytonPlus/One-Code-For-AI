package com.devdayton.onecodeforai

import java.io.File

/** Árbol al estilo gitingest / code2prompt con ├── y └──. */
class DirectoryTree(
    private val root: File,
    private val filter: FileFilter,
) {
    fun build(): String {
        val sb = StringBuilder()
        sb.appendLine("Directory structure:")
        sb.appendLine("└── ${root.name.ifBlank { "root" }}/")
        appendChildren(sb, listChildren(root), prefix = "    ")
        return sb.toString()
    }

    private fun appendChildren(sb: StringBuilder, children: List<File>, prefix: String) {
        children.forEachIndexed { index, child ->
            val isLast = index == children.lastIndex
            val connector = if (isLast) "└── " else "├── "
            val suffix = if (child.isDirectory) "/" else ""
            sb.appendLine("$prefix$connector${child.name}$suffix")
            if (child.isDirectory) {
                val next = prefix + if (isLast) "    " else "│   "
                appendChildren(sb, listChildren(child), next)
            }
        }
    }

    private fun listChildren(dir: File): List<File> =
        dir.listFiles()
            ?.filter { filter.include(it) }
            ?.sortedWith(
                compareBy<File> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
            ?: emptyList()
}