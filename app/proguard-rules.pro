# =============================================================================
# Vigília R8/ProGuard rules
# =============================================================================
# Enable via `isMinifyEnabled = true` in app/build.gradle.kts. Full rationale
# and library-by-library breakdown lives in CLAUDE.md under "History of
# decisions worth remembering". Test any rule change by building the release
# APK and smoke-testing the auth / monitoring / sync flows on device — unit
# tests run against unminified debug code and cannot catch minification bugs.

# =============================================================================
# Kotlin core
# =============================================================================
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# =============================================================================
# kotlinx.serialization
# =============================================================================
# The runtime resolves serializers by looking up the compiler-generated
# `$Companion` and `$serializer` on each @Serializable class. Both must be
# preserved by name.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static <1>$Companion Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable *;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}

# =============================================================================
# App DTOs (defense in depth in case the pattern rules above miss)
# =============================================================================
-keep class com.vigilia.app.data.remote.dto.** { *; }
-keepclassmembers class com.vigilia.app.data.remote.dto.** {
    <init>(...);
    <fields>;
}

# =============================================================================
# App enums used via .name / .valueOf
# =============================================================================
# FatigueState.name is persisted to session.csv and session_summary.json and
# read back via FatigueState.valueOf during Supabase upload. LightingMode is
# reconstructed in MonitoringViewModel with LightingMode.valueOf(String).
-keepclassmembers enum com.vigilia.app.domain.model.FatigueState { *; }
-keepclassmembers enum com.vigilia.app.lighting.LightingMode { *; }
# Generic guard for any other enum touched by valueOf/name at runtime.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# =============================================================================
# MediaPipe Tasks Vision
# =============================================================================
# Native code + reflection to load the .task model asset. Keep the whole
# tasks-vision + protobuf tree.
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# =============================================================================
# OpenCV
# =============================================================================
# OpenCVLoader.initLocal() loads libopencv_java4.so dynamically. All org.opencv
# classes are JNI entry points.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# =============================================================================
# Ktor (HTTP client used by Supabase)
# =============================================================================
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
# Kotlin reflection metadata is only needed if the app calls kotlin-reflect
# directly (we don't) — silencing warnings that come from Ktor's optional
# reflection paths.
-dontwarn kotlin.reflect.jvm.internal.**

# =============================================================================
# Supabase-kt (Auth, Postgrest, Android engine)
# =============================================================================
-keep class io.github.jan.supabase.** { *; }
-keep interface io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**

# =============================================================================
# CameraX (ServiceLoader-based camera provider discovery)
# =============================================================================
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }
-dontwarn androidx.camera.**

# =============================================================================
# WorkManager (SyncWorker is a CoroutineWorker instantiated by class name)
# =============================================================================
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# =============================================================================
# App build config
# =============================================================================
-keep class com.vigilia.app.BuildConfig { *; }

# =============================================================================
# Compose (AGP defaults usually cover this; -dontwarn is a safety net)
# =============================================================================
-dontwarn androidx.compose.**

# =============================================================================
# javax.lang.model — referenced by AutoValue/JavaPoet inside CameraX at
# compile time only, but R8 sees the references and complains. Not present on
# Android runtime. Suggested verbatim by R8 in missing_rules.txt.
# =============================================================================
-dontwarn javax.lang.model.**
