package com.devdayton.onecodeforai.android

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object ExportStateHolder {

    enum class State { IDLE, RUNNING, SUCCESS, ERROR }

    data class Snapshot(
        val state: State = State.IDLE,
        val message: String = "",
        val processed: Int = 0,
        val total: Int = 0,
        val bytesProcessed: Long = 0L,
        val bytesTotal: Long = 0L,
        val uri: Uri? = null,
        val fileName: String? = null,
        val lastAction: ExportAction? = null,
        val error: String? = null,
        val rootPath: String? = null,
        val filesCount: Int = 0,
        val totalBytes: Long = 0L,
        val estimatedTokens: Long = 0L
    ) {
        val isActive: Boolean get() = state != State.IDLE
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    fun subscribe(listener: (Snapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        val current = snapshot
        mainHandler.post { listener(current) }
        return { listeners.remove(listener) }
    }

    fun begin(action: ExportAction, rootPath: String) {
        publish(
            Snapshot(
                state = State.RUNNING,
                message = "Preparing…",
                lastAction = action,
                rootPath = rootPath
            )
        )
    }

    fun updateProgress(
        message: String,
        processed: Int,
        total: Int,
        bytesProcessed: Long,
        bytesTotal: Long
    ) {
        val current = snapshot
        if (current.state != State.RUNNING) return
        publish(
            current.copy(
                message = message,
                processed = processed,
                total = total,
                bytesProcessed = bytesProcessed,
                bytesTotal = bytesTotal
            )
        )
    }

    fun complete(uri: Uri, fileName: String, filesCount: Int, totalBytes: Long) {
        publish(
            snapshot.copy(
                state = State.SUCCESS,
                uri = uri,
                fileName = fileName,
                error = null,
                filesCount = filesCount,
                totalBytes = totalBytes,
                estimatedTokens = if (totalBytes <= 0L) 0L else (totalBytes + 3L) / 4L
            )
        )
    }

    fun fail(message: String) {
        publish(snapshot.copy(state = State.ERROR, error = message))
    }

    fun reset() {
        publish(Snapshot())
    }

    private fun publish(next: Snapshot) {
        snapshot = next
        val copy = listeners.toList()
        copy.forEach { listener -> mainHandler.post { listener(next) } }
    }
}