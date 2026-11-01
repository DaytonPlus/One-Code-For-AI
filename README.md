# One Code For AI - CodeAssist Plugin

A robust Android plugin for the CodeAssist IDE that compiles your project's source code into a single, structured `.txt` file. This tool is specifically designed to generate context for Large Language Models (LLMs) such as ChatGPT, Claude, and Gemini, eliminating the need to copy and paste files individually.

## Overview

One Code For AI scans your project directory, applies intelligent filtering, and generates a comprehensive code dump. The output includes a visual directory tree followed by the contents of each allowed file, formatted for optimal AI comprehension.

## Features

- **Single-File Generation:** Aggregates your entire project or specific subdirectories into one text file.
- **Git Integration (JGit):** Export files directly from specific Git branches, tags, or commits without altering your current working tree.
- **Advanced Filtering:**
  - Respect `.gitignore` rules automatically.
  - Filter by file extension (inclusions and exclusions).
  - Exclude binary files and set maximum file size limits.
- **Interactive File Tree:** Visualize the project structure and manually exclude specific directories or files before exporting.
- **Real-time Statistics:** Estimates total files, byte size, and token count to ensure you stay within your AI model's context window.
- **Export Profiles:** Save custom filter configurations for different project types (e.g., Android, Web, Python).
- **Native UI/UX:** Fully integrated with CodeAssist's dark theme, offering a seamless and responsive user experience.
- **Direct Sharing:** Export to local storage (`Downloads/OneCodeForAI`) or share the generated file directly to other applications.

## Requirements

- Android 8.0 (API 26) or higher.
- CodeAssist IDE installed on the device.
- CodeAssist Plugin API version 4.

## Installation

1. Download the latest `.apk` release from this repository.
2. Install the APK on your Android device. You may need to allow installation from unknown sources.
3. Open CodeAssist.
4. Navigate to **Settings > Plugins > Installed**.
5. Enable **One Code For AI**.
6. Restart CodeAssist to ensure the plugin initializes correctly.

## Usage

1. Open a project in CodeAssist.
2. Open the project file tree.
3. Long-press on any folder or file.
4. Select **"One Code For AI: generate .txt"** from the context menu.
5. Configure your export profile:
   - Adjust inclusions/exclusions.
   - Set maximum file size.
   - Toggle Git integration to read from a specific commit or branch.
   - Use the interactive tree to deselect specific paths.
6. Tap **Export to Downloads** or **Share file**.
7. If sharing, select your preferred AI application.

## Technical Details

The plugin is built using Kotlin and integrates directly with the CodeAssist host environment via the Plugin API. Git operations are handled natively via JGit, ensuring full compatibility with Android's runtime environment without relying on external system binaries. File parsing and filtering algorithms are optimized to handle large repositories efficiently.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for more details.

```text
MIT License

Copyright (c) 2026 DaytonPlus

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```