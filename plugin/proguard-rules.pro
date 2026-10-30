# Keep minification off for a plugin. The IDE instantiates the entry point by the class name in
# res/raw/codeassist_plugin.toml, and R8 would rename it.
-keep class ** { *; }
