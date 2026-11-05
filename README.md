<div align="center">

# 🚀 One Code For AI

**Turn your entire CodeAssist project into a single, structured, AI-ready `.txt` file in seconds.**

[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](https://github.com/daytonplus/one-code-for-ai/blob/main/LICENSE)
[![Min SDK](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=for-the-badge)]()
[![Target SDK](https://img.shields.io/badge/Target_SDK-36-informational.svg?style=for-the-badge)]()
[![Java](https://img.shields.io/badge/Java-17-orange.svg?style=for-the-badge)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-JVM-purple.svg?style=for-the-badge)]()
[![CodeAssist Plugin API](https://img.shields.io/badge/Plugin_API-4-lightgrey.svg?style=for-the-badge)]()

*Stop copying and pasting files one by one. Give ChatGPT, Claude, or Gemini the full context they need, 100% locally on your device.*

</div>

---

## 🌟 Why One Code For AI?

Providing context to AI models is the difference between generic answers and precise, project-aware code. **One Code For AI** deeply integrates with the CodeAssist IDE to scan your project, respect your ignore rules, and generate a beautifully formatted directory tree alongside the actual source code. 

No cloud uploads. No telemetry. Just pure, instant context generation.

## ✨ Epic Features

### 📁 Advanced Export & Smart Filtering
*   **Complete Control:** Export an entire project, specific subfolders, or dynamically filter by extensions, names, and relative paths.
*   **Advanced Glob Patterns:** Master your exports with patterns like `*.log`, `build/`, or `**/generated/**`.
*   **Directory-Level Exclusions:** Instantly skip heavy folders during the scan, making large exports lightning-fast.
*   **`.gitignore` Intelligence:** Automatically respects `.gitignore` rules. If a manual inclusion/exclusion overrides a rule, the UI highlights it in **amber** so you always know what's being exported.
*   **Binary Shield:** Automatically excludes images, videos, and compiled binaries to save tokens.

### 🌿 Deep Git Integration
*   **Snapshot Export:** Generate a dump from any local/remote branch, tag, or commit without altering your working tree.
*   **Diff Preview:** See exactly what changed against the current working tree before you export.
*   **Global Git Kill Switch:** Disable all Git features in settings for a purely filesystem-based experience. The plugin still hunts down ancestor `.gitignore` files to keep your filters active.

### 📱 Professional Android Presets
*   **Android (Kotlin):** The perfect profile for full-project reviews. Includes source, build configs, and resources.
*   **Android (Source Only):** Excludes tests, docs, and JSONs for pure logic analysis.
*   **Android (Resources):** Focuses strictly on XML layouts, themes, and manifests for UI work.
*   **Web, Python & Text:** Includes smart, pre-configured exclusions (like `package-lock.json`, `coverage.xml`, and `R.java`) so the AI doesn't get confused by machine-generated code.

### 🎨 Native UI & Contextual Help
*   **Built-in Settings Page:** Native CodeAssist toggles for Git, Animations (great for low-end devices), and Generation Notifications.
*   **Inline Help Dialogs:** Contextual `?` icons explain pattern syntax and priority rules exactly where you need them.
*   **Project Tree Preview:** Browse a beautiful, interactive tree. Check or uncheck entire folders or single files to customize your export.
*   **Byte-Accurate Progress:** Watch your export in real-time with byte-level precision, and cancel or minimize it while it runs in the background.

### ⚡ Uncompromising Performance
*   **Background Processing:** Dialog setup, Git detection, and tree scanning run off the main thread to keep the IDE perfectly responsive.
*   **Dynamic Animations:** UI transitions and tree expansions update instantly when toggled in settings.
*   **IDE Native Logging:** Internal diagnostics bypass Logcat and route directly to the CodeAssist console, featuring automatic chunking for long messages.

---

## 📦 Output Format

Generated files are saved securely to your device's storage:
`Downloads/OneCodeForAI`

The output is a highly readable `.txt` file containing:
1.  A visual directory tree of all included files.
2.  Clearly separated file contents with exact relative paths.
3.  Final statistics: Total files, total size, and an estimated token count.

---

## 🔒 Privacy & Security

*   **100% Offline:** No internet permission required. Your code never leaves your device.
*   **No Telemetry:** We don't track usage.
*   **Local Processing:** Everything happens right inside CodeAssist.

---

## 🛠️ Technical Details

*   **Requirements:** Android 8.0 (API 26+) and CodeAssist with Plugin API 4 support.
*   **Language:** Kotlin (JVM) / Java 17.
*   **Localization:** Fully translated in **English** and **Spanish**.
*   **Module Format:** CodeAssist plugin architecture.

**License:** MIT  
**Source code and issues:** [https://github.com/daytonplus/one-code-for-ai](https://github.com/daytonplus/one-code-for-ai)

<div align="center">
  
*Built with ❤️ for the CodeAssist community.*

**If this plugin supercharges your workflow, consider giving it a ⭐ on GitHub!**

</div>