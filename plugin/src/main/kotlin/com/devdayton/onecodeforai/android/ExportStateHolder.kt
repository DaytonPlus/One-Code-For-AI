package com.devdayton.onecodeforai.android

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide observable store for the state of the current export.
 *
 * The dialog, the notification, and any future entry point need to agree on
 * whether an export is running and what the last result was. Instead of
 * duplicating that logic, the coordinator writes into this singleton and
 * observers re-render whatever snapshot is currently published.
 *
 * Design notes:
 *  - [Snapshot] is immutable. Observers always receive a complete, consistent
 *    view; there are no partial updates to reconcile.
 *  - The publish/subscribe channel is serialized onto the main looper, so
 *    listeners are never invoked from the export worker thread.
 *  - Late subscribers immediately receive the current snapshot, which is what
 *    lets a dismissed-and-reopened dialog recover the in-flight progress or
 *    the previous result without extra plumbing.
 *  - The store is *not* persisted. Its lifetime is the process; a fresh launch
 *    always starts in [State.IDLE].
 */
object ExportStateHolder {

    /** Lifecycle of a single export run. */
    enum class State { IDLE, RUNNING, SUCCESS, ERROR }

    /**
     * Immutable view of the export state at a point in time.
     *
     * Fields fall into three groups and it is safe to leave the ones that do
     * not apply to the current [state] at their defaults:
     *  - Identity/context: [rootPath], [lastAction].
     *  - Progress (while [State.RUNNING]): [message], [processed]/[total],
     *    [bytesProcessed]/[bytesTotal].
     *  - Result (on [State.SUCCESS]): [uri], [fileName], [filesCount],
     *    [totalBytes], [estimatedTokens]. On [State.ERROR], [error] carries
     *    the failure reason.
     */
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
        /** `true` for any state other than [State.IDLE]; safe to gate "busy" UI on it. */
        val isActive: Boolean get() = state != State.IDLE
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Copy-on-write so subscriptions can be added or removed from any thread
     * while [publish] is iterating. The list is tiny (the dialog is the only
     * production subscriber), so the copy cost on mutation is negligible.
     */
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    /**
     * Most recently published snapshot. `@Volatile` guarantees visibility
     * across threads so a subscriber that arrives while an export is running
     * sees a fully published value rather than a torn one.
     */
    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    /**
     * Registers [listener] and schedules an immediate delivery of the current
     * snapshot on the main thread. Returns an unregister function; calling it
     * more than once is safe.
     *
     * Delivery is deferred so callers can subscribe from within
     * `show()`/`onCreate()` without the listener re-entering their own
     * initialization.
     */
    fun subscribe(listener: (Snapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        val current = snapshot
        mainHandler.post { listener(current) }
        return { listeners.remove(listener) }
    }

    /**
     * Marks the start of an export owned by [action] on [rootPath]. Resets
     * any prior progress or result fields so observers never see a mixed state
     * from the previous run.
     */
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

    /**
     * Updates the progress fields while keeping the rest of the snapshot.
     *
     * No-ops when the current state is not [State.RUNNING]: a progress tick
     * that arrives after [complete]/[fail]/[reset] must not resurrect a
     * finished run in the UI.
     */
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

    /**
     * Publishes the successful result of a run.
     *
     * [estimatedTokens] uses the ~4 UTF-8 bytes per token heuristic so the
     * result panel can show an order-of-magnitude figure without shipping a
     * tokenizer. Ceiling division ensures a non-empty file reports at least
     * one token.
     */
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

    /** Publishes a failure; [message] is shown verbatim by the UI. */
    fun fail(message: String) {
        publish(snapshot.copy(state = State.ERROR, error = message))
    }

    /** Returns the store to [State.IDLE], discarding any progress or result. */
    fun reset() {
        publish(Snapshot())
    }

    /**
     * Stores [next] and notifies every subscriber on the main thread.
     *
     * The snapshot is updated *before* dispatching so a listener that
     * re-reads [snapshot] from inside its callback sees the value it was just
     * handed. Listeners are snapshotted with `toList()` because
     * [CopyOnWriteArrayList] guarantees a consistent view even if a listener
     * unsubscribes during iteration.
     */
    private fun publish(next: Snapshot) {
        snapshot = next
        val copy = listeners.toList()
        copy.forEach { listener -> mainHandler.post { listener(next) } }
    }
}