# Add project specific ProGuard rules here.

# Keep line numbers for crash-stack readability
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

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
-keepclassmembers class com.example.vocaguard.data.** {
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
# Misc AndroidX suppressions
# ============================================================
-dontwarn androidx.lifecycle.**
-dontwarn androidx.compose.**
