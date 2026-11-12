package com.devdayton.onecodeforai.core

/**
 * Immutable configuration for a project export.
 *
 * The property names are intentionally stable: user profiles written by
 * previous versions are stored as JSON using these keys. New fields are
 * added with backward-compatible defaults, so an older profile loaded by
 * a newer build never fails to parse — the new fields simply take their
 * documented defaults.
 *
 * ### Scope: this is the rules half of the export model
 *
 * As of Tree v2 the export configuration is split into two independent
 * pieces:
 *
 *  - Rules (this class): declarative filters that decide the initial
 *    state of every entry when the project tree is scanned. They travel
 *    with the preset across projects.
 *  - Overrides ([TreeOverrides]): per-path manual state changes made in
 *    the tree selector. They are persisted per project under
 *    .platform/plugins/com.devdayton.onecodeforai/config.json and only
 *    on Generate.
 *
 * Keeping the two separate lets the user switch profiles without losing
 * manual decisions and lets a profile stay portable.
 *
 * ### Section enable switches (Tree v2, section-toggle patch)
 *
 * Inclusions, exclusions, and reference-only patterns are each guarded
 * by a boolean enable flag ([enableInclusions], [enableExclusions],
 * [enableStubPatterns]). These flags power the section checkboxes in the
 * configuration dialog: a user can temporarily disable a rule list
 * without destroying it, so toggling the section off and back on
 * restores exactly the rules that were there before.
 *
 * The flags are not aliases for "the list is empty". A section can be
 * enabled with an empty list (the filter then matches nothing) or
 * disabled with a populated list (the filter then ignores the list
 * entirely). The distinction matters for two reasons:
 *
 *  - Portability. A user who disables exclusions on project A and
 *    saves that state under a profile expects the profile's exclusion
 *    list to be intact when they open project B; the flag carries the
 *    "the user chose not to apply this" signal, not a silent wipe of
 *    the rules.
 *  - Round-trip stability. The codec and the preset manager serialise
 *    both the list and the flag. A disabled-but-populated section
 *    therefore survives a save/load cycle unchanged, so a user who
 *    toggles a section off, generates, and then reopens the dialog
 *    sees the section still off — and the rules are still there for
 *    when they turn it back on.
 *
 * The flags are evaluated in [FileFilter.resolveMode] before the
 * corresponding rule list, so a disabled section cannot win against an
 * enabled one on a technicality (see the priority chain below).
 *
 * ### excludedPaths was removed in Tree v2
 *
 * Earlier versions carried an excludedPaths field that mixed manually
 * excluded paths with the profile. That leaked one project's manual
 * exclusions into every other project opened with the same profile. The
 * codecs accept the old excludedPaths key on read and ignore it.
 *
 * ### Evaluation order in [FileFilter.resolveMode]
 *
 * The rules are evaluated in this fixed order:
 *
 *  1. [enableStubPatterns] and [stubPatterns] match -> STUB
 *  2. [enableInclusions] and [inclusions] match     -> INCLUDE
 *  3. [enableExclusions] and [exclusions] match     -> EXCLUDE
 *  4. .gitignore match                              -> EXCLUDE (only when [respectGitignore])
 *  5. per-file size overflow                        -> EXCLUDE (only when [useMaxSize])
 *  6. binary extension                              -> EXCLUDE (only when [excludeBinaries])
 *  7. hidden file (dot-prefixed)                    -> EXCLUDE
 *  8. text extension or no extension                -> INCLUDE
 *  9. otherwise                                     -> EXCLUDE
 *
 * Each of the three section flags short-circuits its corresponding
 * branch: when the flag is false, the branch is skipped entirely and
 * the next rule in the chain is evaluated. This is what makes the
 * "disabled section" behave exactly like "the user removed every rule
 * from that section", without the destructive side effect of actually
 * deleting the entries.
 *
 * @property excludeBinaries when true (the default), files whose
 *   extension is in the filter's binary set are excluded from the
 *   export. Users who genuinely need to include a binary can add its
 *   extension to [inclusions], which is evaluated before this check.
 *
 * @property respectGitignore when true (the default), the root
 *   .gitignore rules are parsed and applied. Evaluated after the
 *   three user rule lists, so an inclusion always wins over a
 *   .gitignore entry. Toggled by the "Gitignore" section checkbox in
 *   the configuration dialog; unlike the three section flags below, this
 *   one has no separate rule list of its own — the rules come from the
 *   .gitignore file, and the flag is the only way to disable them.
 *
 * @property useMaxSize when true (the default), files larger than
 *   [maxSizeKb] kilobytes are excluded. Guards against a single huge
 *   generated file dominating the dump.
 *
 * @property maxSizeKb the per-file size limit, in kilobytes. Only
 *   consulted when [useMaxSize] is true. Coerced to at least 1 by
 *   [normalized], so a stray 0 in a hand-edited profile cannot reject
 *   every file.
 *
 * @property inclusions whitelist of extensions and glob patterns.
 *   Evaluated before [exclusions] and .gitignore, so an inclusion
 *   always wins. Guarded by [enableInclusions]; see the class KDoc.
 *
 * @property exclusions blacklist of extensions and glob patterns.
 *   Evaluated after [inclusions] (an inclusion wins on conflict) and
 *   before .gitignore. Guarded by [enableExclusions].
 *
 * @property stubPatterns patterns for files that should be listed in
 *   the dump but whose content is not read. A match produces a
 *   [NodeState.STUB] entry: the file banner and a content-omitted
 *   marker are written, but the file body is never opened. Suitable for
 *   lockfiles, generated code, and machine-local configuration.
 *
 *   Syntax is the same as [inclusions] and [exclusions]: bare extension
 *   (lock, log), filename glob (package-lock.json, *.min.js), or a
 *   path glob for nested directories such as a match on any "generated"
 *   directory at any depth. Case-insensitive.
 *
 *   Evaluated before [inclusions] and [exclusions], so a file matching
 *   both a stub pattern and an inclusion is exported as a stub. That is
 *   the whole point of stubbing: "list the file, do not read its body"
 *   must take precedence over any broad inclusion that would otherwise
 *   pull the body in.
 *
 *   This list holds the rule-based half of the stub feature. The
 *   per-path half lives in [TreeOverrides.stubPaths] and is applied by
 *   the tree scanner, not by this class.
 *
 *   Guarded by [enableStubPatterns].
 *
 * @property enableInclusions when false, [inclusions] is preserved but
 *   ignored. The corresponding section checkbox in the configuration
 *   dialog toggles this flag; toggling the checkbox off and back on
 *   restores the same list. Defaults to true, matching the
 *   pre-toggle behaviour where a populated inclusion list was always
 *   applied.
 *
 * @property enableExclusions when false, [exclusions] is preserved but
 *   ignored. Same semantics as [enableInclusions]. Defaults to true.
 *
 * @property enableStubPatterns when false, [stubPatterns] is preserved
 *   but ignored. Same semantics as [enableInclusions]. Defaults to
 *   true. When disabled, files that would otherwise have been
 *   exported as stubs are included or excluded according to the
 *   remaining rules — typically included, because a stub pattern is
 *   usually a specific override of a broader inclusion rule.
 *
 * @property useGit when true, the export reads from a Git snapshot at
 *   [gitRef] instead of the current working tree. The Git section
 *   checkbox in the configuration dialog toggles this flag; the export
 *   coordinator validates [gitRef] before launching the run.
 *
 * @property gitRef the reference (branch, tag, commit, or any expression
 *   Git can resolve) the export should read from when [useGit] is true.
 *   Null or blank means "no reference selected"; the configuration
 *   dialog treats that as "Git export disabled" even when [useGit] is
 *   true, to avoid a run that would fail for an obvious reason.
 */
data class FilterConfig(
    val excludeBinaries: Boolean = true,
    val respectGitignore: Boolean = true,
    val useMaxSize: Boolean = true,
    val maxSizeKb: Int = DEFAULT_MAX_SIZE_KB,
    val inclusions: List<String> = emptyList(),
    val exclusions: List<String> = emptyList(),
    val stubPatterns: List<String> = emptyList(),

    /**
     * Enables [inclusions]. Defaults to true so an existing profile
     * behaves exactly as before the toggle was introduced.
     *
     * See the class KDoc, section Section enable switches, for the
     * full rationale of why this is a separate flag rather than a
     * "non-empty list" check.
     */
    val enableInclusions: Boolean = true,

    /**
     * Enables [exclusions]. Defaults to true for the same reason as
     * [enableInclusions].
     */
    val enableExclusions: Boolean = true,

    /**
     * Enables [stubPatterns]. Defaults to true for the same reason as
     * [enableInclusions].
     */
    val enableStubPatterns: Boolean = true,

    val useGit: Boolean = false,
    val gitRef: String? = null
) {
    /**
     * A positive, overflow-safe byte limit used by file filters.
     *
     * When [useMaxSize] is false, the property returns Long.MAX_VALUE
     * so the filter's size check can never reject a file for size — the
     * flag is authoritative, and the numeric value of [maxSizeKb] is
     * irrelevant. When [useMaxSize] is true, the value is
     * maxSizeKb times 1024 bytes with [maxSizeKb] coerced to at least 1,
     * so a corrupted preference of 0 cannot reject every file.
     */
    val maxFileSizeBytes: Long
        get() = if (!useMaxSize) Long.MAX_VALUE
                else maxSizeKb.coerceAtLeast(1).toLong() * BYTES_PER_KB

    /**
     * Returns a copy with every list and numeric field in canonical form:
     *
     *  - [maxSizeKb] is coerced to at least 1.
     *  - [inclusions], [exclusions] and [stubPatterns] are trimmed,
     *    empty entries dropped, and duplicates removed
     *    case-insensitively (so KT and kt do not both survive).
     *
     * The three section enable flags ([enableInclusions],
     * [enableExclusions], [enableStubPatterns]) are not touched by
     * normalization. They are the user's explicit intent and must not
     * be derived from the list state — a normalized config with
     * enableInclusions = false and a populated [inclusions] list is a
     * valid, meaningful state (the user turned the section off but did
     * not clear it), and collapsing it to false + empty list would
     * destroy the very thing that makes the toggle reversible.
     *
     * The method is idempotent: cfg.normalized().normalized() ==
     * cfg.normalized() for every input.
     */
    fun normalized(): FilterConfig = copy(
        maxSizeKb = maxSizeKb.coerceAtLeast(1),
        inclusions = inclusions.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() },
        exclusions = exclusions.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() },
        stubPatterns = stubPatterns.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
    )

    companion object {
        /** Default value for [maxSizeKb]; 512 KB covers the vast majority of source files. */
        const val DEFAULT_MAX_SIZE_KB = 512

        /** Number of bytes in a kilobyte, as used by [maxFileSizeBytes]. */
        const val BYTES_PER_KB = 1024L

        /**
         * Normalizes a relative path for storage and comparison.
         *
         * Trims surrounding whitespace, converts backslashes to forward
         * slashes, strips leading slashes, collapses internal duplicate
         * slashes, and removes a trailing slash. The result is the
         * canonical form used as the key in [TreeOverrides] and as the
         * path argument to [FileFilter.resolveMode].
         *
         * The method does not resolve single-dot or double-dot segments;
         * callers that accept user input are expected to reject those
         * before calling [normalizeRelativePath] (see [FileFilter] for
         * the exact policy).
         */
        fun normalizeRelativePath(path: String): String = path
            .trim()
            .replace('\\', '/')
            .trimStart('/')
            .replace(Regex("/+/"), "/")
            .removeSuffix("/")
    }
}