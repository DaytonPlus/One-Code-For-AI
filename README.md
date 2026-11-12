# 🚀 One Code For AI

**Turn your CodeAssist project into a single, AI-ready `.txt` file in seconds.**

One Code For AI is a robust Android plugin for the CodeAssist IDE that scans a project or selected folder, applies configurable filters, and generates a structured `.txt` file containing a directory tree and the contents of the selected files. It is designed to give LLMs clean context without the tedious process of copying files one by one.

[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](https://github.com/daytonplus/one-code-for-ai/blob/main/LICENSE)
[![Min SDK](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=for-the-badge)]()
[![Target SDK](https://img.shields.io/badge/Target_SDK-36-informational.svg?style=for-the-badge)]()
[![Java](https://img.shields.io/badge/Java-17-orange.svg?style=for-the-badge)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-JVM-purple.svg?style=for-the-badge)]()
[![CodeAssist Plugin API](https://img.shields.io/badge/Plugin_API-4-lightgrey.svg?style=for-the-badge)]()
[![Version](https://img.shields.io/badge/Version-1.1.0-success.svg?style=for-the-badge)]()

> Stop copy-pasting files. Share a clean, reviewable project context with ChatGPT, Claude, Gemini, or any other AI tool.

---

## Why One Code For AI?

AI assistants work much better with the right context. Instead of manually selecting files or pasting long snippets, this plugin:
- exports your project as one structured text file,
- preserves the source tree for review,
- filters out noise like binaries and generated output,
- keeps the result local on your device.

No cloud upload. No telemetry. Just clean AI context.

---

## ✨ Features

### 📁 Smart export and filtering
- Export an entire project or a selected folder.
- Filter by extension, filename, relative path, or glob patterns like:
  - `*.log`
  - `build/`
  - `**/generated/**`
- Respect root `.gitignore` rules, including override behavior. The checkbox that enables this now lives inside the `.gitignore` section itself, right above the chips it controls.
- Exclude binary files automatically.
- Preview the project tree before export.
- Manually include, stub, or exclude individual files and folders.
- Configure per-file and total export size limits.

### 🌳 Interactive project tree (Tree v2)
The file tree is now the **authoritative source of truth** for what goes into the dump, not just a preview. Every entry can be set to one of three states:

- **Included** — the file's full content is written to the dump.
- **Reference only** — the file's name and size are listed, but its content is not read. Ideal for lockfiles, generated code, and machine-local configuration.
- **Excluded** — the file is not written to the dump.

Click any row to cycle through the three states. Click a folder to apply the next state to its whole subtree — but only to children whose current state matches the folder's aggregate, so your manual exclusions are preserved. Parent and child states stay consistent both ways: marking the last included child of a folder unchecks the folder, and marking a folder re-checks its children.

Hard-excluded folders (`node_modules`, `build`, `.git`, …) and binary files appear as **locked** nodes: visible in the tree but not interactive until you enable "Show locked". Locking is per-session and safe — unlocking a folder does not automatically include anything inside it; you still have to mark files explicitly.

Your manual tree decisions are stored per project in `.platform/plugins/com.devdayton.onecodeforai/config.json`, so each project keeps its own selection independent of the preset. Closing the dialog without pressing Generate discards the manual changes, matching the standard "commit on Generate" model.

### 🌿 Git integration
- Browse local and remote branches, tags, and recent commits.
- Export a Git snapshot without modifying the working tree.
- Preview the diff against the current working tree.
- View the current branch, commit, and repo status.
- Disable Git globally while keeping `.gitignore` filtering available.

### 🎨 Theme support
- The plugin UI ships with a **light** and a **dark** palette. The dark palette preserves the original hand-tuned colors; the light palette keeps the same visual identity (warm accent, muted text hierarchy, soft outlines) on a light background.
- The theme can be set to **System** (follow the device's light/dark mode — the default), **Light**, or **Dark** from the settings page.
- Only newly opened dialogs and screens pick up a change; already-open windows keep their palette until reopened, matching the standard Android behavior for runtime theme changes.

### 📱 Built-in presets and settings
- Professional presets for:
  - Android (Kotlin)
  - Android (source only)
  - Android (resources only)
  - Web
  - Python
  - Plain text
- Save and restore custom profiles.
- Configure Git, animations, notifications, UI language, UI theme, export limits, and the parent-folder search limit from a single settings page.
- Use contextual help inside the UI: Export profile, Version control, Inclusions, Exclusions, Reference only, Gitignore rules, Binary exclusion, Project tree, Locked nodes, and Per-file size limit all expose a "?" affordance with an inline explanation.
- Built-in presets now use **reference-only** entries for generated files and lockfiles — `R.java`, `BuildConfig.java`, `local.properties`, `package-lock.json`, `pnpm-lock.yaml`, `poetry.lock`, `Pipfile.lock`. The dump stays structurally complete without paying the token cost of those files' contents.

### 🌍 Multi-language interface
- The plugin UI is available in **18 languages**, selectable from a dedicated dropdown in the settings page. Each language is listed by its native name (Español, Français, Deutsch, 日本語, 中文, …) so users can find theirs without reading English.
- **Auto (follow IDE)** is the default: the plugin uses whatever language CodeAssist is running in. No configuration required.
- A specific language can be forced for the plugin only, independently of the IDE language. This is useful when following tutorials or documentation written in a different language than your IDE.
- The selected language applies to the next newly-opened dialog or screen. Already-open windows keep their previous language until reopened, matching standard Android behavior for runtime locale changes.
- Existing users who had the previous "Force English UI" toggle enabled are migrated automatically to the language selector — no one loses their choice after the upgrade.

### ⚡ Reliability and UX
- Background generation and progress tracking.
- Cancel export while it is running.
- Minimize the dialog without losing progress.
- Save, share, or regenerate the final output.
- Clear warnings for unreadable files or incomplete exports.
- Persistent recovery behavior for previous valid output.
- The tree's loading state is now robust: cancelling a scan, superseding it with a filter change, or hitting an error always restores the correct panel state. No orphan spinners, and the Cancel button is only shown while a scan is running.
- The project root is resolved by a three-step heuristic (`.gitignore` search, CodeAssist project boundary, fallback to the selected folder), bounded by a user-configurable parent folder search limit (default 10, range 0–50). Set it to 0 to keep the plugin strictly scoped to the folder you selected.

---

## 🌐 Supported languages

| Language | Code | Native name |
|---|---|---|
| Auto (follow IDE) | `auto` | — |
| English | `en` | English |
| Arabic | `ar` | العربية |
| Chinese (Simplified) | `zh` | 中文 |
| Czech | `cs` | Čeština |
| French | `fr` | Français |
| German | `de` | Deutsch |
| Greek | `el` | Ελληνικά |
| Hindi | `hi` | हिन्दी |
| Indonesian | `in` | Bahasa Indonesia |
| Italian | `it` | Italiano |
| Japanese | `ja` | 日本語 |
| Korean | `ko` | 한국어 |
| Polish | `pl` | Polski |
| Portuguese (Brazil) | `pt` | Português (Brasil) |
| Romanian | `ro` | Română |
| Russian | `ru` | Русский |
| Spanish | `es` | Español |
| Turkish | `tr` | Türkçe |
| Vietnamese | `vi` | Tiếng Việt |

> **Coverage note:** The primary user flow (configuration dialog, project tree, result panel, settings page, error and notification strings) is fully translated in every language listed above. The "What's New" screen, the installation guide, and the help/FAQ section currently fall back to English in some of the newer translations; a follow-up translation pass is planned.

---

## 📦 Output format

The generated file is a readable `.txt` document that includes:
1. a directory tree (with a `[ref]` suffix next to reference-only entries),
2. clearly separated file sections with relative paths, each section being either the file's content or a `[content omitted: N B - reference only]` marker,
3. final stats for file count, total size, and estimated token count.

Generated files are saved under the public **Download** folder on your device:

```text
Download/OneCodeForAI/
```

---

🛠️ How it works

1. Open CodeAssist.
2. Select the project or folder you want to include.
3. Launch the plugin.
4. Choose a preset or custom filters. The tree shows exactly what the preset selects.
5. Adjust the tree manually if needed: cycle files between included, reference-only, and excluded; enable "Show locked" to unlock hard-excluded folders.
6. Export the final .txt file.
7. Share it with your AI tool of choice.

The tree selection is saved to the project so the next open restores your previous decisions. Pressing Generate is what commits the current tree to the project's config, matching the standard commit-on-generate model.

---

🔒 Privacy and security

· Everything happens locally on your device.
· No internet permission is required.
· No project files are uploaded to a server.
· No telemetry is sent by the plugin.
· The export process runs entirely inside the CodeAssist environment.

---

🧱 Project structure

The plugin is organized into a few main areas:

· core/ — filtering, Git handling, dump generation, file scanning, profile logic, tree overrides, and the per-project tree config store
· android/ — host integration, file access, notifications, and export coordination
· ui/ — configuration dialog, project tree UI, settings, help, result panels, theme palette, and the tree's custom scroll views
· root package — plugin entry point and info screen

---

🆕 What's new in 1.1.0

· Tree v2: the project tree is now the authoritative source of truth for what goes into the dump. Files can be marked Included, Reference-only, or Excluded from the tree itself, and the selection is preserved per project.
· Reference-only files: a new third inclusion state lets you list a file in the dump — name and size — without reading its content. Ideal for lockfiles, generated code, and machine-local configuration. Configured both by pattern (in the new Reference only section of the dialog) and per file (in the tree).
· Per-project tree config: the tree selection is saved to .platform/plugins/com.devdayton.onecodeforai/config.json inside the project, so each project keeps its own decisions independent of the preset. Only written on Generate; closing the dialog without generating discards manual changes.
· Show locked toggle: hard-excluded folders (node_modules, build, .git, …) and binary files appear as locked nodes in the tree. Enabling "Show locked" makes them interactive. Locking is per-session and safe — unlocking does not automatically include anything inside the folder.
· Tri-state indicator: every row shows its state with a custom tri-state glyph (checkmark for Included, dash for Reference only, empty for Excluded). Cycling a folder advances its whole subtree, but only touches descendants whose current state matches the folder's aggregate, preserving manual overrides.
· Built-in presets updated: Android, Web, and Python presets now use reference-only entries for R.java, BuildConfig.java, local.properties, package-lock.json, pnpm-lock.yaml, poetry.lock, and Pipfile.lock. The dump stays structurally complete without paying the token cost of those files' contents.
· Tree loading robustness: cancelling a scan, superseding it with a filter change, or hitting an error now always restores the correct panel state. The Cancel button and the Reload action both work reliably.
· Initial render fix: the tri-state indicator and lock glyphs are visible from the first frame, without waiting for a user interaction to trigger a refresh.
· Preset restoration: the preset spinner now reads its initial selection from the project's config.json instead of SharedPreferences, so the correct preset is always shown for the correct project.
· Theme selector: the plugin UI now supports a light and a dark palette, resolved at every UI entry point. A new Theme selector in the settings page offers System (follow device), Light, and Dark; the default is System.
· Dark and light palettes: the dark palette preserves the original hand-tuned colors. The light palette introduces a lighter background, softer outlines, and a slightly darker accent for legibility on light backgrounds.
· Help icons for every section: Export profile, Version control, Inclusions, Exclusions, Reference only, Gitignore rules, Binary exclusion, Project tree, Locked nodes, and Per-file size limit now all expose a "?" affordance with an inline explanation.
· Help icon alignment: the "?" icon now sits flush with the right edge of its section title or panel instead of inheriting the frame's extra padding on top of the section's own. The touch target is preserved.
· Gitignore section redesign: the "Respect .gitignore rules" checkbox has been moved inside the .gitignore section, directly below the title and above the flow of chips. Toggling it off clears the flow in place (no dialog reload, no tree re-scan); toggling it back on repopulates the chips from the cached rules.
· Configurable parent folder search limit: the plugin used to walk exactly 5 parent folders looking for a .gitignore. The limit is now a user setting (default 10, clamped to 0–50), exposed in the settings page as "Parent folder search limit". A value of 0 makes the plugin strictly scoped to the selected folder.
· Project root resolution: new three-step heuristic (.gitignore search, CodeAssist project boundary detection, fallback to the selected folder). Used both when Git is disabled and as the fallback when JGit cannot find a repository.
· .platform excluded from the dump: the plugin's own per-project config directory (.platform/plugins/com.devdayton.onecodeforai/) is now hard-excluded from the dump, alongside .git, .gradle, and .idea. Users who need to inspect it can enable "Show locked" and mark it explicitly.
· Tree opening fix: the tree section no longer stays on "Analyzing…" forever on first open. The section is marked visible immediately, and the Cancel button is only shown while a scan is running.

---

✅ Requirements

· Android 8.0 (API 26) or higher
· CodeAssist with Plugin API 4 support
· Java 17 for compiling the plugin module

---

📄 License

This project is licensed under the MIT License.

See LICENSE for details.

---

🔗 Links

· GitHub: https://github.com/daytonplus/one-code-for-ai
· Issues: https://github.com/daytonplus/one-code-for-ai/issues

---

Built with ❤️ for the CodeAssist community.
