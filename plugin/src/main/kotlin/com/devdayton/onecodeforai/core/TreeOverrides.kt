package com.devdayton.onecodeforai.core

/**
 * Per-path overrides applied on top of the filter-derived initial state.
 *
 * ### What this is
 *
 * When the tree selector scans a project it starts from the state each
 * entry's rules produce: a `.kt` file is INCLUDE because the profile
 * has `kt` in inclusions, a `package-lock.json` is STUB because the
 * profile has it in stub patterns, and so on. Those rules are stored
 * in [FilterConfig] and travel with the preset across projects.
 *
 * Manual changes the user makes in the tree are stored in this class.
 * They are intentionally separate from [FilterConfig] because they
 * describe *decisions about specific files in a specific project*,
 * not reusable filtering rules:
 *
 *  - A user who stubs `app/build/generated/Foo.kt` for one project
 *    probably does not want that stub to apply to every project they
 *    open with the same profile.
 *  - Two projects opened with the same profile should not share manual
 *    exclusions.
 *
 * Keeping the two concerns in different classes and different
 * persistence layers is what allows a profile to be portable without
 * dragging project-specific paths along with it.
 *
 * ### Persistence
 *
 * Stored in `<projectRoot>/.platform/plugins/com.devdayton.onecodeforai/config.json`
 * by [TreeConfigStore]. Only written on Generate; a manual change the
 * user makes and then discards by closing the dialog without
 * generating is deliberately lost, matching the "session vs. commit"
 * model the preset spinner already uses.
 *
 * ### Priority
 *
 * Applied **before** every other rule in the pipeline. A path in
 * [includedPaths] is INCLUDE regardless of what the filter says; a
 * path in [stubPaths] is STUB regardless of what the filter says; a
 * path in [excludedPaths] is EXCLUDE regardless of what the filter
 * says. The three lists are mutually exclusive by construction — a
 * path can be in at most one of them — because the tree selector
 * removes the path from the other two whenever the user cycles its
 * state.
 *
 * ### Normalization
 *
 * Paths are stored in the canonical form produced by
 * [FilterConfig.normalizeRelativePath] (forward slashes, no leading or
 * trailing slash, no duplicate slashes). The constructor does not
 * enforce this — callers are expected to normalize before building a
 * [TreeOverrides] — because the cost of normalizing a list on every
 * construction would be paid on every tree update, which is the
 * hottest path in the UI. The places that actually consume the lists
 * ([FileFilter.resolveMode], the tree scanner, the dump generator)
 * normalize their keys before lookup, so a non-normalized entry in the
 * list would fail to match the corresponding path. Callers that
 * construct [TreeOverrides] from user input must normalize first;
 * [TreeConfigStore] does this on load and save.
 *
 * @param includedPaths  relative paths the user forced to [NodeState.INCLUDE]
 *                       regardless of what the filter says.
 * @param stubPaths      relative paths the user forced to [NodeState.STUB]
 *                       regardless of what the filter says.
 * @param excludedPaths  relative paths the user forced to [NodeState.EXCLUDE]
 *                       regardless of what the filter says.
 */
data class TreeOverrides(
    val includedPaths: List<String> = emptyList(),
    val stubPaths: List<String> = emptyList(),
    val excludedPaths: List<String> = emptyList()
) {
    /**
     * Returns `true` when every list is empty.
     *
     * Used by the tree selector and [TreeConfigStore] to skip writes
     * and lookups that would be no-ops, and by the dialog to detect the
     * common "nothing was overridden" case without inspecting three
     * lists individually.
     */
    fun isEmpty(): Boolean =
        includedPaths.isEmpty() && stubPaths.isEmpty() && excludedPaths.isEmpty()

    /**
     * Returns the [NodeState] this override forces for [relativePath],
     * or `null` when the path is not overridden.
     *
     * The method is the single lookup point shared by every consumer,
     * which is what guarantees that the tree, the scanner, and the
     * dump generator agree on which override (if any) applies to a
     * path. Because the three lists are mutually exclusive by
     * construction, the order of the checks does not matter in
     * practice; the current order matches the order the tree selector
     * uses when it cycles the state, so a debugger stepping through
     * the checks reads them in the same sequence the user sees in the
     * UI.
     */
    fun stateFor(relativePath: String): NodeState? = when {
        relativePath in includedPaths -> NodeState.INCLUDE
        relativePath in stubPaths -> NodeState.STUB
        relativePath in excludedPaths -> NodeState.EXCLUDE
        else -> null
    }
}