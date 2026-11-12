# 🚀 One Code For AI

**Turn your CodeAssist project into a single, AI-ready `.txt` file in seconds.**

One Code For AI is a robust Android plugin for the CodeAssist IDE that scans a project or selected folder, applies configurable filters, and generates a structured `.txt` file containing a directory tree and the contents of the selected files. It is designed to give LLMs clean context without the tedious process of copying files one by one.

[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](https://github.com/daytonplus/one-code-for-ai/blob/main/LICENSE)
[![Min SDK](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=for-the-badge)]()
[![Target SDK](https://img.shields.io/badge/Target_SDK-36-informational.svg?style=for-the-badge)]()
[![Java](https://img.shields.io/badge/Java-17-orange.svg?style=for-the-badge)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-JVM-purple.svg?style=for-the-badge)]()
[![CodeAssist Plugin API](https://img.shields.io/badge/Plugin_API-4-lightgrey.svg?style=for-the-badge)]()
[![Version](https://img.shields.io/badge/Version-1.2.0-success.svg?style=for-the-badge)]()

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
- Filter by extension, filename, relative path, or glob patterns like `*.log`, `build/`, and `**/generated/**`.
- Respect root `.gitignore` rules, including override behavior.
- Exclude binary files automatically.
- Preview the project tree before export.
- Manually include, stub, or exclude individual files and folders.
- Configure per-file and total export size limits.

### 🌳 Interactive project tree (Tree v2)
The file tree is the **authoritative source of truth** for what goes into the dump, not just a preview. Every entry can be set to one of three states:

- **Included** — the file's full content is written to the dump.
- **Reference only** — the file's name and size are listed, but its content is not read. Ideal for lockfiles, generated code, and machine-local configuration.
- **Excluded** — the file is not written to the dump.

Clicking a file cycles through the three states. Clicking a folder **forces the next state onto its entire non-locked subtree uniformly**, so after the click the folder and everything inside it share the same state. The folder's tri-state indicator therefore never disagrees with its children, and marking a deep file updates every ancestor's aggregate all the way to the root, without ever touching the ancestors' other children.

Hard-excluded folders (`node_modules`, `build`, `.git`, …) and binary files appear as **locked** nodes: visible in the tree but not interactive until you enable "Show locked". Enabling the toggle makes them interactive and scans a newly-unlocked folder's contents on demand, showing every child — including build outputs such as APKs, `.dex` files and binaries — so you can navigate to anything inside. The toggle survives filter changes and manual reloads: a rescan with "Show locked" on leaves the new tree interactive instead of rebuilding it locked. Disabling it re-locks every originally-locked node and discards anything that was included while unlocked, so Generate never exports a file you can no longer see.

Your manual tree decisions are stored per project in `.platform/plugins/com.devdayton.onecodeforai/config.json`, so each project keeps its own selection independent of the preset. Closing the dialog without pressing Generate discards the manual changes, matching the standard "commit on Generate" model.

### 🌿 Git integration
- Browse local and remote branches, tags, and recent commits.
- Export a Git snapshot without modifying the working tree.
- Preview the diff against the current working tree.
- View the current branch, commit, and repo status.
- Disable Git globally while keeping `.gitignore` filtering available.

### 🎨 Theme support
- The plugin UI ships with a **light** and a **dark** palette. The dark palette preserves the original hand-tuned colors and is the default; the light palette keeps the same visual identity (warm accent, muted text hierarchy, soft outlines) on a light background.
- The theme can be set to **Dark** (the default), **System** (follow the device's light/dark mode), or **Light** from the settings page.
- Only newly opened dialogs and screens pick up a change; already-open windows keep their palette until reopened, matching the standard Android behavior for runtime theme changes.

### 📱 Built-in presets and settings
- Professional presets for Android (Kotlin), Android (source only), Android (Resources), Web, Python, and Plain text.
- Save and restore custom profiles.
- Configure Git, animations, notifications, UI language, UI theme, export limits, and the parent-folder search limit from a single settings page.
- Use contextual help inside the UI: Export profile, Version control, Inclusions, Exclusions, Reference only, Gitignore, Binary exclusion, Project tree, Locked nodes, and Per-file size limit all expose a "?" affordance with an inline explanation.
- Built-in presets use **reference-only** entries for generated files and lockfiles — `R.java`, `BuildConfig.java`, `local.properties`, `package-lock.json`, `pnpm-lock.yaml`, `poetry.lock`, `Pipfile.lock`. The dump stays structurally complete without paying the token cost of those files' contents.

### ⚙️ Section toggles
Every rule list in the configuration dialog is a **toggleable section**: a checkbox with an uppercase title, an optional Clear button, an optional "+ Add" button, a "?" help icon, and a body that appears only when the checkbox is checked. Unchecking Inclusions, Exclusions, or Reference only disables that list without deleting it — the rules stay in the profile and are reapplied the moment you check the box again. A Clear button next to Add empties an editable flow in one tap; emptying a flow counts as a rule change and switches the profile to Custom. The Gitignore section uses the same shape, with the "Respect .gitignore rules" checkbox acting as the section header, directly above the rules it controls. Gitignore has no Clear button: the flow is read-only, so there would be nothing for it to clear.

Base flags (Exclude binary files, Maximum size) live under their own **General** section at the top of the form. The uppercase section titles are the only block boundaries — no hairlines between rule sections — so the panel stays short enough to fit on a phone screen without scrolling past chrome.

### 🌍 Multi-language interface
- The plugin UI is available in **27 languages**, selectable from a dedicated dropdown in the settings page. Each language is listed by its native name (Español, Français, Deutsch, 日本語, 中文, …) so users can find theirs without reading English.
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
- The tree's loading state is robust: cancelling a scan, superseding it with a filter change, or hitting an error always restores the correct panel state. No orphan spinners, and the Cancel button is only shown while a scan is running.
- The project root is resolved by a three-step heuristic (`.gitignore` search, CodeAssist project boundary, fallback to the selected folder), bounded by a user-configurable parent folder search limit (default 10, range 0–50). Set it to 0 to keep the plugin strictly scoped to the folder you selected.

---

## 🌐 Supported languages

| Language | Code | Native name |
|---|---|---|
| Auto (follow IDE) | `auto` | — |
| English | `en` | English |
| Arabic | `ar` | العربية |
| Bengali | `bn` | বাংলা |
| Catalan | `ca` | Català |
| Chinese (Simplified) | `zh` | 中文 |
| Czech | `cs` | Čeština |
| Dutch | `nl` | Nederlands |
| French | `fr` | Français |
| German | `de` | Deutsch |
| Greek | `el` | Ελληνικά |
| Hebrew | `he` | עברית |
| Hindi | `hi` | हिन्दी |
| Indonesian | `in` | Bahasa Indonesia |
| Italian | `it` | Italiano |
| Japanese | `ja` | 日本語 |
| Korean | `ko` | 한국어 |
| Persian | `fa` | فارسی |
| Polish | `pl` | Polski |
| Portuguese (Brazil) | `pt` | Português (Brasil) |
| Romanian | `ro` | Română |
| Russian | `ru` | Русский |
| Spanish | `es` | Español |
| Swedish | `sv` | Svenska |
| Thai | `th` | ไทย |
| Turkish | `tr` | Türkçe |
| Ukrainian | `uk` | Українська |
| Vietnamese | `vi` | Tiếng Việt |

> **Coverage note:** The primary user flow (configuration dialog, project tree, result panel, settings page, error and notification strings) is fully translated in every language listed above. The "What's New" screen, the installation guide, and the help/FAQ section currently fall back to English in the newest additions (Bengali, Catalan, Persian, Hebrew, Dutch, Swedish, Thai, Ukrainian); a follow-up translation pass is planned.

---

## 📦 Output format

The generated file is a readable `.txt` document that includes:

1. a directory tree (with a `[ref]` suffix next to reference-only entries),
2. clearly separated file sections with relative paths, each section being either the file's content or a `[content omitted: N B — reference only]` marker,
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
5. Adjust the tree manually if needed: cycle files between Included, Reference-only, and Excluded; enable "Show locked" to unlock hard-excluded folders.
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

· core/ — filtering, Git handling, dump generation, file scanning, profile logic, tree overrides, and the per-project tree config store.
· android/ — host integration, file access, notifications, and export coordination.
· ui/ — configuration dialog, project tree UI, settings, help, result panels, theme palette, and the tree's custom scroll views.
· Root package — plugin entry point and info screen.

---

🆕 What's new in 1.2.0

· Eight new languages: Bengali, Catalan, Persian, Hebrew, Dutch, Swedish, Thai and Ukrainian. The plugin now bundles 27 languages (26 translations plus English), selectable independently of the IDE language from the settings page.

· Append-only language codes: the new codes are appended to the end of the language list and of the locale map, so hosts that persist the dropdown selection as an integer index keep pointing at the same language for every existing user.

· No behavioral changes to the export pipeline, the filtering logic, the Git integration, or the storage layer. This release is a pure i18n expansion plus the documentation needed to keep the new languages maintainable.

---

🆕 What's new in 1.1.0

· Tree v2: the project tree is the authoritative source of truth for what goes into the dump. Files can be marked Included, Reference-only, or Excluded from the tree itself, and the selection is preserved per project.
· Reference-only files: a new third inclusion state lets you list a file in the dump — name and size — without reading its content. Ideal for lockfiles, generated code, and machine-local configuration. Configured both by pattern (in the Reference only section of the dialog) and per file (in the tree).
· Uniform folder cycling: clicking a folder forces the next state onto every non-locked descendant at every depth, so the folder's tri-state indicator never disagrees with its children. Marking a deep file updates every ancestor's aggregate up to the root, without touching the ancestors' other children.
· Show locked: hard-excluded folders and binary files appear as locked nodes; enabling "Show locked" makes them interactive and scans a newly-unlocked folder's contents on demand, showing every child including APKs, .dex and binaries. The toggle survives filter changes and reloads: a rescan with "Show locked" on leaves the tree interactive. Disabling it re-locks everything and discards what was included while unlocked.
· New General section: "Exclude binary files" and "Maximum size" now live under their own uppercase title at the top of the form.
· Section toggles: Inclusions, Exclusions, Reference only, Gitignore, Version control, and File tree all follow the same checkbox + uppercase title + help pattern. Unchecking Inclusions, Exclusions, or Reference only disables the list without deleting it. A Clear button next to Add empties an editable flow in one tap and switches the profile to Custom; Gitignore has no Clear because its flow is read-only.
· Uppercase section headers, single hairline separator below the preset spinner, and no dividers between rule sections, so the panel stays short and minimalist on a phone screen.
· Dark theme is now the default. A fresh install opens on the plugin's original hand-tuned palette; existing users who explicitly picked a theme keep their stored choice.
· Info screen redesigned: features grouped into four categories, per-row icon count cut from roughly 25 to 10, and a hero block with a version and status chip row.
· Project root resolution: three-step heuristic (.gitignore search, CodeAssist project boundary, fallback to the selected folder), bounded by the new "Parent folder search limit" setting.
· .platform directory is now hard-excluded from the dump, alongside .git, .gradle, and .idea, so the plugin's own tracking file cannot end up in the model's context.
· Tree loading and cancellation are robust: no orphan "Analyzing…" spinners, Cancel only shown while a scan is running, and Reload expands the section first if it was collapsed.
· Contextual help on every section, and every toggle section's help body now explains that unchecking the box disables the list without deleting it.
· Built-in presets use reference-only entries for generated files and lockfiles, keeping the dump structurally complete without paying the token cost of their contents.

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

```

---

## Notas sobre este README

### Contenido preservado sin tocar

Todo lo que no tiene que ver con los idiomas queda idéntico al que compartiste en la última iteración:

- Introducción + badges (excepto el de versión)
- `Why One Code For AI?`
- `✨ Features` completas (Smart export, Interactive project tree, Git integration, Theme support, Built-in presets, Section toggles, Reliability and UX) con los cambios de Show locked persistence y Clear button ya aplicados
- `📦 Output format`
- `🛠️ How it works`
- `🔒 Privacy and security`
- `🧱 Project structure`
- `✅ Requirements`, `📄 License`, `🔗 Links`, footer

### Cambios mínimos aplicados

| # | Ubicación | Cambio |
|---|---|---|
| 1 | Badge row | `Version-1.1.0` → `Version-1.2.0` |
| 2 | `### 🌍 Multi-language interface` (primer bullet) | `18 languages` → `27 languages` |
| 3 | `## 🌐 Supported languages` (tabla) | 8 filas nuevas insertadas alfabéticamente por nombre inglés |
| 4 | Coverage note | Menciona explícitamente los 8 idiomas nuevos |
| 5 | Antes de `What's new in 1.1.0` | Nueva sección `What's new in 1.2.0` (breve, 3 bullets) |

### Sobre el conteo "27 languages"

Significa **27 idiomas totales en la tabla** (inglés + 26 traducciones), sin contar la entrada "Auto (follow IDE)" que es un modo, no un idioma. La tabla tiene por tanto 28 filas: 1 `Auto` + 27 idiomas. Esto es coherente con lo que ya habíamos acordado en turnos anteriores.

### Consistencia pendiente en otros archivos

Para que la release `1.2.0` quede coherente, estos otros archivos también deben reflejar la versión nueva antes de compilar/tag:

| Archivo | Línea |
|---|---|
| `plugin/module.toml` | `versionCode = 100` → `versionCode = 120` (o `101`) y `versionName = "1.1.0"` → `versionName = "1.2.0"` |
| `plugin/src/main/res/raw/codeassist_plugin.toml` | `version = "1.1.0"` → `version = "1.2.0"` |
| `README_STORE.md` | Mismos 5 cambios que en este README (badge, dos "18 languages", tabla, coverage note) |

¿Quieres que te pase también el `README_STORE.md` con los mismos cambios aplicados?README.md actualizado para 1.2.0

Cambios aplicados (mínimos y quirúrgicos, todo lo demás idéntico):

1. Badge Version-1.1.0 → Version-1.2.0
2. 18 languages → 27 languages (dos lugares: intro y sección Multi-language)
3. Tabla 🌐 Supported languages ampliada con 8 entradas en orden alfabético
4. Coverage note actualizada mencionando los 8 idiomas nuevos
5. Nueva sección 🆕 What's new in 1.2.0 antes de la existente 1.1.0 (la de 1.1.0 se conserva intacta como histórico del último release con features)

```markdown
# 🚀 One Code For AI

**Turn your CodeAssist project into a single, AI-ready `.txt` file in seconds.**

One Code For AI is a robust Android plugin for the CodeAssist IDE that scans a project or selected folder, applies configurable filters, and generates a structured `.txt` file containing a directory tree and the contents of the selected files. It is designed to give LLMs clean context without the tedious process of copying files one by one.

[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](https://github.com/daytonplus/one-code-for-ai/blob/main/LICENSE)
[![Min SDK](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=for-the-badge)]()
[![Target SDK](https://img.shields.io/badge/Target_SDK-36-informational.svg?style=for-the-badge)]()
[![Java](https://img.shields.io/badge/Java-17-orange.svg?style=for-the-badge)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-JVM-purple.svg?style=for-the-badge)]()
[![CodeAssist Plugin API](https://img.shields.io/badge/Plugin_API-4-lightgrey.svg?style=for-the-badge)]()
[![Version](https://img.shields.io/badge/Version-1.2.0-success.svg?style=for-the-badge)]()

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
- Filter by extension, filename, relative path, or glob patterns like `*.log`, `build/`, and `**/generated/**`.
- Respect root `.gitignore` rules, including override behavior.
- Exclude binary files automatically.
- Preview the project tree before export.
- Manually include, stub, or exclude individual files and folders.
- Configure per-file and total export size limits.

### 🌳 Interactive project tree (Tree v2)
The file tree is the **authoritative source of truth** for what goes into the dump, not just a preview. Every entry can be set to one of three states:

- **Included** — the file's full content is written to the dump.
- **Reference only** — the file's name and size are listed, but its content is not read. Ideal for lockfiles, generated code, and machine-local configuration.
- **Excluded** — the file is not written to the dump.

Clicking a file cycles through the three states. Clicking a folder **forces the next state onto its entire non-locked subtree uniformly**, so after the click the folder and everything inside it share the same state. The folder's tri-state indicator therefore never disagrees with its children, and marking a deep file updates every ancestor's aggregate all the way to the root, without ever touching the ancestors' other children.

Hard-excluded folders (`node_modules`, `build`, `.git`, …) and binary files appear as **locked** nodes: visible in the tree but not interactive until you enable "Show locked". Enabling the toggle makes them interactive and scans a newly-unlocked folder's contents on demand, showing every child — including build outputs such as APKs, `.dex` files and binaries — so you can navigate to anything inside. The toggle survives filter changes and manual reloads: a rescan with "Show locked" on leaves the new tree interactive instead of rebuilding it locked. Disabling it re-locks every originally-locked node and discards anything that was included while unlocked, so Generate never exports a file you can no longer see.

Your manual tree decisions are stored per project in `.platform/plugins/com.devdayton.onecodeforai/config.json`, so each project keeps its own selection independent of the preset. Closing the dialog without pressing Generate discards the manual changes, matching the standard "commit on Generate" model.

### 🌿 Git integration
- Browse local and remote branches, tags, and recent commits.
- Export a Git snapshot without modifying the working tree.
- Preview the diff against the current working tree.
- View the current branch, commit, and repo status.
- Disable Git globally while keeping `.gitignore` filtering available.

### 🎨 Theme support
- The plugin UI ships with a **light** and a **dark** palette. The dark palette preserves the original hand-tuned colors and is the default; the light palette keeps the same visual identity (warm accent, muted text hierarchy, soft outlines) on a light background.
- The theme can be set to **Dark** (the default), **System** (follow the device's light/dark mode), or **Light** from the settings page.
- Only newly opened dialogs and screens pick up a change; already-open windows keep their palette until reopened, matching the standard Android behavior for runtime theme changes.

### 📱 Built-in presets and settings
- Professional presets for Android (Kotlin), Android (source only), Android (Resources), Web, Python, and Plain text.
- Save and restore custom profiles.
- Configure Git, animations, notifications, UI language, UI theme, export limits, and the parent-folder search limit from a single settings page.
- Use contextual help inside the UI: Export profile, Version control, Inclusions, Exclusions, Reference only, Gitignore, Binary exclusion, Project tree, Locked nodes, and Per-file size limit all expose a "?" affordance with an inline explanation.
- Built-in presets use **reference-only** entries for generated files and lockfiles — `R.java`, `BuildConfig.java`, `local.properties`, `package-lock.json`, `pnpm-lock.yaml`, `poetry.lock`, `Pipfile.lock`. The dump stays structurally complete without paying the token cost of those files' contents.

### ⚙️ Section toggles
Every rule list in the configuration dialog is a **toggleable section**: a checkbox with an uppercase title, an optional Clear button, an optional "+ Add" button, a "?" help icon, and a body that appears only when the checkbox is checked. Unchecking Inclusions, Exclusions, or Reference only disables that list without deleting it — the rules stay in the profile and are reapplied the moment you check the box again. A Clear button next to Add empties an editable flow in one tap; emptying a flow counts as a rule change and switches the profile to Custom. The Gitignore section uses the same shape, with the "Respect .gitignore rules" checkbox acting as the section header, directly above the rules it controls. Gitignore has no Clear button: the flow is read-only, so there would be nothing for it to clear.

Base flags (Exclude binary files, Maximum size) live under their own **General** section at the top of the form. The uppercase section titles are the only block boundaries — no hairlines between rule sections — so the panel stays short enough to fit on a phone screen without scrolling past chrome.

### 🌍 Multi-language interface
- The plugin UI is available in **27 languages**, selectable from a dedicated dropdown in the settings page. Each language is listed by its native name (Español, Français, Deutsch, 日本語, 中文, …) so users can find theirs without reading English.
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
- The tree's loading state is robust: cancelling a scan, superseding it with a filter change, or hitting an error always restores the correct panel state. No orphan spinners, and the Cancel button is only shown while a scan is running.
- The project root is resolved by a three-step heuristic (`.gitignore` search, CodeAssist project boundary, fallback to the selected folder), bounded by a user-configurable parent folder search limit (default 10, range 0–50). Set it to 0 to keep the plugin strictly scoped to the folder you selected.

---

## 🌐 Supported languages

| Language | Code | Native name |
|---|---|---|
| Auto (follow IDE) | `auto` | — |
| English | `en` | English |
| Arabic | `ar` | العربية |
| Bengali | `bn` | বাংলা |
| Catalan | `ca` | Català |
| Chinese (Simplified) | `zh` | 中文 |
| Czech | `cs` | Čeština |
| Dutch | `nl` | Nederlands |
| French | `fr` | Français |
| German | `de` | Deutsch |
| Greek | `el` | Ελληνικά |
| Hebrew | `he` | עברית |
| Hindi | `hi` | हिन्दी |
| Indonesian | `in` | Bahasa Indonesia |
| Italian | `it` | Italiano |
| Japanese | `ja` | 日本語 |
| Korean | `ko` | 한국어 |
| Persian | `fa` | فارسی |
| Polish | `pl` | Polski |
| Portuguese (Brazil) | `pt` | Português (Brasil) |
| Romanian | `ro` | Română |
| Russian | `ru` | Русский |
| Spanish | `es` | Español |
| Swedish | `sv` | Svenska |
| Thai | `th` | ไทย |
| Turkish | `tr` | Türkçe |
| Ukrainian | `uk` | Українська |
| Vietnamese | `vi` | Tiếng Việt |

> **Coverage note:** The primary user flow (configuration dialog, project tree, result panel, settings page, error and notification strings) is fully translated in every language listed above. The "What's New" screen, the installation guide, and the help/FAQ section currently fall back to English in the newest additions (Bengali, Catalan, Persian, Hebrew, Dutch, Swedish, Thai, Ukrainian); a follow-up translation pass is planned.

---

## 📦 Output format

The generated file is a readable `.txt` document that includes:

1. a directory tree (with a `[ref]` suffix next to reference-only entries),
2. clearly separated file sections with relative paths, each section being either the file's content or a `[content omitted: N B — reference only]` marker,
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
5. Adjust the tree manually if needed: cycle files between Included, Reference-only, and Excluded; enable "Show locked" to unlock hard-excluded folders.
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

· core/ — filtering, Git handling, dump generation, file scanning, profile logic, tree overrides, and the per-project tree config store.
· android/ — host integration, file access, notifications, and export coordination.
· ui/ — configuration dialog, project tree UI, settings, help, result panels, theme palette, and the tree's custom scroll views.
· Root package — plugin entry point and info screen.

---

🆕 What's new in 1.2.0

· Eight new languages: Bengali, Catalan, Persian, Hebrew, Dutch, Swedish, Thai and Ukrainian. The plugin now bundles 27 languages (26 translations plus English), selectable independently of the IDE language from the settings page.

· Append-only language codes: the new codes are appended to the end of the language list and of the locale map, so hosts that persist the dropdown selection as an integer index keep pointing at the same language for every existing user.

· No behavioral changes to the export pipeline, the filtering logic, the Git integration, or the storage layer. This release is a pure i18n expansion plus the documentation needed to keep the new languages maintainable.

---

🆕 What's new in 1.1.0

· Tree v2: the project tree is the authoritative source of truth for what goes into the dump. Files can be marked Included, Reference-only, or Excluded from the tree itself, and the selection is preserved per project.
· Reference-only files: a new third inclusion state lets you list a file in the dump — name and size — without reading its content. Ideal for lockfiles, generated code, and machine-local configuration. Configured both by pattern (in the Reference only section of the dialog) and per file (in the tree).
· Uniform folder cycling: clicking a folder forces the next state onto every non-locked descendant at every depth, so the folder's tri-state indicator never disagrees with its children. Marking a deep file updates every ancestor's aggregate up to the root, without touching the ancestors' other children.
· Show locked: hard-excluded folders and binary files appear as locked nodes; enabling "Show locked" makes them interactive and scans a newly-unlocked folder's contents on demand, showing every child including APKs, .dex and binaries. The toggle survives filter changes and reloads: a rescan with "Show locked" on leaves the tree interactive. Disabling it re-locks everything and discards what was included while unlocked.
· New General section: "Exclude binary files" and "Maximum size" now live under their own uppercase title at the top of the form.
· Section toggles: Inclusions, Exclusions, Reference only, Gitignore, Version control, and File tree all follow the same checkbox + uppercase title + help pattern. Unchecking Inclusions, Exclusions, or Reference only disables the list without deleting it. A Clear button next to Add empties an editable flow in one tap and switches the profile to Custom; Gitignore has no Clear because its flow is read-only.
· Uppercase section headers, single hairline separator below the preset spinner, and no dividers between rule sections, so the panel stays short and minimalist on a phone screen.
· Dark theme is now the default. A fresh install opens on the plugin's original hand-tuned palette; existing users who explicitly picked a theme keep their stored choice.
· Info screen redesigned: features grouped into four categories, per-row icon count cut from roughly 25 to 10, and a hero block with a version and status chip row.
· Project root resolution: three-step heuristic (.gitignore search, CodeAssist project boundary, fallback to the selected folder), bounded by the new "Parent folder search limit" setting.
· .platform directory is now hard-excluded from the dump, alongside .git, .gradle, and .idea, so the plugin's own tracking file cannot end up in the model's context.
· Tree loading and cancellation are robust: no orphan "Analyzing…" spinners, Cancel only shown while a scan is running, and Reload expands the section first if it was collapsed.
· Contextual help on every section, and every toggle section's help body now explains that unchecking the box disables the list without deleting it.
· Built-in presets use reference-only entries for generated files and lockfiles, keeping the dump structurally complete without paying the token cost of their contents.

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
