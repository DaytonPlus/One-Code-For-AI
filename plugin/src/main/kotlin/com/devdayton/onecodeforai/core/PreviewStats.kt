package com.devdayton.onecodeforai.core

/**
 * Aggregated per-extension export statistics.
 *
 * One instance per extension that appears in the tree selection. Populated
 * by [com.devdayton.onecodeforai.ui.ProjectTreeView.computeStatsFromTree]
 * and consumed by [PreviewStats.byExtension] to render the per-extension
 * breakdown in the configuration dialog.
 *
 * ### Mutable fields on purpose
 *
 * The three counters are `var` because the aggregator updates them in
 * place while it walks the tree, instead of building a new immutable
 * instance per file (which would allocate one object per entry on large
 * projects and defeat the point of aggregating). Once the walk finishes,
 * the map is copied into [PreviewStats] and never mutated again — the
 * mutation is confined to the scanner thread.
 *
 * ### How each counter is defined
 *
 *  - **[files]** — total number of files with this extension that the tree
 *    selector counted. Includes both INCLUDE and STUB entries; the two are
 *    indistinguishable from the extension's point of view. Excluded and
 *    locked files are not counted.
 *  - **[tokens]** — estimated number of tokens this extension contributes
 *    to the dump, using the ~4-bytes-per-token heuristic. STUB entries
 *    contribute zero (their content is never read); INCLUDE entries
 *    contribute their own body size divided by four.
 *  - **[bytes]** — total number of source bytes this extension contributes
 *    to the dump. Same rule as [tokens]: STUB entries contribute zero,
 *    INCLUDE entries contribute their own body size. This is **not** the
 *    number of bytes written to the dump, which also includes the per-file
 *    banner, the CRLF normalization and, for stubs, the marker line — but
 *    those are constant overhead per file and are handled by the summary
 *    line in [PreviewStats], not by the per-extension breakdown.
 *
 * @param extension the file extension in lowercase, without the leading
 *   dot. Files without an extension use the sentinel `"sin-ext"` (Spanish
 *   for "without extension"), which matches the label the export result
 *   panel already uses and keeps the extension grouping consistent across
 *   the UI even in English locales — an empty group label would look like
 *   a rendering bug.
 */
data class ExtensionStats(
    val extension: String,
    var files: Int = 0,
    var tokens: Long = 0L,
    var bytes: Long = 0L
)

/**
 * Aggregate statistics for the entire export selection.
 *
 * Produced by [com.devdayton.onecodeforai.ui.ProjectTreeView.computeStatsFromTree]
 * whenever the tree selection changes (initial render, a click on a node,
 * an expand/collapse that changes visibility of counted nodes) and passed
 * to the configuration dialog through the `onStatsChanged` callback.
 *
 * ### Immutability
 *
 * The instance itself is immutable (`val` fields, immutable list) so the
 * dialog can compare two snapshots cheaply or retain the previous one for
 * the "generation complete" summary. The list is built once at the end of
 * the walk; the dialog never has to defensively copy it.
 *
 * ### What the numbers mean (Tree v2)
 *
 * Since Tree v2 the tree selection is the source of truth for the export,
 * and it recognises three states that a counted file can be in:
 *
 *  - **INCLUDE** — the file's full content will be written to the dump.
 *    It contributes to [totalFiles], [totalBytes] and [totalTokens].
 *  - **STUB** ("reference only") — only the file's header and a short
 *    size marker will be written. It contributes to [totalFiles] and
 *    [stubFiles], but **not** to [totalBytes] or [totalTokens]:
 *    counting the source file's real size would be misleading, because
 *    the dump the user is about to generate will be much smaller than
 *    the source files it references.
 *  - **EXCLUDE** / **LOCKED** — the file is not part of the export.
 *    It contributes nothing.
 *
 * The dialog uses this distinction in two places:
 *
 *  - The result panel's summary line shows `N files (M refs) - ~X tokens
 *    - Y KB`, which is what the user wants to see after Generate: an
 *    honest estimate of the dump's real size, not the combined size of
 *    every file the tree happens to contain.
 *  - The tree section's own stats, if displayed, should use the same
 *    breakdown so the numbers are never inconsistent between the
 *    pre-generation and post-generation views.
 *
 * ### Token estimation
 *
 * [totalTokens] is an estimate, not a tokenizer-specific exact count. The
 * estimator is the same ~4-bytes-per-token heuristic used by the export
 * coordinator and the dump generator, so the number the user sees in the
 * dialog matches the number the result panel reports after generation.
 * The heuristic is deliberately simple: a real tokenizer would add a
 * dependency (and, on Android, a significant APK size cost) for a figure
 * that is by definition approximate — different model families tokenize
 * the same text differently.
 *
 * @param totalFiles total number of files the tree selector counted,
 *   including both INCLUDE and STUB entries. Excluded and locked files
 *   are not counted, so the figure matches what the user sees as
 *   "included" in the tree.
 * @param stubFiles number of entries among [totalFiles] that are
 *   reference-only (NodeState.STUB). Always `<=` [totalFiles]; the
 *   difference is the number of entries whose content is actually
 *   written to the dump.
 * @param totalTokens estimated total number of tokens the dump will
 *   contain, based on the included files only. Stubs contribute zero.
 * @param totalBytes total number of source bytes from included files
 *   only. Stubs contribute zero. This is an approximation of the dump's
 *   size: the dump itself also contains per-file banners and, for stubs,
 *   the marker lines, so the real output is slightly larger. The
 *   approximation is good enough for the "this will be about N KB" hint
 *   the dialog shows next to the Generate button.
 * @param byExtension per-extension breakdown, sorted by descending token
 *   count so the biggest contributors to the dump appear first. The
 *   list is empty when nothing is selected, never null, so callers can
 *   render it unconditionally.
 */
data class PreviewStats(
    val totalFiles: Int = 0,
    val stubFiles: Int = 0,
    val totalTokens: Long = 0L,
    val totalBytes: Long = 0L,
    val byExtension: List<ExtensionStats> = emptyList()
)