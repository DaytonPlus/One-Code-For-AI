# 🚀 One Code For AI

**Turn your CodeAssist project into a single, AI-ready `.txt` file in seconds.**

One Code For AI is a plugin for the CodeAssist IDE that scans a project or selected folder, applies configurable filters, and generates a structured `.txt` file containing a directory tree and the contents of the selected files. It is designed to give LLMs clean context without the tedious process of copying files one by one.

> Stop copy-pasting files. Share a clean, reviewable project context with ChatGPT, Claude, Gemini, or any other AI tool.

---

## ✨ What you get

- **One file, full context.** The entire project — or a carefully filtered subset — becomes a single reviewable `.txt`.
- **Interactive project tree.** Mark each file as Included, Reference-only, or Excluded from the tree itself. The selection is what gets exported, and it is preserved per project.
- **Reference-only files.** List a file by name and size in the dump without reading its content. Ideal for lockfiles, generated code, and machine-local configuration.
- **Smart filtering.** Exclude binaries and generated output automatically, respect `.gitignore`, or write your own glob rules.
- **Git-aware.** Export from any branch, tag, or commit, or preview what changed before generating.
- **Multi-language UI.** 18 languages, selectable independently of the IDE language.
- **100% offline.** No internet permission, no telemetry, no cloud upload.

---

## 🌳 Interactive project tree

The project tree is the source of truth for what goes into the dump. Every entry can be set to one of three states:

- **Included** — the file's full content is written to the dump.
- **Reference only** — the file's name and size are listed, but its content is not read.
- **Excluded** — the file is not written to the dump.

Tap any row to cycle between the three states. Tap a folder to apply the next state to its whole subtree, preserving any manual overrides already present on individual files. Parent and child states stay consistent both ways: marking the last included child of a folder unchecks the folder, and marking a folder re-checks its children.

Hard-excluded folders (`node_modules`, `build`, `.git`, …) and binary files appear as **locked** nodes: visible in the tree but not interactive until you enable "Show locked". Locking is per-session and safe — unlocking a folder does not automatically include anything inside it; you still have to mark files explicitly.

Your manual tree decisions are stored per project in `.platform/plugins/com.devdayton.onecodeforai/config.json`, so each project keeps its own selection independent of the preset. Closing the dialog without pressing Generate discards the manual changes, matching the standard "commit on Generate" model.

---

## 📁 Smart export and filtering

- Export an entire project or a selected folder.
- Filter by extension, filename, relative path, or glob patterns like `*.log`, `build/`, or `**/generated/**`.
- Mark specific files or file families as reference-only, so the model knows they exist without receiving their content.
- Respect root `.gitignore` rules, including override behavior.
- Exclude binary files automatically.
- Preview the project tree before export, and manually adjust each entry's state.
- Configure per-file and total export size limits.

---

## 🌿 Git integration

- Browse local and remote branches, tags, and recent commits.
- Export a Git snapshot without modifying the working tree.
- Preview the diff against the current working tree.
- View the current branch, commit, and repo status.
- Disable Git globally while keeping `.gitignore` filtering available.

---

## 📱 Built-in presets and settings

- Professional presets for Android (Kotlin), Android (source only), Android (resources only), Web, Python, and Plain text.
- Built-in presets use reference-only entries for generated files and lockfiles, so the dump stays structurally complete without paying the token cost of those files' contents.
- Save and restore your own custom profiles.
- Configure Git, animations, notifications, filtering behavior, and export limits from a native settings page.
- Use contextual help inside the UI for filters and `.gitignore` rules.

---

## 🌍 Multi-language interface

The plugin UI is available in **18 languages**, selectable from a dedicated dropdown in the settings page. Each language is listed by its native name (Español, Français, Deutsch, 日本語, 中文, …) so users can find theirs without reading English.

- **Auto (follow IDE)** is the default: the plugin uses whatever language CodeAssist is running in.
- A specific language can be forced for the plugin only, independently of the IDE language.
- The selected language applies to the next newly-opened dialog or screen. Already-open windows keep their previous language until reopened, matching standard Android behavior.

Supported languages:

- English
- Español (Spanish)
- Français (French)
- Deutsch (German)
- Italiano (Italian)
- Português — Brasil (Portuguese)
- Русский (Russian)
- Polski (Polish)
- Čeština (Czech)
- Română (Romanian)
- Ελληνικά (Greek)
- Türkçe (Turkish)
- العربية (Arabic)
- हिन्दी (Hindi)
- Bahasa Indonesia
- Tiếng Việt (Vietnamese)
- 日本語 (Japanese)
- 한국어 (Korean)
- 中文 (Chinese, Simplified)

> **Coverage note:** The primary user flow (configuration dialog, project tree, result panel, settings page, error and notification strings) is fully translated in every language listed above. The "What's New" screen, the installation guide, and the help/FAQ section currently fall back to English in some of the newer translations; a follow-up translation pass is planned.

---

## ⚡ Reliability and UX

- Background generation and progress tracking.
- Cancel export while it is running.
- Minimize the dialog without losing progress.
- Save, share, or regenerate the final output.
- Clear warnings for unreadable files or incomplete exports.
- Persistent recovery behavior for previous valid output.
- Robust tree loading: cancelling a scan, superseding it with a filter change, or hitting an error always restores the correct panel state, with no orphan spinners.

---

## 📦 Output format

The generated file is a readable `.txt` document that includes:

1. A directory tree of the included files, with a `[ref]` suffix next to reference-only entries.
2. Clearly separated file sections, each headed by the file's relative path, containing either the file's content or a `[content omitted: N B - reference only]` marker.
3. Final stats for file count, total size, and estimated token count.

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

Your tree selection is saved to the project and restored the next time you open the dialog. Pressing Generate is what commits the current tree to the project's config.

---

🔒 Privacy and security

· Everything happens locally on your device.
· No internet permission is required.
· No project files are uploaded to a server.
· No telemetry is sent by the plugin.
· The export process runs entirely inside the CodeAssist environment.

---

🧱 Project structure (for developers)

The plugin is organized into a few main areas:

· core/ — filtering, Git handling, dump generation, file scanning, profile logic, tree overrides, and the per-project tree config store.
· android/ — host integration, file access, notifications, and export coordination.
· ui/ — configuration dialog, project tree UI, settings, help, result panels, and the tree's custom scroll views.
· Root package — plugin entry point and info screen.

---

🆕 What's new in 1.1.0

· Tree v2: the project tree is now the source of truth for what goes into the dump. Mark each file as Included, Reference-only, or Excluded from the tree itself. Parent and child states stay consistent both ways, and manual overrides are preserved when you cycle a folder.
· Reference-only files: a new third inclusion state lets you list a file in the dump — name and size — without reading its content. Ideal for lockfiles, generated code, and machine-local configuration. Configured both by pattern (in the new Reference only section of the dialog) and per file (in the tree). The dump marks them with a [ref] suffix in the tree header and a [content omitted: N B - reference only] marker in the body.
· Per-project tree config: your tree selection is saved to .platform/plugins/com.devdayton.onecodeforai/config.json inside the project, so each project keeps its own decisions independent of the preset. Only written on Generate; closing the dialog without generating discards manual changes.
· Show locked toggle: hard-excluded folders (node_modules, build, .git, …) and binary files appear as locked nodes. Enabling "Show locked" makes them interactive. Locking is per-session and safe: unlocking does not automatically include anything inside the folder.
· Tri-state indicator: every row shows its state with a custom tri-state glyph (checkmark for Included, dash for Reference only, empty for Excluded). The indicator is visible from the first frame, without waiting for a user interaction.
· Built-in presets updated: Android, Web, and Python presets now use reference-only entries for R.java, BuildConfig.java, local.properties, package-lock.json, pnpm-lock.yaml, poetry.lock, and Pipfile.lock. The dump stays structurally complete without paying the token cost of those files' contents.
· Tree loading robustness: cancelling a scan, superseding it with a filter change, or hitting an error always restores the correct panel state. The Cancel button and the Reload action both work reliably.
· Preset restoration: the preset spinner now reads its initial selection from the project's config.json instead of from SharedPreferences, so the correct preset is always shown for the correct project.

---

✅ Requirements

· Android 8.0 (API 26) or higher.
· CodeAssist with Plugin API 4 support.
· Java 17 for compiling the plugin module.

---

📄 License

This project is licensed under the MIT License. See the LICENSE file in the repository for details.

---

🔗 Links

· GitHub: https://github.com/daytonplus/one-code-for-ai
· Issues: https://github.com/daytonplus/one-code-for-ai/issues

---

Built with ❤️ for the CodeAssist community.
