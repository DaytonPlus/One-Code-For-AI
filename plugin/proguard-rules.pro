# Only the classes discovered reflectively by the IDE/Android runtime need stable names.
-keep class com.devdayton.onecodeforai.OneCodeForAIPlugin { public <init>(); }
-keep class com.devdayton.onecodeforai.OneCodeForAIInfoActivity { *; }

# JGit 5.13 uses reflection internally (service loaders, optional deps) and
# references classes that are not present on Android (e.g. Eclipse-specific
# integration). Suppress the "missing class" warnings; the referenced classes
# are only reached on non-Android platforms, so the code paths that use them
# are never executed here.
-dontwarn org.eclipse.jgit.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn org.apache.**

# Keep JGit's service-loader registrations so repository builders still find
# their transports after shrinking.
-keep class org.eclipse.jgit.** { *; }