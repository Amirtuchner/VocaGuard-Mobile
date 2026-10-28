# Add project specific ProGuard rules here.

# Keep line numbers for crash-stack readability
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================
# Linphone SDK — keep all classes and interfaces so R8 does not
# strip CoreListenerStub callbacks used by VocaGuardSipManager
# ============================================================
-keep class org.linphone.** { *; }
-keep interface org.linphone.** { *; }
-dontwarn org.linphone.**

# ============================================================
# LiteRT (TensorFlow Lite)
# ============================================================
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.**

# ============================================================
# Room
# ============================================================
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static <methods>;
}
-dontwarn androidx.room.**

# ============================================================
# Kotlin Coroutines
# ============================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============================================================
# App data / domain classes (serialised by Room)
# ============================================================
-keepclassmembers class io.vocaguard.** {
    public <init>(...);
    <fields>;
}

# ============================================================
# Enums (ScamType stored as String in Room)
# ============================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================
# Kotlin metadata (reflection used by coroutines / serialization)
# ============================================================
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ============================================================
# Tenjin attribution SDK + its identifier providers.
# Tenjin loads the Google Advertising ID, AppSet ID and Install Referrer via
# REFLECTION, so R8 can't see the references and strips those classes in release.
# Result: advertising_id comes back null and Tenjin rejects every event with
# 202 "invalid device identifier" — zero installs recorded in production (debug
# works because R8 isn't applied). Diagnosed 2026-10-28. Keep them all.
# ============================================================
-keep class com.tenjin.** { *; }
-dontwarn com.tenjin.**
-keep class com.google.android.gms.ads.identifier.** { *; }
-keep class com.google.android.gms.appset.** { *; }
-dontwarn com.google.android.gms.appset.**
-keep class com.android.installreferrer.** { *; }
-dontwarn com.android.installreferrer.**

# ============================================================
# Misc AndroidX suppressions
# ============================================================
-dontwarn androidx.lifecycle.**
-dontwarn androidx.compose.**
