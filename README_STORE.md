# 🚀 One Code For AI

**Turn your CodeAssist project into a single, AI-ready `.txt` file in seconds.**

One Code For AI is a plugin for the CodeAssist IDE that scans a project or selected folder, applies configurable filters, and generates a structured `.txt` file containing a directory tree and the contents of the selected files. It is designed to give LLMs clean context without the tedious process of copying files one by one.

> Stop copy-pasting files. Share a clean, reviewable project context with ChatGPT, Claude, Gemini, or any other AI tool.

---

## ✨ What you get

- **One file, full context.** The entire project — or a carefully filtered subset — becomes a single reviewable `.txt`.
- **Smart filtering.** Exclude binaries and generated output automatically, respect `.gitignore`, or write your own glob rules.
- **Git-aware.** Export from any branch, tag, or commit, or preview what changed before generating.
- **Multi-language UI.** 18 languages, selectable independently of the IDE language.
- **100% offline.** No internet permission, no telemetry, no cloud upload.

---

## 📁 Smart export and filtering

- Export an entire project or a selected folder.
- Filter by extension, filename, relative path, or glob patterns like `*.log`, `build/`, or `**/generated/**`.
- Respect root `.gitignore` rules, including override behavior.
- Exclude binary files automatically.
- Preview the project tree before export, and manually include or exclude individual files or folders.
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

---

## 📦 Output format

The generated file is a readable `.txt` document that includes:

1. A directory tree of the included files.
2. Clearly separated file sections, each headed by the file's relative path.
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
4. Choose filters, presets, and Git options.
5. Export the final .txt file.
6. Share it with your AI tool of choice.

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

· core/ — filtering, Git handling, dump generation, file scanning, and profile logic.
· android/ — host integration, file access, notifications, and export coordination.
· ui/ — configuration dialog, project tree UI, settings, help, and result panels.
· Root package — plugin entry point and info screen.

---

🆕 What's new in 1.0.90

· Language selector replaces the previous "Force English UI" toggle. Pick any of 18 languages from the settings page; "Auto" follows the IDE.
· 17 new translations: Arabic, Czech, German, Greek, French, Hindi, Indonesian, Italian, Japanese, Korean, Polish, Portuguese (Brazil), Romanian, Russian, Turkish, Vietnamese, and Chinese.
· Automatic migration for existing "Force English UI" users: their preference becomes ui_language = "en" the first time the settings page is opened.
· Context-menu label refresh when the language changes. On builds that support in-place action updates the label switches instantly; on builds that cache action labels, the new label appears after the next IDE restart.
· Critical fix for Android 8–12 runtime crashes caused by JGit 7.x depending on InputStream.readAllBytes() (API 33+); downgraded to JGit 5.13.3, the last series that runs on API 26+.
· .gitignore fixes: patterns starting with **/ now match files at the project root, and character-class patterns like [abc] / [!abc] are no longer treated as literals.
· Binary detection fixes: svg is no longer classified as binary, and lockfiles can be force-included via Inclusions.
· Tree traversal safety: five recursive walks converted to iterative ones, preventing StackOverflowError on pathological directory trees.
· Performance: Git reference validation moved off the UI thread; the Generate button no longer freezes on large repositories.
· UX consistency: the share action and the result-panel chip now go through a single entry point, so they behave identically.

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
