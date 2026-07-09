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
# MediaPipe Tasks Vision + full Google runtime transitive graph
# =============================================================================
# Native code + reflection to load the .task model asset. Keep the whole
# tasks-vision + protobuf tree.
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
# MediaPipe pulls Guava + Google Flogger + errorprone transitively. Flogger
# walks the stack to identify the caller class; when Guava/Flogger get
# obfuscated the caller class name no longer matches the expected pattern
# and Graph.<clinit> throws "no caller found on the stack for: <obfuscated>".
# Symptom: FaceLandmarker init fails with ExceptionInInitializerError chained
# to IllegalStateException in Graph static init. Keeping the whole com.google
# subtree is broader than ideal but the transitive graph is deep and hard to
# pin down without another iteration cycle.
-keep class com.google.common.** { *; }
-keep class com.google.flogger.** { *; }
-keep class com.google.errorprone.annotations.** { *; }
-keep class com.google.j2objc.annotations.** { *; }
-keep class com.google.thirdparty.** { *; }
-dontwarn com.google.common.**
-dontwarn com.google.flogger.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn com.google.thirdparty.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.**

# =============================================================================
# OpenCV
# =============================================================================
# OpenCVLoader.initLocal() loads libopencv_java4.so dynamically. All org.opencv
# classes are JNI entry points.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# =============================================================================
# Ktor (HTTP client used by Supabase) + related kotlinx modules
# =============================================================================
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-dontwarn io.ktor.**
# Ktor depends on kotlinx-io for streaming. Keep the API classes so R8 doesn't
# strip stream adapters that Ktor loads reflectively.
-keep class kotlinx.io.** { *; }
-dontwarn kotlinx.io.**
# Coroutines internals are complex and Ktor exposes them across module boundaries.
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
# Kotlinx datetime — Supabase-kt uses Instant/LocalDate serializers for timestamp
# columns in sessions / telemetry_records. Must survive minification.
-keep class kotlinx.datetime.** { *; }
-dontwarn kotlinx.datetime.**
# kotlinx-serialization-json exposes internal reflection paths that R8 flags.
-keep class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**
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
# WorkManager depends on Room internally (WorkDatabase). Room generates *_Impl
# classes at compile time that are instantiated reflectively via
# Class.getDeclaredConstructor() — the default constructor gets stripped by R8
# unless we keep it explicitly. Symptom without this rule: app crashes on
# launch with NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>[]
-keep class androidx.work.impl.** { *; }
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
-keepclassmembers class **_Impl {
    <init>(...);
}
-dontwarn androidx.room.**

# =============================================================================
# AndroidX Startup (ContentProvider-based library initialization)
# =============================================================================
-keep class androidx.startup.** { *; }
-keep class * implements androidx.startup.Initializer { *; }

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
