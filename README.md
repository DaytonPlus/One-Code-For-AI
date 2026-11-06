# 🚀 One Code For AI

**Turn your CodeAssist project into a single, AI-ready `.txt` file in seconds.**

One Code For AI is a robust Android plugin for the CodeAssist IDE that scans a project or selected folder, applies configurable filters, and generates a structured `.txt` file containing a directory tree and the contents of the selected files. It is designed to give LLMs clean context without the tedious process of copying files one by one.

[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](https://github.com/daytonplus/one-code-for-ai/blob/main/LICENSE)
[![Min SDK](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=for-the-badge)]()
[![Target SDK](https://img.shields.io/badge/Target_SDK-36-informational.svg?style=for-the-badge)]()
[![Java](https://img.shields.io/badge/Java-17-orange.svg?style=for-the-badge)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-JVM-purple.svg?style=for-the-badge)]()
[![CodeAssist Plugin API](https://img.shields.io/badge/Plugin_API-4-lightgrey.svg?style=for-the-badge)]()
[![Version](https://img.shields.io/badge/Version-1.0.84-success.svg?style=for-the-badge)]()

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
- Respect root `.gitignore` rules, including override behavior.
- Exclude binary files automatically.
- Preview the project tree before export.
- Manually include or exclude individual files or folders.
- Configure per-file and total export size limits.

### 🌿 Git integration
- Browse local and remote branches, tags, and recent commits.
- Export a Git snapshot without modifying the working tree.
- Preview the diff against the current working tree.
- View the current branch, commit, and repo status.
- Disable Git globally while keeping `.gitignore` filtering available.

### 📱 Built-in presets and settings
- Professional presets for:
  - Android (Kotlin)
  - Android (source only)
  - Android (resources only)
  - Web
  - Python
  - Plain text
- Save and restore custom profiles.
- Configure Git, animations, notifications, filtering behavior, and export limits.
- Use contextual help inside the UI for filters and `.gitignore` rules.

### ⚡ Reliability and UX
- Background generation and progress tracking.
- Cancel export while it is running.
- Minimize the dialog without losing progress.
- Save, share, or regenerate the final output.
- Clear warnings for unreadable files or incomplete exports.
- Persistent recovery behavior for previous valid output.

---

## 📦 Output format

The generated file is a readable `.txt` document that includes:
1. a directory tree,
2. clearly separated file sections with relative paths,
3. final stats for file count, total size, and estimated token count.

Generated files are saved to:

```text
Downloads/OneCodeForAI
```

---

## 🛠️ How it works

1. Open CodeAssist.
2. Select the project or folder you want to include.
3. Launch the plugin.
4. Choose filters, presets, and Git options.
5. Export the final `.txt` file.
6. Share it with your AI tool of choice.

---

## 🔒 Privacy and security

- Everything happens locally on your device.
- No internet permission is required.
- No project files are uploaded to a server.
- No telemetry is sent by the plugin.
- The export process runs entirely inside the CodeAssist environment.

---

## 🧱 Project structure

The plugin is organized into a few main areas:

- `core/` — filtering, Git handling, dump generation, file scanning, and profile logic
- `android/` — host integration, file access, notifications, and export coordination
- `ui/` — configuration dialog, project tree UI, settings, help, and result panels
- root package — plugin entry point and info screen

---

## ✅ Requirements

- Android 8.0 (API 26) or higher
- CodeAssist with Plugin API 4 support
- Java 17 for compiling the plugin module

---

## 📄 License

This project is licensed under the MIT License.

See [LICENSE](LICENSE) for details.

---

## 🔗 Links

- GitHub: https://github.com/daytonplus/one-code-for-ai
- Issues: https://github.com/daytonplus/one-code-for-ai/issues

---

Built with ❤️ for the CodeAssist community.
