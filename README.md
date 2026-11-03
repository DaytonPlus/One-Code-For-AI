# One Code For AI

**Prepare clean, reviewable technical context for AI-assisted programming — in one file.**

One Code For AI is a **CodeAssist** plugin that scans your project, applies smart filtering, and generates a single, well-structured `.txt` dump containing a directory tree and the selected file contents. Drop it into any LLM or code-review tool and start working immediately.

![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Min SDK](https://img.shields.io/badge/API-26%2B-brightgreen.svg)
![Target SDK](https://img.shields.io/badge/Target_SDK-36-informational.svg)
![Java](https://img.shields.io/badge/Java-17-orange.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-JVM-purple.svg)
![Platform](https://img.shields.io/badge/CodeAssist_Plugin_API-4-lightgrey.svg)
![Version](https://img.shields.io/badge/Version-1.0.75-success.svg)
![Status](https://img.shields.io/badge/Status-Early_development-yellow.svg)

> **Status:** early development. The source code will be published in this repository soon. This README documents the project's scope, features, and planned architecture ahead of the initial code drop.

---

## Table of Contents

- [Why One Code For AI?](#why-one-code-for-ai)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Planned Architecture](#planned-architecture)
- [Roadmap](#roadmap)
- [Requirements](#requirements)
- [License](#license)

---

## Why One Code For AI?

Large language models and code-review tools work best when they receive the **right context** — not the whole repository. One Code For AI gives you precise control over what gets included:

- **Filters that make sense** — by extension, name, relative path, or glob pattern (`*`, `?`, `**`).
- **Respects your project** — root `.gitignore`, binary detection, per-file size limits, and manual path exclusion.
- **Works on Git snapshots** — export a branch, tag, or commit without touching your working tree.
- **Human-friendly output** — a readable directory tree plus clearly delimited file sections.

No more copy-pasting files one by one.

---

## Features

### Export & Filtering
- Generate the whole project or a selected folder with a single action.
- Automatic Git root detection when inside a repository.
- Inclusion and exclusion rules by **extension**, **name**, **relative path**, and **glob pattern** (`*`, `?`, `**`).
- Root `.gitignore` support (including negation rules such as `!notes.md`).
- Automatic binary-file exclusion.
- Optional per-file size limit (KB) — the checkbox disables the limit entirely.
- Manual path exclusion for individual files or whole directories.
- Automatic cleanup of stale `OneCodeForAI_Share_Latest (n).txt` files before each share run.

### Project Tree & Statistics
- Interactive project tree view with file/folder selection, expand/collapse, manual reload.
- **Live statistics** displayed in the result panel once generation completes: file count, total bytes, and estimated tokens (≈ 4 UTF-8 bytes per token, labelled as an estimate).

### Git Integration
- List and resolve **local and remote branches**, **tags**, and **recent commits**.
- Export the current state of any reference.
- **Diff preview** against the working tree before exporting.

### Profiles & Preferences
- Built-in profiles for **Android/Kotlin**, **Web**, **Python**, and **plain text**.
- Persistent custom profiles with last-saved state restored on next launch.
- Global preferences for Git, tree/statistics, `.gitignore`, and binaries.

### Output, Sharing & Notifications
- Single **Generate** flow: one tap builds the file, then dedicated **Share / Save / Regenerate** chips appear.
- Inline progress panel inside the same dialog — cancel at any time.
- The dialog can be minimized while generation runs; work continues in the background.
- **Persistent interactive notification** with Share / Save / Regenerate actions once the file is ready.
- Reopening the configuration screen shows live progress or the last result, even if the dialog was dismissed mid-export.
- Files are saved to `Downloads/OneCodeForAI`; the system share sheet is one tap away.

### Localization
- Full UI available in **English** and **Spanish** — including the plugin info page, help, FAQ, and guide.

---

## Tech Stack

| Category | Technology |
|---|---|
| Language | Kotlin (JVM) |
| Min SDK | 26 — Android 8.0 Oreo |
| Compile / Target SDK | 36 |
| Java language level | 17 |
| Host platform | CodeAssist Plugin API **4** |
| Git access | JGit `7.8.0.202609011348-r` |
| Module format | CodeAssist module (`plugin/module.toml`) |

> The project deliberately avoids a parallel Gradle project and introduces no new UI dependencies.

---

## Planned Architecture

The plugin is organized into **three packages** with a single responsibility each, plus the plugin entry point and the info activity. This keeps the core logic fully testable outside of Android and the Plugin API.

> **Note:** the layout below reflects the planned structure of the source code, not files currently present in this repository.

| Layer | Purpose | Rules |
|---|---|---|
| **`core/`** | Pure Kotlin logic: filtering, tree serialization, dump generation, JGit adapter, presets persistence, JSON codec for `FilterConfig`. | **No** `android.*`, `Context`, `Activity`, views, or Plugin API imports. Flat package — no single-file subpackages. |
| **`android/`** | Host access and delivery: reflection on host `Application`/`Activity`, async coordination, `MediaStore` writes with duplicate cleanup, notifications, `Toast`, share chooser, observable export state. | May depend on Android and the Plugin API. |
| **`ui/`** | Configuration dialog, inline progress/result panel, settings page, interactive tree, Git reference picker, diff view, theme, and shared layout helpers. | Programmatic Android views only — no XML layouts. |
| **Root** | Entry point (`OneCodeForAIPlugin`), info page (`OneCodeForAIInfoActivity`), and the notification action receiver (`ExportActionReceiver`). | Registers action + settings page, resolves selected folder, reads global preferences, opens the dialog; routes notification actions back into the coordinator. |

### Key design decisions

- **Single source of truth for filtering.** Preview and export both consult `FileFilter`. The `includePath(...)` API lets the same rules apply to files coming from a Git reference that may not exist in the working tree.
- **Streaming export.** Bounded read/write buffers; the tree is written progressively using iterative traversal.
- **Single-session Git reading.** `GitUtils.withSnapshot` enumerates the chosen reference's blobs inside a single JGit session — no repeated repo opens.
- **Observable export state.** `ExportStateHolder` is a process-wide singleton that publishes immutable `Snapshot`s. The dialog subscribes to it and renders whatever state currently exists — this is what allows dismissing and reopening the dialog mid-export without losing progress.
- **Single global lock.** `ExportCoordinator.Companion` owns an `AtomicBoolean` that prevents concurrent runs regardless of whether the request comes from the dialog, a notification action, or a future entry point.
- **Cooperative cancellation.** `ExportCoordinator.cancelCurrent()` interrupts the worker; `CodeDumpGenerator` checks `Thread.isInterrupted` between files and between write chunks, so the worker unwinds promptly even on large projects.
- **Idempotent file writes with duplicate cleanup.** Before writing the canonical share file, `ExportCoordinator` deletes any `OneCodeForAI_Share_Latest (n).txt` left by previous runs, so the user always sees a single file in Downloads.
- **Compatible persistence.** Every existing preference key, JSON property, and profile name is preserved across updates. New fields (`useMaxSize`) default to backward-compatible values.

---

## Roadmap

- [ ] Publish the source code in this repository
- [ ] Provide a sample `.txt` dump in `docs/samples/`
- [ ] Publish the plugin on the CodeAssist plugin registry
- [ ] v1.0.71 — first public release

---

## Requirements

- **Android** — API 26 (Android 8.0 Oreo) or higher.
- **CodeAssist** — with **Plugin API 4** support.
- **Java** — 17 (for compiling the plugin module).
- **Compile SDK / Target SDK** — 36.
- **JGit** — `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r`.

---

## License

MIT License — see [LICENSE](LICENSE) for the full text.

Copyright (c) 2026 DaytonPlus

---

<p align="center">
  <sub>Built with ❤️ for the CodeAssist community.</sub>
</p>