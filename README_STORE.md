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
- **Dark by default.** A dedicated theme selector lets you follow the device theme, or force the plugin to always use light or dark.
- **Multi-language UI.** 27 languages, selectable independently of the IDE language.
- **100% offline.** No internet permission, no telemetry, no cloud upload.

---

## 🌳 Interactive project tree

The project tree is the source of truth for what goes into the dump. Every entry can be set to one of three states:

- **Included** — the file's full content is written to the dump.
- **Reference only** — the file's name and size are listed, but its content is not read.
- **Excluded** — the file is not written to the dump.

Click a row to cycle through the three states. Click a folder to **force the next state onto everything inside it** — folders and files, at every depth — so the folder's tri-state indicator never disagrees with its children. Marking a deep file updates every ancestor's aggregate all the way to the root, without ever touching the ancestors' other children. Parent and child states stay consistent in both directions.

Hard-excluded folders (`node_modules`, `build`, `.git`, …) and binary files appear as **locked** nodes: visible in the tree but not interactive until you enable "Show locked". Enabling the toggle makes them interactive and scans a newly-unlocked folder's contents on demand, showing every child — including build outputs such as APKs, `.dex` files and binaries. The toggle survives filter changes and manual reloads, so a rescan with "Show locked" on leaves the new tree interactive. Disabling it re-locks every originally-locked node and discards anything that was included while unlocked, so Generate never exports a file you can no longer see.

Your manual tree decisions are stored per project in `.platform/plugins/com.devdayton.onecodeforai/config.json`, so each project keeps its own selection independent of the preset. Closing the dialog without pressing Generate discards the manual changes, matching the standard "commit on Generate" model.

---

## 📁 Smart export and filtering

- Export an entire project or a selected folder.
- Filter by extension, filename, relative path, or glob patterns like `*.log`, `build/`, or `**/generated/**`.
- Mark specific files or file families as reference-only, so the model knows they exist without receiving their content.
- Respect root `.gitignore` rules, including override behavior. The checkbox that enables this now lives inside the `.gitignore` section itself, right above the chips it controls, so the causal relationship is obvious.
- Exclude binary files automatically.
- Preview the project tree before export, and manually adjust each entry's state.
- Configure per-file and total export size limits.

---

## ⚙️ Section toggles

Every rule list is a **toggleable section**: a checkbox with an uppercase title, an optional Clear button, an optional "+ Add" button, a "?" help icon, and a body that appears only when the checkbox is checked.

Unchecking Inclusions, Exclusions, or Reference only disables that list **without deleting it** — the rules stay in the profile and are reapplied the moment you check the box again. No re-typing required.

A **Clear** button next to Add empties an editable flow in one tap. Emptying a flow counts as a rule change and switches the profile to Custom. Gitignore has no Clear button: the flow is read-only.

Base flags (Exclude binary files, Maximum size) live under their own **General** section at the top of the form. The uppercase section titles are the only block boundaries — no hairlines between rule sections — so the panel stays short enough to fit on a phone screen without scrolling past chrome.

---

## 🌿 Git integration

- Browse local and remote branches, tags, and recent commits.
- Export a Git snapshot without modifying the working tree.
- Preview the diff against the current working tree.
- View the current branch, commit, and repo status.
- Disable Git globally while keeping `.gitignore` filtering available.

---

## 🎨 Light and dark themes

The plugin UI ships with a light and a dark palette. The dark palette is the default and preserves the original hand-tuned colors; the light palette keeps the same visual identity (warm accent, muted text hierarchy, soft outlines) on a light background.

- The theme can be set to **Dark** (the default), **System** (follow the device's light/dark mode), or **Light** from the settings page.
- Only newly opened dialogs and screens pick up a change; already-open windows keep their palette until reopened, matching the standard Android behavior for runtime theme changes.

---

## 📱 Built-in presets and settings

- Professional presets for Android (Kotlin), Android (source only), Android (Resources), Web, Python, and Plain text.
- Built-in presets use reference-only entries for generated files and lockfiles, so the dump stays structurally complete without paying the token cost of those files' contents.
- Save and restore your own custom profiles.
- Configure Git, animations, notifications, UI language, UI theme, export limits, and the parent folder search limit from a native settings page.
- Use contextual help inside the UI: Export profile, Version control, Inclusions, Exclusions, Reference only, Gitignore, Binary exclusion, Project tree, Locked nodes, and Per-file size limit all expose a "?" affordance with an inline explanation.

---

## 🌍 Multi-language interface

The plugin UI is available in **27 languages**, selectable from a dedicated dropdown in the settings page. Each language is listed by its native name (Español, Français, Deutsch, 日本語, 中文, …) so users can find theirs without reading English.

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
- বাংলা (Bengali)
- Català (Catalan)
- فارسی (Persian)
- עברית (Hebrew)
- Nederlands (Dutch)
- Svenska (Swedish)
- ไทย (Thai)
- Українська (Ukrainian)

> **Coverage note:** The primary user flow (configuration dialog, project tree, result panel, settings page, error and notification strings) is fully translated in every language listed above. The "What's New" screen, the installation guide, and the help/FAQ section currently fall back to English in the newest additions (Bengali, Catalan, Persian, Hebrew, Dutch, Swedish, Thai, Ukrainian); a follow-up translation pass is planned.

---

## ⚡ Reliability and UX

- Background generation and progress tracking.
- Cancel export while it is running.
- Minimize the dialog without losing progress.
- Save, share, or regenerate the final output.
- Clear warnings for unreadable files or incomplete exports.
- Persistent recovery behavior for previous valid output.
- Robust tree loading: cancelling a scan, superseding it with a filter change, or hitting an error always restores the correct panel state. No orphan "Analyzing…" spinners, and the Cancel button is only shown while a scan is running.
- Project root is resolved by a three-step heuristic (`.gitignore` search, CodeAssist project boundary, fallback to the selected folder), bounded by a user-configurable parent folder search limit (default 10, range 0–50). Set it to 0 to keep the plugin strictly scoped to the folder you selected.

---

## 📦 Output format

The generated file is a readable `.txt` document that includes:

1. A directory tree of the included files, with a `[ref]` suffix next to reference-only entries.
2. Clearly separated file sections, each headed by the file's relative path, containing either the file's content or a `[content omitted: N B — reference only]` marker.
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
· ui/ — configuration dialog, project tree UI, settings, help, result panels, theme palette, and the tree's custom scroll views.
· Root package — plugin entry point and info screen.

---

🆕 What's new in 1.2.0

· Eight new languages: Bengali, Catalan, Persian, Hebrew, Dutch, Swedish, Thai and Ukrainian. The plugin now speaks 27 languages (26 translations plus English), selectable independently of the IDE language from the settings page.

· Right-to-left support for Arabic, Persian and Hebrew comes from Android's automatic layout mirroring, so no extra configuration is needed and the visual identity of the plugin is preserved in every direction.

· Built-in presets now open with a section toggle off when the preset has no rules in that section. "Android (Kotlin)" opens with Exclusions off; "Android (Resources)" and "Plain Text" open with Exclusions and Reference only off. The remaining built-in profiles already ship rules in every section and are unchanged. Turning a section on by hand still works, and still switches the profile spinner to Custom, as before.

· No behavioral changes to the export pipeline, the filtering logic, the Git integration, or the storage layer. This release is a pure i18n expansion plus the preset toggle initial state and the documentation needed to keep the new languages maintainable.

---

🆕 What's new in 1.1.0

· Tree v2: the project tree is now the authoritative source of truth for what goes into the dump. Files can be marked Included, Reference-only, or Excluded from the tree itself, and the selection is preserved per project.
· Reference-only files: a new third inclusion state lets you list a file in the dump — name and size — without reading its content. Ideal for lockfiles, generated code, and machine-local configuration. Configured both by pattern (in the Reference only section of the dialog) and per file (in the tree). The dump marks them with a [ref] suffix in the tree header and a [content omitted: N B — reference only] marker in the body.
· Uniform folder cycling: clicking a folder forces the next state onto every non-locked descendant at every depth, so the folder's tri-state indicator never disagrees with its children. Marking a deep file updates every ancestor's aggregate all the way to the root, without touching the ancestors' other children.
· Show locked: hard-excluded folders and binary files appear as locked nodes; enabling "Show locked" makes them interactive and scans a newly-unlocked folder's contents on demand, showing every child including APKs, .dex and binaries. The toggle survives filter changes and reloads: a rescan with "Show locked" on leaves the tree interactive. Disabling it re-locks everything and discards what was included while unlocked.
· New General section: "Exclude binary files" and "Maximum size" now live under their own uppercase title at the top of the form.
· Section toggles: Inclusions, Exclusions, Reference only, Gitignore, Version control, and File tree all follow the same checkbox + uppercase title + help pattern. Unchecking Inclusions, Exclusions, or Reference only disables the list without deleting it. A Clear button next to Add empties an editable flow in one tap and switches the profile to Custom; Gitignore has no Clear because its flow is read-only.
· Uppercase section headers and a single hairline separator below the preset spinner, with no dividers between rule sections, so the panel stays short and minimalist on a phone screen.
· Dark theme is now the default. A fresh install opens on the plugin's original hand-tuned palette; existing users who explicitly picked a theme keep their stored choice.
· Info screen redesigned: features grouped into four categories, per-row icon count cut from roughly 25 to 10, and a hero block with a version and status chip row.
· Project root resolution: three-step heuristic (.gitignore search, CodeAssist project boundary, fallback to the selected folder), bounded by the new "Parent folder search limit" setting.
· .platform directory hard-excluded from the dump, alongside .git, .gradle, and .idea, so the plugin's own tracking file cannot end up in the model's context.
· Tree loading and cancellation are robust: no orphan "Analyzing…" spinners, Cancel only shown while a scan is running, and Reload expands the section first if it was collapsed.
· Contextual help on every section, and every toggle section's help body now explains that unchecking the box disables the list without deleting it.
· Built-in presets use reference-only entries for generated files and lockfiles, keeping the dump structurally complete without paying the token cost of their contents.

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
