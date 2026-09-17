# Proguard rules for VeilFrame Android Release build

# Chaquopy Python Runtime
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

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
