# One Code For AI

**Prepare clean, reviewable technical context for AI-assisted programming — in one file.**

One Code For AI is a **CodeAssist** plugin that scans a project or selected folder, applies configurable filters, and generates a structured `.txt` file containing a directory tree and the contents of the selected files. Share the result with ChatGPT, Claude, Gemini, or another app—without copying files one by one.

![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Min SDK](https://img.shields.io/badge/API-26%2B-brightgreen.svg)
![Target SDK](https://img.shields.io/badge/Target_SDK-36-informational.svg)
![Java](https://img.shields.io/badge/Java-17-orange.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-JVM-purple.svg)
![Platform](https://img.shields.io/badge/CodeAssist_Plugin_API-4-lightgrey.svg)
![Version](https://img.shields.io/badge/Version-1.0.80-success.svg)

---

## Features

### Export and filtering

- Generate a dump of the project or a selected folder.
- Automatically detect the Git root when working inside a repository.
- Include or exclude files by extension, name, relative path, or glob pattern (`*`, `?`, `**`).
- Respect root `.gitignore` rules, including negation patterns.
- Exclude binary files automatically.
- Enable or disable the per-file size limit.
- Exclude individual files or directories from the project tree.

### Git snapshots

- Browse local and remote branches, tags, and recent commits.
- Generate a dump from a selected Git reference without changing the working tree.
- Preview a reference’s diff against the current working tree before exporting.
- View the current branch, commit, and uncommitted changes.

### Project tree and results

- Browse the project tree, expand or collapse directories, and reload the view.
- Select files or directories to exclude from the generated dump.
- Follow export progress by processed bytes, with a file-count fallback when byte totals are not yet available.
- View the processed file count and byte progress while generation runs.
- See the final file count, total size, and approximate token count after generation. Token counts are estimates, based on roughly four UTF-8 bytes per token.

### Profiles and preferences

- Use built-in profiles for Android/Kotlin, Web, Python, and plain text.
- Save custom profiles and restore them on the next launch.
- Preserve saved profiles and settings across plugin updates.
- Configure Git integration, the project tree, `.gitignore` handling, and binary-file filtering in plugin settings.

### Export, saving, and sharing

- Generate the project context in one step.
- Cancel an export while it is running or minimize the dialog; generation continues in the background.
- After generation, share the result, save a timestamped copy, or regenerate it.
- Save files to `Downloads/OneCodeForAI`.
- Share the generated file through the native Android share chooser.
- Receive a notification when generation is complete.

### Performance and localization

- Load the configuration dialog promptly while project and Git information is resolved in the background.
- Reuse cached `.gitignore` rules and plugin resources to reduce repeated file reads and UI setup work.
- Update visible project-tree rows in place when possible instead of rebuilding the entire tree.
- Use the interface in **English** or **Spanish**, including the plugin information page, installation guide, and help.

## Privacy

Project scanning and dump generation happen locally on your device. The plugin does not upload project files or send telemetry.

## Output format

The generated `.txt` file contains a directory tree followed by clearly separated sections for the included files. File contents are written incrementally rather than assembled into one large in-memory string.

Generated files are stored in:

```text
Downloads/OneCodeForAI
```

## Tech stack

| Category | Technology |
|---|---|
| Language | Kotlin (JVM) |
| Minimum Android version | Android 8.0 Oreo (API 26) |
| Compile SDK / Target SDK | 36 |
| Java language level | 17 |
| Host platform | CodeAssist Plugin API 4 |
| Git library | JGit `7.8.0.202609011348-r` |
| Module format | CodeAssist module (`plugin/module.toml`) |

## Project structure

| Package | Responsibility |
|---|---|
| `core/` | File filtering, directory-tree generation, dump generation, Git operations, and profile/configuration data |
| `android/` | Host environment access, export coordination, progress state, notifications, and Android file delivery |
| `ui/` | Configuration dialog, project tree, Git reference picker, diff preview, settings, and shared UI components |
| Root package | CodeAssist plugin entry point, settings page, and plugin information activity |

## Requirements

- Android 8.0 (API 26) or higher.
- CodeAssist with support for Plugin API 4.
- Java 17 to compile the plugin module.

## Links

- [Source code and issues](https://github.com/daytonplus/one-code-for-ai)
- [MIT License](https://github.com/daytonplus/one-code-for-ai/blob/main/LICENSE)

---

Built with ❤️ for the CodeAssist community.