# Optional Meta DAT implementations are loaded reflectively so the standard
# source set can compile without GitHub Packages. R8 must preserve entrypoints.
-keep class com.nemoclaw.chat.jarvis.meta.MetaWearablesFrameSource { *; }
-keep class com.nemoclaw.chat.jarvis.meta.MetaWearablesSetupBridgeImpl { *; }
# Device security + push: R8 full mode rimuove classi usate solo via
# reflection-free paths indiretti (verificato su mapping 0.6.291).
-keep class androidx.biometric.** { *; }
-keep class androidx.fragment.app.FragmentActivity { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.firebase.messaging.** { *; }
-keep class com.nemoclaw.chat.HermesPushService { *; }
-keep class com.nemoclaw.chat.core.security.DeviceKeysKt { *; }
