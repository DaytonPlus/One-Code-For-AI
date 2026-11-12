package com.devdayton.onecodeforai.core

/**
 * The four states a project entry can occupy in the export model.
 *
 * ### Overview
 *
 * Before Tree v2 the export model was a boolean: a file was either
 * included (its content written) or excluded (not written at all). That
 * was sufficient for a filter-driven pipeline, but it could not express
 * two important cases that the tree selector needs:
 *
 *  - A file the user wants the model to *know about* without receiving
 *    its content — a lockfile, a generated source file, a
 *    machine-local config. The model benefits from knowing the file
 *    exists and how large it is, but including its body would waste
 *    tokens and adding it to `Exclusions` would hide it entirely.
 *  - A file that is structurally excluded (a hard-excluded directory
 *    like `.git`, or a binary extension) and that the user has not
 *    explicitly opted into seeing. Hiding it entirely would surprise
 *    the user who expects to see the whole tree; making it a normal
 *    EXCLUDE would let them accidentally click it into INCLUDE, which
 *    is almost never what they want.
 *
 * [NodeState] encodes all four cases explicitly so that the tree, the
 * scanner, and the dump generator can agree on what each entry means
 * without having to re-derive it from a filter.
 *
 * ### State semantics
 *
 *  - **[INCLUDE]** — the file's full content is written to the dump.
 *  - **[STUB]** — the file's header and a size marker are written, but
 *    its content is not read. This is the "reference only" state.
 *  - **[EXCLUDE]** — the file is not written to the dump at all.
 *  - **[LOCKED]** — the file is present in the tree but not
 *    interactive. Only the scanner produces this state, and only for
 *    entries that are excluded by structural rules (hard-excluded
 *    directory names, binary extensions). LOCKED entries become
 *    EXCLUDE entries when the user flips the "Show locked" toggle in
 *    the tree header.
 *
 * ### Transitions
 *
 * The user-facing cycle for a leaf node is:
 *
 *  INCLUDE → STUB → EXCLUDE → INCLUDE
 *
 * LOCKED is not part of the cycle. It is entered by the scanner and
 * exited by the "Show locked" toggle, which flips LOCKED to EXCLUDE
 * (or back). Clicks on a LOCKED node are ignored until that toggle is
 * enabled.
 *
 * Directory nodes never have a state of their own; their displayed
 * state is the aggregate of their non-LOCKED children (see
 * `ProjectTreeView.computeAggregateState`). This enum is used to
 * represent that aggregate as well, which is why the aggregate
 * computation also returns a [NodeState] and why [LOCKED] never
 * appears as an aggregate (LOCKED children are filtered out before
 * the aggregate is computed).
 *
 * ### Persistence
 *
 * Only [INCLUDE], [STUB], and [EXCLUDE] are ever persisted in the
 * project's `config.json`. [LOCKED] is a UI-only state that exists
 * between the scan and the "Show locked" toggle; it is never written
 * to disk. The persistence codec (`TreeOverrides`) therefore only
 * ever serializes the three user-facing states, and the scanner
 * re-derives LOCKED from the same hard-excluded rules on every open.
 */
enum class NodeState {
    /** The file's full content is written to the dump. */
    INCLUDE,

    /**
     * The file is listed in the dump by name and size, but its content
     * is not read. Used for lockfiles, generated code, and
     * machine-local configuration whose existence matters to the
     * consuming model but whose body is noise.
     */
    STUB,

    /** The file is not written to the dump at all. */
    EXCLUDE,

    /**
     * The file is present in the tree but not interactive. Produced
     * only by the scanner, for entries excluded by structural rules
     * (hard-excluded directory names, binary extensions). The "Show
     * locked" toggle in the tree header flips LOCKED entries to
     * EXCLUDE so they become interactive.
     */
    LOCKED
}