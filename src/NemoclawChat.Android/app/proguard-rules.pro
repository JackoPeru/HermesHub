# Optional Meta DAT implementations are loaded reflectively so the standard
# source set can compile without GitHub Packages. R8 must preserve entrypoints.
-keep class com.nemoclaw.chat.jarvis.meta.MetaWearablesFrameSource { *; }
-keep class com.nemoclaw.chat.jarvis.meta.MetaWearablesSetupBridgeImpl { *; }
