package com.devdayton.onecodeforai.core

import com.devdayton.onecodeforai.core.FilterConfig
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for deciding which project entries are part of
 * an export and in what role.
 *
 * Both the project-tree preview and the dump generator use this class so
 * that the preview cannot silently apply different filtering rules from
 * the export.
 *
 * The implementation is deliberately filesystem-based and does not follow
 * symbolic links. This prevents cycles and avoids exporting data outside
 * the selected project root.
 *
 * ### Tree v2: from "included or not" to a four-state model
 *
 * Prior to Tree v2 this class answered a single yes/no question —
 * "should this entry be included?" — and every consumer had to reason
 * about that boolean. Tree v2 replaced that with an explicit
 * [NodeState] answer from [resolveMode], so the same object tells the
 * caller *how* an entry participates in the export:
 *
 *  - **[NodeState.INCLUDE]** — the file's full content is written to the
 *    dump. This is the only state the old boolean API could represent.
 *  - **[NodeState.STUB]** — the file's header and a size marker are
 *    written, but its content is not read. Used for lockfiles, generated
 *    code, and machine-local configuration whose existence matters to
 *    the consuming model but whose body is noise.
 *  - **[NodeState.EXCLUDE]** — the file is not written at all.
 *  - **[NodeState.LOCKED]** — the file is present in the tree as a
 *    disabled, non-interactive node. Applied by the tree scanner to
 *    entries that are excluded by structural rules the user has not
 *    opted to see (the hard-excluded directory names and binary
 *    extensions). Locking is decided by the tree scanner, not by this
 *    class; [resolveMode] never returns LOCKED on its own. This class
 *    only owns the *rule-based* answer for an entry the scanner has
 *    already decided to show.
 *
 * The old boolean entry points — [include] and [includePath] — are
 * retained as thin wrappers that return `resolveMode(...) == INCLUDE`.
 * They exist so that legacy call sites keep compiling; new code should
 * call [resolveMode] (or [walkIncludedAndStubbed]) and inspect the
 * returned state directly.
 *
 * ### Section enable switches (Tree v2, section-toggle patch)
 *
 * The three user rule lists — inclusions, exclusions and stub patterns
 * — are each guarded by a boolean flag on [FilterConfig]
 * ([FilterConfig.enableInclusions], [FilterConfig.enableExclusions],
 * [FilterConfig.enableStubPatterns]). The flags are what powers the
 * section checkboxes in the configuration dialog: a user can
 * temporarily disable a rule list without destroying it, so toggling
 * the section off and back on restores the same rules.
 *
 * In [resolveMode] the flag is evaluated **before** the corresponding
 * list. When the flag is `false`, the branch is skipped entirely, as if
 * the list were empty, and the next rule in the chain is evaluated.
 * This is what makes "disabled section" behave like "the user removed
 * every rule from that section" without the destructive side effect of
 * actually deleting the entries — the config still carries the rules,
 * but this class ignores them, and the user can turn the section back
 * on without losing anything.
 *
 * The flags are **not** aliases for "the list is empty". A section can
 * be enabled with an empty list (the filter then matches nothing) or
 * disabled with a populated list (the filter then ignores the list
 * entirely). The distinction is what makes the toggle reversible across
 * a save/load cycle; see the [FilterConfig] KDoc, section *Section
 * enable switches*, for the full rationale.
 *
 * ### Priority order in [resolveMode]
 *
 * The rules are evaluated in a fixed order that mirrors the existing
 * inclusions > exclusions precedence and adds the stub layer on top:
 *
 *  1. [FilterConfig.enableStubPatterns] && [FilterConfig.stubPatterns]
 *     match → `STUB`
 *  2. [FilterConfig.enableInclusions] && [FilterConfig.inclusions]
 *     match → `INCLUDE`
 *  3. [FilterConfig.enableExclusions] && [FilterConfig.exclusions]
 *     match → `EXCLUDE`
 *  4. `.gitignore` match → `EXCLUDE` (only if respecting)
 *  5. per-file size overflow → `EXCLUDE` (only if limiting)
 *  6. binary extension → `EXCLUDE` (only if excluding)
 *  7. hidden file (dot-prefixed) → `EXCLUDE`
 *  8. text extension or no extension → `INCLUDE`
 *  9. otherwise → `EXCLUDE`
 *
 * Stub patterns precede inclusions on purpose: a file the user marked
 * as reference-only must not be silently promoted back to a full
 * include by a broad inclusion pattern. Per-path overrides in
 * [TreeOverrides] are applied **before** this whole chain by the
 * caller (see the tree scanner); this class is not aware of them and
 * must not be, so that a profile's rules stay independent of any
 * single project's manual choices.
 *
 * Each of the three section flags short-circuits its corresponding
 * branch: when the flag is `false`, that branch is skipped and the next
 * rule in the chain is evaluated. This is what allows a user to, for
 * example, disable exclusions and still have the inclusion list and the
 * `.gitignore` rules applied, without any of the disabled section's
 * entries being silently lost.
 *
 * ### Hard-excluded directories and binary extensions are the tree's
 * ### responsibility, not this class's
 *
 * The hard-excluded directory set and the binary extension set are used
 * by the tree scanner to decide which entries become LOCKED. This class
 * only knows them as exclusions that may be reached through rules (see
 * [hardExcludedDirectories] and [binaryExtensions]). A caller that
 * wants to know whether an entry would be locked must ask the scanner,
 * not this filter.
 *
 * ### Performance and caching of `.gitignore`
 *
 * The `.gitignore` file is read and parsed at most once per
 * `(absolutePath, lastModified)` pair, shared through [gitignoreCache].
 * Before Tree v2, every [FileFilter] instance re-read and re-parsed the
 * file, and the configuration dialog plus the tree scanner both
 * created instances (and rescanned on every filter change). The cache
 * is bounded to [GITIGNORE_CACHE_MAX_ENTRIES] entries to prevent
 * unbounded growth across a long IDE session with many projects; the
 * eviction is approximate (removes the lexicographically first key, not
 * the true LRU) but bounded and O(1).
 *
 * ### Cancellation
 *
 * The export path is cancellable through the standard
 * `Thread.interrupt` mechanism. [walkIncludedAndStubbed] — and
 * [walkIncludedFiles], which delegates to it — checks the worker
 * thread's interrupt flag between directory entries, so an export that
 * is cancelled mid-walk unwinds within a single entry instead of having
 * to finish scanning the whole tree.
 *
 * The thrown [InterruptedException] propagates to the caller unchanged;
 * the export coordinator treats it as a user cancellation and never
 * reports it as a failure. The tree scanner in the configuration dialog
 * is not affected because it uses its own token-based cancellation and
 * never calls these methods while a filter instance is shared with the
 * export worker.
 */
class FileFilter(
    root: File,
    config: FilterConfig = FilterConfig()
) {
    val root: File = root.canonicalFile
    val config: FilterConfig = config.normalized()

    /**
     * Directory names that are always excluded regardless of user
     * patterns.
     *
     * The tree scanner uses this set to decide which directories to
     * render as LOCKED instead of descending into them. The names are
     * chosen from the union of common build tools, IDE metadata, and
     * package-manager caches, so that a fresh profile does not waste
     * time walking them and a fresh scan does not blow up the tree's
     * memory on large vendored trees.
     *
     * Entries here are unreachable by ordinary patterns by design: the
     * only way to see them is to enable "Show locked" in the tree, at
     * which point the user has explicitly opted into the extra scan
     * cost.
     */
    private val excludedDirectoryNames = setOf(
        ".git", ".gradle", ".idea", "build", "out", "target",
        "node_modules", "dist", ".next", ".nuxt", "venv", ".venv",
        "__pycache__", ".mypy_cache", ".kotlin", ".cxx", "captures",
        ".externalNativeBuild"
    )

    /**
     * Parsed `.gitignore` rules for the project root.
     *
     * Lazily loaded because not every caller touches ignore rules
     * (e.g. a caller that only uses [matchesAny] on inclusions), and
     * because the parse involves reading a file.
     *
     * The lazy is not synchronized (`LazyThreadSafetyMode.NONE`)
     * because a [FileFilter] instance is confined to a single consumer
     * at a time — the export path creates one per run, and the tree
     * scanner creates one per scan. Sharing a single instance across
     * threads is therefore a programming error, and the potential
     * double-parse in a race is harmless: both threads produce the same
     * immutable list.
     */
    private val ignoreRules: List<IgnoreRule> by lazy(LazyThreadSafetyMode.NONE) {
        if (this.config.respectGitignore) loadIgnoreRules() else emptyList()
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns the set of directory names that are always excluded
     * regardless of user patterns.
     *
     * Exposed so the tree scanner (`ProjectTreeView`) can decide which
     * directories to render as LOCKED without descending into them.
     * The set is the same one used by [resolveMode] for its directory
     * check, so the tree and the filter cannot disagree about which
     * directories are structurally excluded.
     *
     * The returned set is the internal, immutable set; callers must not
     * mutate it (Kotlin's `Set` interface does not expose mutators, so
     * this is enforced by the type system).
     */
    fun hardExcludedDirectories(): Set<String> = excludedDirectoryNames

    /**
     * Returns the set of file extensions treated as binary.
     *
     * Exposed for the same reason as [hardExcludedDirectories]: the
     * tree scanner needs it to decide which files to render as LOCKED
     * leaves. The set is the same one used by [resolveMode] for its
     * binary-extension check.
     *
     * The set contains lowercase extensions without the leading dot,
     * matching the form the filter compares against internally.
     */
    fun binaryExtensions(): Set<String> = BINARY_EXTENSIONS

    /**
     * Resolves the export role of a filesystem entry.
     *
     * Returns [NodeState.INCLUDE], [NodeState.STUB] or
     * [NodeState.EXCLUDE] according to the priority documented on the
     * class. It never returns [NodeState.LOCKED] — locking is the tree
     * scanner's responsibility, because it is the scanner that decides
     * when to descend into hard-excluded directories.
     *
     * ### Section enable flags
     *
     * The three user rule lists are each guarded by a boolean flag on
     * [FilterConfig] ([FilterConfig.enableInclusions],
     * [FilterConfig.enableExclusions],
     * [FilterConfig.enableStubPatterns]). When the flag is `false`, the
     * corresponding list is ignored entirely: the branch is skipped and
     * the next rule in the chain is evaluated, exactly as if the list
     * were empty. This is what makes the section checkboxes in the
     * configuration dialog reversible — the rules stay in the config,
     * they are just not applied while the section is off, so turning
     * the checkbox back on restores the exact same state without the
     * user having to re-enter anything.
     *
     * The flags are evaluated **before** their list so that a disabled
     * section cannot win against an enabled one on a technicality. For
     * example, with inclusions enabled and stub patterns disabled, a
     * file that matches both lists is exported as INCLUDE, because the
     * stub branch is skipped before the stub match is even attempted.
     * Re-enabling stub patterns flips that file back to STUB, which is
     * the expected behaviour of an enable toggle.
     *
     * ### `respectExcludedPaths` and the tree selector
     *
     * The parameter is preserved for API stability even though there is
     * no longer an `excludedPaths` field in [FilterConfig]: the tree
     * scanner no longer routes per-file decisions through this method,
     * because those come from [TreeOverrides] and are applied by the
     * caller before [resolveMode] runs.
     *
     * Semantically, `respectExcludedPaths = false` means "answer as if
     * the user had made no manual decisions", which is exactly what the
     * scanner needs to compute the filter-derived *initial* state of a
     * node before layering the overrides on top. In Tree v2 the two
     * modes are behaviorally identical — the legacy `excludedPaths`
     * source has moved out of [FilterConfig] — but the parameter stays
     * so that out-of-tree callers keep compiling and the intent remains
     * documented at the call site.
     *
     * ### `respectHardExcludedAncestors`
     *
     * When `true` (the default), a file whose path contains a directory
     * in [hardExcludedDirectories] — anything inside `build/`,
     * `node_modules/`, `.git/`, etc. — is always EXCLUDE, regardless
     * of the other rules. This is what keeps a normal scan fast and
     * focused: a fresh profile on a real project with hundreds of
     * thousands of files in `node_modules/` would otherwise flood the
     * tree with entries the user almost never wants to see.
     *
     * When `false`, the ancestor check is skipped and the file's own
     * rules decide its state. The `ProjectTreeView` passes `false`
     * when scanning the contents of a folder the user has explicitly
     * unlocked via "Show locked", so files inside `build/` (including
     * generated APKs, binaries, and resources) are evaluated by their
     * own rules instead of being hidden because their ancestor happens
     * to be in the hard-excluded set. Without this, expanding an
     * unlocked `build/` folder would produce an empty tree, because
     * every child would return EXCLUDE purely from its path.
     *
     * The parameter only affects the *file* branch. The directory
     * branch still rejects a directory whose own name is in
     * [hardExcludedDirectories], so a `build/` nested inside another
     * unlocked folder stays rejected unless the caller also resolves
     * it directly (which it does, one level at a time, when the user
     * expands it).
     *
     * ### Cancellation
     *
     * No interrupt check here: this method is called once per entry
     * from the walk loop, which is where the flag is polled. Adding a
     * second check per entry would double the cost of the hottest path
     * in the filter for no benefit.
     */
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
        // Only inherit the hard-excluded ancestor check when the caller
        // wants it. Unlocked subtrees (see the KDoc above) pass `false`
        // so the user can see and interact with files inside
        // node_modules, build, .git, etc., instead of getting an empty
        // tree because their ancestor happens to be in the
        // hard-excluded set.
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

    /**
     * Legacy boolean entry point retained for source compatibility.
     *
     * Equivalent to `resolveMode(...) == NodeState.INCLUDE`. New code
     * should call [resolveMode] directly and inspect the returned
     * state, because this wrapper silently discards the distinction
     * between EXCLUDE and STUB.
     *
     * The `respectExcludedPaths` parameter is forwarded unchanged; see
     * [resolveMode] for its meaning. The section enable flags are
     * honoured transparently by the underlying [resolveMode] call, so a
     * caller that uses this wrapper also gets the "disabled section
     * behaves like empty list" semantics without having to know the
     * flags exist.
     *
     * `respectHardExcludedAncestors` is not exposed on this wrapper
     * because the wrapper is the legacy code path and no caller that
     * uses it needs the unlocked-subtree behaviour. Callers that want
     * it must switch to [resolveMode] and pass the parameter
     * explicitly.
     */
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

    /**
     * Legacy path-based boolean entry point retained for source
     * compatibility.
     *
     * Equivalent to `resolveMode(...) == NodeState.INCLUDE`. Used by
     * callers that enumerate paths from a source other than the
     * filesystem — for example the Git snapshot path, whose file list
     * comes from the tree object rather than from a directory walk.
     *
     * See [resolveMode] for the priority order and the meaning of
     * `respectExcludedPaths`. The section enable flags are honoured
     * transparently, as in [include].
     *
     * `respectHardExcludedAncestors` is not exposed on this wrapper
     * either; see [include] for the rationale.
     */
    fun includePath(
        relativePath: String,
        isDirectory: Boolean,
        sizeBytes: Long = 0L,
        respectExcludedPaths: Boolean = true
    ): Boolean = resolveMode(relativePath, isDirectory, sizeBytes, respectExcludedPaths) == NodeState.INCLUDE

    /**
     * Returns every included or stubbed file in deterministic
     * depth-first order, paired with its resolved [NodeState].
     *
     * Only one directory's children are materialized at a time, so peak
     * memory is proportional to the depth of the tree, not to its size.
     *
     * ### Which states are emitted
     *
     * Both [NodeState.INCLUDE] and [NodeState.STUB] files are returned;
     * the caller distinguishes them by the second component of the
     * pair. This is what lets the dump generator write a stub's header
     * and size marker without ever opening the file.
     * [NodeState.EXCLUDE] entries are skipped entirely.
     * [NodeState.LOCKED] is never returned by this method because the
     * filter does not produce that state — locking is the scanner's
     * decision, and the scanner does not descend into locked
     * directories anyway.
     *
     * The section enable flags are honoured transparently by the
     * underlying [resolveMode] calls, so a disabled section's rules are
     * not applied when the walk decides what to yield. A directory that
     * is only excluded because of a disabled-section rule is therefore
     * walked as if the rule did not exist, which is exactly what the
     * user sees in the tree selector.
     *
     * ### Directory-level stubs
     *
     * A directory that matches a stub pattern is *walked* as if it
     * were included, because a directory stub is a structural hint
     * rather than a leaf entry: the dump format has no way to express
     * "this folder's contents were omitted" as a single block. The
     * children of such a directory are then resolved individually, so
     * a folder that matches a stub pattern still contributes per-file
     * stubs for each of its files, which is exactly the mental model a
     * user has when they mark a folder as reference-only in the tree
     * selector.
     *
     * ### Hard-excluded ancestors
     *
     * This walk uses the default `respectHardExcludedAncestors = true`
     * behaviour of [resolveMode], because the walk is used by the
     * export path, which must never include files inside
     * `node_modules`, `build`, `.git`, etc., unless the user has
     * explicitly overridden them via [TreeOverrides] (and those
     * overrides are applied by the caller, not by this walk). The
     * unlocked-subtree scan in the configuration dialog uses its own
     * one-level scan instead of this walk, precisely because it needs
     * the opposite behaviour.
     *
     * ### Cancellation
     *
     * The worker thread's interrupt flag is checked once per directory
     * entry, in two places:
     *
     *  - at the top of [walk], before `listFiles()` runs, so a
     *    directory that was just entered cannot start its scan if the
     *    user already pressed Cancel between recursion levels, and
     *  - inside the `for` loop, before dispatching into [resolveMode]
     *    or yielding, so a wide directory with thousands of children
     *    unwinds on the very next entry.
     *
     * `listFiles()` and `sortedWith` themselves are not interruptible
     * — both are bounded by the number of entries in a single directory
     * and complete in milliseconds on any realistic project — so adding
     * a check inside them is not worth the extra branch per iteration.
     *
     * The thrown [InterruptedException] propagates out of the calling
     * terminal operator (`toList`, `count`, `forEach`, …) and lands in
     * the export coordinator's dedicated cancellation handler.
     */
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

    /**
     * Legacy convenience wrapper around [walkIncludedAndStubbed] that
     * returns only the [NodeState.INCLUDE] files.
     *
     * Retained so that callers that predate Tree v2 keep working
     * without a source change. New code should call
     * [walkIncludedAndStubbed] directly, because dropping the stubs
     * silently changes the dump's shape for a caller that meant "every
     * file the tree selector shows as included or reference-only".
     *
     * Cancellation semantics are identical to
     * [walkIncludedAndStubbed]. Section enable flags are also honoured
     * identically, since this method delegates to the same walk.
     */
    fun walkIncludedFiles(): Sequence<File> =
        walkIncludedAndStubbed().filter { it.second == NodeState.INCLUDE }.map { it.first }

    /**
     * Returns the number of files that would be emitted with the
     * current configuration, counting both [NodeState.INCLUDE] and
     * [NodeState.STUB] entries.
     *
     * Used to drive the determinate progress bar in the generation
     * panel and the notification text. Stubs count toward the total
     * because the dump writes a header for each of them, which is a
     * per-file operation from the user's point of view even though the
     * file's content is never read.
     *
     * Delegates to [walkIncludedAndStubbed], so it also honours the
     * worker thread's interrupt flag: if the export is cancelled while
     * the pre-count is running, this throws [InterruptedException]
     * instead of finishing the traversal. Section enable flags affect
     * the count exactly as they affect the walk, because both share
     * the same [resolveMode] calls.
     */
    fun countEligibleFiles(): Int = walkIncludedAndStubbed().count()

    /**
     * Root `.gitignore` rules available for display in the
     * configuration UI.
     *
     * Negative rules (`!foo`) are included with their `!` prefix
     * preserved so the UI can show them as negations. Previously they
     * were filtered out, which hid from the user that a `!important.txt`
     * in their `.gitignore` was actually overriding a broader
     * exclusion.
     *
     * The returned list is **not** affected by the section enable
     * flags: the `.gitignore` file itself is always read (subject to
     * [FilterConfig.respectGitignore]), and the caller can display the
     * rules regardless of whether inclusions, exclusions, or stub
     * patterns are enabled. This is deliberate: the "Gitignore"
     * section in the configuration dialog needs the raw rule list to
     * render its chip flow, and that flow is independent of the other
     * sections' toggles.
     */
    fun loadGitignoreRules(): List<String> =
        readGitignoreEntry()?.displayRules ?: emptyList()

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Throws [InterruptedException] when the current thread has been
     * interrupted. Shared by every loop in this class so the walk
     * responds to `ExportCoordinator.cancel()` within a single
     * directory entry.
     */
    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("File walk was interrupted.")
        }
    }

    /**
     * Returns the path of [file] relative to [root], normalized for
     * comparison and storage.
     *
     * Uses canonical paths on both sides so that symlink aliases inside
     * the project do not produce duplicate entries with different path
     * strings. Returns null when canonicalization fails (broken symlink,
     * permission error) so the caller can skip the entry instead of
     * crashing the scan.
     */
    private fun relativePathOf(file: File): String? = runCatching {
        val canonical = file.canonicalFile
        if (canonical == root) "" else {
            val relative = canonical.toPath().let { root.toPath().relativize(it) }
            FilterConfig.normalizeRelativePath(relative.toString())
        }
    }.getOrNull()

    /**
     * `.gitignore`'s last matching rule wins. Rules are loaded once per
     * filter instance, and ignored parent directories prevent traversal
     * into children.
     *
     * The ancestor loop is what makes a broad ignore rule like `build/`
     * propagate into every descendant even when a later rule negates a
     * specific child: the walk checks each ancestor prefix, so a
     * negated `build/keep.txt` cannot bypass an ignored `build/`.
     */
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

    /**
     * Evaluates the parsed `.gitignore` rules against a single path.
     *
     * Last matching rule wins, matching `.gitignore` semantics. A
     * negation (`!foo`) therefore re-includes a path that an earlier
     * rule excluded, but only at the exact level where the negation
     * applies — the ancestor check in [isIgnored] still runs first.
     */
    private fun isIgnoredPath(path: String, isDirectory: Boolean): Boolean {
        var ignored = false
        ignoreRules.forEach { rule ->
            if (rule.matches(path, isDirectory)) ignored = !rule.negated
        }
        return ignored
    }

    /**
     * Returns the parsed rules from the root `.gitignore`, or an empty
     * list when the file is missing.
     */
    private fun loadIgnoreRules(): List<IgnoreRule> =
        readGitignoreEntry()?.parsedRules ?: emptyList()

    /**
     * Reads and parses the root `.gitignore`, reusing the cached entry
     * when the file's mtime has not changed.
     *
     * The fast path returns the cached entry without touching the
     * filesystem beyond the `lastModified()` call; the slow path reads
     * and parses the file once and stores the result.
     * `ConcurrentHashMap` provides the happens-before guarantee needed
     * for the immutable [GitignoreCacheEntry] to be safely shared
     * between threads.
     *
     * The cache is bounded: when it exceeds
     * [GITIGNORE_CACHE_MAX_ENTRIES], the lexicographically-first key is
     * evicted before inserting the new entry. Approximate-LRU is
     * acceptable here because the working set of simultaneously open
     * projects in an IDE session is small (typically 1-3) and the cost
     * of a cache miss is one file read.
     */
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
        // `rawRules` keeps the pre-`!` form (used by nothing today, but
        // kept for backwards compatibility with code that may still
        // read it).
        val rawRules = filtered
            .filter { !it.startsWith('!') }
            .map { it.removePrefix("/").removeSuffix("/") }
            .distinct()
        // `displayRules` keeps the `!` prefix so the UI can render
        // negations.
        val displayRules = filtered.distinct()

        val entry = GitignoreCacheEntry(mtime, rawRules, displayRules, parsedRules)
        if (gitignoreCache.size >= GITIGNORE_CACHE_MAX_ENTRIES) {
            gitignoreCache.keys.firstOrNull()?.let { gitignoreCache.remove(it) }
        }
        gitignoreCache[key] = entry
        return entry
    }

    /**
     * Tests a path against one of the three user rule lists
     * (inclusions, exclusions, stub patterns).
     *
     * The rule matching is intentionally identical for all three lists
     * so that a pattern behaves the same wherever the user writes it.
     * The only difference is the state the caller assigns when a match
     * is found, which is why this method returns a boolean and the
     * decision lives in [resolveMode].
     *
     * This method does **not** consult the section enable flags. The
     * caller is expected to gate the call on
     * [FilterConfig.enableInclusions],
     * [FilterConfig.enableExclusions] or
     * [FilterConfig.enableStubPatterns] as appropriate. Keeping the
     * gate at the call site rather than inside this helper is what
     * allows [resolveMode] to short-circuit the disabled branch
     * entirely instead of computing a match it is going to ignore.
     */
    private fun matchesAny(
        rules: List<String>,
        path: String,
        fileName: String,
        extension: String
    ): Boolean = rules.any { rawRule ->
        // Strip the .gitignore-style trailing slash before comparing.
        // `build/` in an exclusion should behave the same as `build` —
        // `fileName` and `path` never carry a trailing slash, so
        // without this the literal comparison and the glob match both
        // fail. The slash is a gitignore hint that the rule targets a
        // directory; here the caller already knows whether the entry is
        // a directory (`isDirectory` branch in `resolveMode`), so
        // keeping it on the pattern would only cause the rule to
        // silently never match.
        val literalRule = rawRule.trim().removeSuffix("/")
        if (literalRule.isEmpty()) return@any false
        val extensionRule = literalRule.removePrefix(".")
        literalRule.equals(fileName, ignoreCase = true) ||
            literalRule.equals(path, ignoreCase = true) ||
            extensionRule.equals(extension, ignoreCase = true) ||
            globMatches(literalRule, fileName) ||
            globMatches(literalRule, path)
    }

    /**
     * Returns true if [file] is a symbolic link, false if it is a
     * regular file or directory, and false on any error.
     *
     * Errors are swallowed and reported as "not a symlink" because the
     * alternative — treating an error as "is a symlink" — would
     * silently drop entries the user can see, whereas the reverse only
     * affects the rare error case where a broken symlink would be
     * walked. In that case [relativePathOf] returns null and the entry
     * is skipped anyway.
     */
    private fun isSymbolicLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Parsed representation of a single `.gitignore` rule.
     *
     * Every field is a direct encoding of one aspect of the gitignore
     * syntax so that [matches] can reproduce gitignore's semantics
     * without re-parsing the rule on every evaluation:
     *
     *  - [negated] captures the leading `!`, which flips the meaning of
     *    a match from "ignore" to "re-include".
     *  - [directoryOnly] captures a trailing `/`, which restricts the
     *    rule to directories.
     *  - [anchored] captures a leading `/`, which restricts the rule to
     *    paths starting at the repository root.
     *  - [hasSlash] is true when the pattern contains an internal `/`,
     *    which (per gitignore) makes the rule match against the full
     *    path rather than just the basename.
     *  - [regex] is the compiled matcher, precomputed so that the hot
     *    path in [matches] does not have to parse the glob.
     */
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

    /**
     * Immutable cache entry for one `.gitignore` file.
     *
     * Contains every representation of the parsed file that any
     * consumer might need, so that a cache hit answers all of them
     * without a second parse: [mtime] for invalidation, [rawRules] and
     * [displayRules] for the UI, and [parsedRules] for the matcher.
     */
    private data class GitignoreCacheEntry(
        val mtime: Long,
        val rawRules: List<String>,
        val displayRules: List<String>,
        val parsedRules: List<IgnoreRule>
    )

    companion object {
        private val gitignoreCache = ConcurrentHashMap<String, GitignoreCacheEntry>()

        /**
         * Upper bound on cached `.gitignore` entries. Chosen so a
         * typical IDE session (1-3 open projects) never evicts, while
         * a long-running session with dozens of projects does not grow
         * without limit.
         */
        private const val GITIGNORE_CACHE_MAX_ENTRIES = 32

        /**
         * Forces a reload when the `.gitignore` file was changed
         * outside the expected mtime window (e.g. a Git checkout that
         * preserves the previous timestamp on some filesystems).
         */
        fun invalidateGitignoreCache(root: File) {
            val gitignore = File(root.canonicalFile, ".gitignore")
            gitignoreCache.remove(gitignore.absolutePath)
        }

        /**
         * Fixed list of known binary extensions.
         *
         * `svg` was historically listed here, but it is an XML text
         * format and an AI consumer can meaningfully reason about it
         * (structure, naming, path data). Treating it as binary meant
         * that an explicit inclusion of `svg` had no effect, because
         * the fallback check `extension in TEXT_EXTENSIONS` is what
         * grants inclusion to files that are neither binary nor
         * hidden. It is now in [TEXT_EXTENSIONS].
         *
         * `lock` was historically listed here too, but modern lockfiles
         * (`Cargo.lock`, `Gemfile.lock`, `poetry.lock`, `yarn.lock`,
         * `composer.lock`, `pubspec.lock`) are plain text. They are
         * still excluded by default (they are not in
         * [TEXT_EXTENSIONS] either), but a user who wants them can now
         * add `lock` to inclusions and the filter will honour it, or
         * add a specific lockfile name to
         * [FilterConfig.stubPatterns] to include it as a
         * reference-only entry.
         */
        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff",
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "mp3", "wav", "ogg", "flac",
            "zip", "jar", "aar", "apk", "aab", "tar", "gz", "rar", "7z",
            "so", "dll", "dylib", "exe", "class", "dex",
            "ttf", "otf", "woff", "woff2", "pdf", "db", "sqlite", "bin"
        )

        /**
         * Extensions treated as text by default.
         *
         * `svg` is here because it is XML. `lock` is deliberately not
         * here: lockfiles are text, but they are typically
         * machine-generated and multi-megabyte, so they are only
         * included when the user adds `lock` to inclusions explicitly,
         * or stubbed via stub patterns.
         */
        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "xml", "toml", "gradle", "properties", "json",
            "yaml", "yml", "md", "txt", "pro", "cfg", "ini", "html", "css", "scss",
            "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h", "cpp",
            "hpp", "cs", "sh", "bat", "ps1", "sql", "proto", "graphql", "svg"
        )

        private fun globMatches(pattern: String, value: String): Boolean =
            globToRegex(pattern).matches(value)

        /**
         * Compiles a `.gitignore`-style glob to a [Regex].
         *
         * Supported syntax:
         *  - `*` matches zero or more non-slash characters.
         *  - `?` matches exactly one non-slash character.
         *  - `**` matches across directory separators.
         *  - A leading double-star followed by a slash matches zero or
         *    more leading directories, including none, so the pattern
         *    matches a file at the project root as well as in any
         *    subdirectory. This was previously compiled to an optional
         *    prefix group, which when anchored at the start of the
         *    string did not match the root form.
         *  - `[abc]` and `[!abc]` are passed through as character
         *    classes (with `[!` translated to the regex `[^` form),
         *    matching the `.gitignore` convention. `[` and `]` are no
         *    longer escaped, which previously disabled character-class
         *    matching entirely.
         *
         * Deliberately not supported: backslash escapes. A literal `[`
         * in a filename must be written as `[[]` (a character class
         * containing `[`), as in `.gitignore` itself.
         *
         * The double-star-plus-slash form is compiled to an alternation
         * between "start of string" and "any prefix ending in a slash",
         * rather than an optional prefix group. An optional group would
         * not match "nothing" at the start of the string while still
         * allowing the pattern to match; the alternation makes the
         * "match nothing" branch explicit.
         */
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
                                // Git-style `**/` also matches a file at
                                // the pattern's root. The alternation
                                // below makes that explicit: either we
                                // are at the start of the string (so
                                // the pattern matches at the root) or
                                // we consume any prefix ending in a
                                // slash.
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
                        // Character class start. Translate gitignore's
                        // `[!...]` negation form to regex's `[^...]`
                        // form.
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