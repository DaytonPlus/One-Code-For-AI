package com.devdayton.onecodeforai

import java.io.File
import java.util.concurrent.TimeUnit

object GitUtils {
    private const val TIMEOUT_SECONDS = 5L

    fun getCurrentBranch(dir: File): String? = runGit(dir, "rev-parse", "--abbrev-ref", "HEAD")?.trim()?.takeIf { it != "HEAD" }
    fun getCurrentCommit(dir: File): String? = runGit(dir, "rev-parse", "--short", "HEAD")?.trim()
    fun getBranches(dir: File): List<String> = runGit(dir, "branch", "--format=%(refname:short)")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    fun getFileContent(dir: File, ref: String, path: String): String? = runGit(dir, "show", "$ref:$path")

    private fun runGit(dir: File, vararg args: String): String? {
        return try {
            val cmd = mutableListOf("git", "-C", dir.absolutePath).apply { addAll(args) }
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }
}