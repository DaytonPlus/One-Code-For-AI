Version 1.0.90

• Fixed a critical runtime crash on Android 8-12 caused by JGit 7.x depending on InputStream.readAllBytes() (API 33+); downgraded to JGit 5.13.3, the last series that runs on API 26+.
• Fixed .gitignore patterns starting with **/ not matching files at the project root.
• Fixed character-class patterns like [abc] and [!abc], which were previously treated as literals.
• Fixed svg files being classified as binary and lockfiles being impossible to force-include via Inclusions.
• Fixed the Turkish-i bug when uppercasing section titles on non-Latin-script systems.
• Fixed a case-only duplicate when renaming a tag from e.g. "kt" to "Kt".
• Fixed a UI-thread stall when validating a stale result URI on slow storage.
• Fixed the Download/Downloads path mismatch in every user-facing string.
• Fixed stack traces being lost in the plugin logger when the host exposes the two-argument form.
• Removed the hardcoded "Personalizado" preset label; the custom fallback now uses R.string.custom_preset.
• Converted five recursive tree traversals to iterative ones, preventing StackOverflowError on pathological directory trees.
• Unified the share helper into a single entry point, so the SHARE action and the result-panel chip behave identically.
• Moved Git reference validation off the UI thread; the Generate button no longer freezes on large repositories.
• Removed dead code: the tree_enabled preference (never written), the pre-API-24 locale fallback, and the unused file_paths.xml provider path.
• The .gitignore cache is now bounded to 32 entries, and negations (!foo) are displayed with a distinct style.
• Host IDE package is now configurable via R.string.host_ide_package, so forks can rebrand without code changes.
• Emojis removed from all strings except the footer heart; the README uses the correct Download path.
• Replaced the "Force English UI" toggle with a full language selector. The plugin now ships with translations for 18 languages in addition to English, and the selector defaults to "Auto" so the pre-selector behaviour (follow the IDE language) is preserved for anyone who never opens the settings page.
• Added 17 new translations: Arabic, Czech, German, Greek, French, Hindi, Indonesian, Italian, Japanese, Korean, Polish, Portuguese (Brazil), Romanian, Russian, Turkish, Vietnamese, and Chinese.
• Each language is listed by its native name (Español, Français, Deutsch, 日本語, 中文, …) so users can find theirs without needing to read English.
• Existing "Force English UI" users keep an English UI. Their legacy preference is migrated automatically to the new selector (ui_language = "en") the first time the settings page is opened, so no one loses their previous choice after the upgrade.
• The language preference is now persisted as an ISO-639 code instead of a boolean, so adding languages in the future will not overwrite the user's existing selection.
• The context-menu label updates on the next IDE restart. Re-registering the action to refresh the label live would duplicate it in the menu (the host treats re-registration as additive, not replacing), so the plugin deliberately does not attempt it. Every other surface — dialogs, settings page, info screen, notifications, error messages — switches to the new language on the next newly-opened window.
• Some of the new translations currently cover the primary UI only. The What's New screen, the installation guide, and the help/FAQ section fall back to English until a follow-up translation pass is completed. The core flow (configuration dialog, result panel, settings page, error and notification strings) is fully translated in every language.
• Diff preview is now resilient to shallow and partial clones. When the historical blob for a file is not present locally (repos cloned with --depth or --filter=blob:none), the file is still listed with its change status; only the line-level stats for that specific file are shown as unavailable. Files whose blobs *are* present keep showing their full diff.
• Diff preview no longer reports a failed computation as "no changes detected". When the diff cannot be computed at all (e.g. an unreadable working tree, or a ref that does not peel to a tree), the dialog now shows a distinct "could not compute the diff" message and points the user to the IDE console for details.
• GitUtils logs every swallowed failure at WARN level with the offending path, blob ID, and a human-readable hint. Diagnosing a broken diff no longer requires re-running the preview with a debugger attached — the reason appears in the IDE console.
• Added git_diff_error string in every bundled language.