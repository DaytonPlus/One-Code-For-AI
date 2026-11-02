# Only the classes discovered reflectively by the IDE/Android runtime need stable names.
-keep class com.devdayton.onecodeforai.plugin.OneCodeForAIPlugin { public <init>(); }
-keep class com.devdayton.onecodeforai.ui.info.PluginInfoActivity { *; }
