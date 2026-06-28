# Keep all app classes
-keep class com.cristal.bristral.tristal.mistral.** { *; }

# Keep JNI native method
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Material
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Keep PackageInstaller session callback
-keep class android.content.pm.PackageInstaller$* { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
