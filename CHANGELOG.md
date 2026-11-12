Version 1.2.0

• i18n expansion pass: eight new languages are now bundled and selectable
  from the settings page — Bengali (বাংলা), Catalan (Català), Persian (فارسی),
  Hebrew (עברית), Dutch (Nederlands), Swedish (Svenska), Thai (ไทย) and
  Ukrainian (Українська). Each language ships as a complete
  values-<code>/strings.xml overlay, bringing the total to 27 languages
  (26 translations plus English) selectable independently of the IDE
  language.

• Language list expanded without reordering: the eight new codes are
  appended to the end of LanguageHelper.localeForLanguageCode and to
  LANGUAGE_OPTIONS in OneCodeForAISettingsPage, so the codes that already
  existed keep their previous position. Hosts that persist the dropdown
  selection as an integer index (rather than as the ISO-639 code) therefore
  keep pointing at the correct language for every existing user; the new
  codes only occupy indices that did not previously exist.

• RTL support documented: Persian and Hebrew join Arabic as right-to-left
  languages. Android mirrors framework widgets automatically from the
  Configuration applied by LanguageHelper.wrapContextWithIdeLocale, so no
  RTL-specific code path was added. The plugin's own custom views
  (FlowLayout, ProjectTreeView) remain documented as LTR-only, and the
  KDocs in LanguageHelper and OneCodeForAISettingsPage now point future
  mirroring regressions at those views rather than at the locale resolver.

• Hebrew uses the modern ISO-639 code: "he" resolves to values-he/ on every
  Android version the plugin supports (minSdk 26). The legacy "iw" alias is
  not used; the KDoc of LanguageHelper documents the substitution
  (Locale("iw", "")) for the edge case of an OEM build that still reports
  the old code.

• KDoc updates for the new languages: LanguageHelper gained a
  "Right-to-left languages" section and a "Languages added after 1.1.0"
  section in localeForLanguageCode's KDoc, and OneCodeForAISettingsPage
  gained a "Right-to-left languages" section and a "Language count" section
  in LANGUAGE_OPTIONS's KDoc that spells out the current count (27), the
  procedure for appending a new language in the future (folder, helper
  branch, LANGUAGE_OPTIONS entry, README table, count), and the reason the
  list order must stay stable.

• README.md and README_STORE.md updated: the "18 languages" phrase is now
  "27 languages", the supported-languages table lists the eight new
  languages in alphabetical order, and the coverage note names the eight
  newer translations that still fall back to English for the "What's New"
  screen, the installation guide, and the help/FAQ section.

• No behavioral changes to the export pipeline, the filtering logic, the
  Git integration, or the storage layer. This release is a pure
  user-facing i18n expansion plus the documentation needed to keep the
  new languages maintainable.

• Version bumps required for release consistency: plugin/module.toml
  (versionName = "1.2.0"), plugin/src/main/res/raw/codeassist_plugin.toml
  (version = "1.2.0"), and the two README badges (Version-1.2.0). These
  are the last three files to touch before tagging; the rest of the release
  is complete once they are bumped.

Version 1.1.0

• Tree v2: the project tree is now the authoritative source of what goes into the dump. Every entry can be Included, Reference-only, or Excluded directly from the tree, and the selection is preserved per project in .platform/plugins/com.devdayton.onecodeforai/config.json. Closing the dialog without pressing Generate discards manual changes, matching the standard commit-on-generate model.
• Reference-only entries: a new third state lets you list a file in the dump by name and size without reading its content. Ideal for lockfiles, generated code, and machine-local configuration. Marked with a [ref] suffix in the directory tree and a [content omitted: N B — reference only] marker in the body.
• Interactive tree selector: every row shows a tri-state indicator (checkmark for Included, dash for Reference-only, empty for Excluded). Clicking a leaf cycles its own state; clicking a folder forces the next state onto its whole non-locked subtree uniformly, so the folder indicator never disagrees with its children. Marking a deep file updates every ancestor's aggregate automatically, without touching its siblings.
• Show locked: hard-excluded folders (node_modules, build, .git, …) and binary files appear as locked nodes. Enabling the toggle makes them interactive and scans a newly-unlocked folder's contents on demand, showing every child including build outputs (APKs, .dex, binaries) so you can navigate to anything inside. Disabling it re-locks every originally-locked node and discards anything that was included while unlocked. The toggle now survives filter changes and manual reloads: a rescan with "Show locked" on leaves the new tree interactive instead of rebuilding it locked.
• Built-in presets updated: Android, Web, and Python presets now list R.java, BuildConfig.java, local.properties, package-lock.json, pnpm-lock.yaml, poetry.lock, and Pipfile.lock as reference-only instead of excluding them, keeping the dump structurally complete without the token cost of their contents.
• Tree loading robustness: cancelling a scan, superseding it with a filter change, or hitting an error always restores the correct panel state. The Cancel button only appears while a scan is running, and Reload expands the section first if it was collapsed.
• Dark theme is now the default. A fresh install opens on the plugin's original hand-tuned palette. The theme selector in Settings still offers System (follow device), Light, and Dark; existing users who explicitly picked a theme keep their stored choice.
• Configuration dialog: every rule section (Inclusions, Exclusions, Reference only, Gitignore, Version control, File tree) now renders as a checkbox with an uppercase title, an optional Clear button, an optional Add button, a "?" help icon, and a body shown only when the box is checked. The checkbox is the section header. Base flags (Exclude binary files, Maximum size) live under a new General section at the top of the form.
• Section toggles: unchecking Inclusions, Exclusions, or Reference only hides the section and ignores its list during export, but the rules stay in the profile and are reapplied when you check the box again. Gitignore uses the same shape, with the "Respect .gitignore rules" checkbox as the section header; toggling it off clears the flow in place without a dialog reload or tree re-scan. A Clear button next to Add empties an editable flow in one tap; emptying a flow counts as a rule change and switches the profile to Custom. Existing profiles load with all three sections enabled, exactly as before.
• Project root resolution: three-step heuristic (.gitignore search, CodeAssist project boundary, fallback to the selected folder), bounded by a new "Parent folder search limit" setting (default 10, 0–50). A value of 0 keeps the plugin strictly scoped to the selected folder.
• The plugin's own .platform directory is now hard-excluded from the dump, alongside .git, .gradle, and .idea, so the per-project config can never end up in the model's context. Enable "Show locked" to inspect it explicitly.
• Info screen redesigned: the ten per-item feature icons are grouped into four categories (Export and filtering, Git integration, Customization, Privacy), the hero block carries a version and status chip row, and every changelog item is a monospace ▸ bullet. Roughly 25 icons are now 10, and the feature list reads in one glance instead of ten.
• Contextual help on every section: Export profile, Version control, Inclusions, Exclusions, Reference only, Gitignore, Binary exclusion, Project tree, Locked nodes, and Per-file size limit all expose a "?" with an inline explanation, and the help body for each toggle section now also explains that unchecking the box disables the list without deleting it.
• Extracted Int.withAlpha(Int) into a top-level extension in CodeAssistTheme.kt so every view builder in the ui package can derive low-alpha variants of a palette color without an import. This fixes an "Unresolved reference 'withAlpha'" compile error that hit any file other than OneCodeForAIInfoActivity that needed the helper.