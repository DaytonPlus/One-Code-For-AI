# One Code For AI

A CodeAssist plugin, packaged as its own app. CodeAssist finds it through the package manager, reads
`plugin/src/main/res/raw/codeassist_plugin.toml`, and loads `OneCodeForAIPlugin` off the installed APK.

## Try it

Build and install this project, then restart CodeAssist and open **Settings > Plugins > Installed**.
Plugins load once at startup, so a newly installed plugin appears after the next launch. If it does
not appear, its row in that screen carries the reason.

## The three parts

| Part | Where |
| --- | --- |
| The packaged manifest | `plugin/src/main/res/raw/codeassist_plugin.toml` |
| The marker activity CodeAssist queries for | `plugin/src/main/AndroidManifest.xml` |
| The entry point the manifest names | `OneCodeForAIPlugin.kt` |

## Notes

- `id` is `com.devdayton.onecodeforai`. It is the plugin's identity, not a display name: it decides load order, what
  another plugin's `dependsOn` refers to, and what an enable/disable choice is stored against.
- The SPI is a `compileOnly` dependency. It is never bundled: the IDE's classloader is the parent of
  this plugin's, so the SPI, the Kotlin stdlib and the Compose runtime resolve to the IDE's copies.
- Do not minify. The IDE loads the entry point by the name in the manifest, and R8 would rename it.
- The plugin runs inside the IDE's process, under its permissions. Class loading separates versions,
  not privileges.
- The SPI is licensed GPL-3.0-or-later with the Classpath exception, so this plugin can carry any
  license you choose.
