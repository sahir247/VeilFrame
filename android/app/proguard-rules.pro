# Proguard / R8 rules for VeilFrame Android Release build

# =========================================================================
# Native C/C++ JNI Boundaries
# =========================================================================
# Preserve all native C/C++ JNI method names and their parameter types
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Preserve NCNN RealSR / Real-ESRGAN JNI bridge and callbacks
-keep class com.veilframe.app.upscale.inference.** { *; }
-keep interface com.veilframe.app.upscale.inference.** { *; }

# =========================================================================
# Computer Vision & Machine Learning Engines
# =========================================================================
# OpenCV SDK (JNI bindings, reflection, and core data types)
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# TensorFlow Lite (FairScan neural document edge segmentation)
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**


# ML Kit Barcode Scanning
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ZXing QR Code Core
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# =========================================================================
# Multimedia & Encoders
# =========================================================================
# FFmpegKit and its Smart Exception dependency
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-keep class dev.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**
-dontwarn com.arthenica.smartexception.**
-dontwarn dev.ffmpegkit.**

# AndroidX Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# AndroidX CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# =========================================================================
# AndroidX & Material UI Framework
# =========================================================================
-keep class androidx.lifecycle.** { *; }
-keep class * implements androidx.viewbinding.ViewBinding { *; }
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Custom Views instantiated via XML layout inflation
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

# =========================================================================
# Data Models, Enums, and Serialization
# =========================================================================
# Pinned model catalogues and geometry specifications
-keep class com.veilframe.app.upscale.model.** { *; }
-keep class com.veilframe.app.cv.segmentation.rembg.RembgModel { *; }
-keep class com.veilframe.app.qr.model.** { *; }
-keep class com.veilframe.app.util.AppVersionProvider { *; }

# Standard Enum methods required for reflection and serialization
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable / Serializable support
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# =========================================================================
# Kotlin Coroutines
# =========================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# =========================================================================
# PDFBox & BouncyCastle (Offline PDF Engine: Signature Verification & Decryption)
# =========================================================================
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# =========================================================================
# Attributes & BuildConfig
# =========================================================================
-keep class com.veilframe.app.BuildConfig { *; }
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
# =========================================================================
# Apache POI, XMLBeans, Log4j & Transitive Non-Android Dependencies
# =========================================================================
# Desktop Java AWT, Color, Geometry, and XML stream classes not present on Android runtime
-dontwarn java.awt.**
-dontwarn javax.xml.stream.**

# OSGi, Bnd SPI, and FindBugs compile-time annotations
-dontwarn org.osgi.framework.**
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**

# Optional XPath and SVG rendering engines (Saxon, Batik)
-dontwarn net.sf.saxon.**
-dontwarn org.apache.batik.**

# Apache POI, Log4j, XMLBeans, and Commons Compress internal reflection/warnings
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.commons.compress.**

# Preserve Apache POI document parsing & XMLBeans classes
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
