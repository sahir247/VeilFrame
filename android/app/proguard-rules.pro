# Proguard rules for VeilFrame Android Release build


# App classes and Media Backend
-keep class com.veilframe.app.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# AndroidX & ViewBinding
-keep class androidx.lifecycle.** { *; }
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# FFmpegKit and its Smart Exception dependency
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-keep class dev.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**
-dontwarn com.arthenica.smartexception.**
-dontwarn dev.ffmpegkit.**

# Attributes & Reflection
-keep class com.veilframe.app.BuildConfig { *; }
-keepattributes *Annotation*
-keepattributes Signature


