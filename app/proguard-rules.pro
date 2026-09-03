# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
-dontwarn org.bouncycastle.**

-keep class nopalito.app.RecentDocument* { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
# to have at least org.opencv.core.CvException
-keep class org.opencv.core.** { *; }
# OpenCV 5 moved goodFeaturesToTrack here; reached reflectively from
# :imageprocessing (HarrisCorners), so R8 would otherwise strip it.
-keep class org.opencv.features.** { *; }

# TensorFlow Lite / LiteRT - preserve symbols for Google Play
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
# R8 missing_rules.txt (AGP 9.3 / litert-support 1.4.2 references legacy Delegate)
# LiteRT 2.2.0 no longer ships org.tensorflow.lite.Delegate, but litert-support still references it (GpuDelegateProxy)
# See app/build/outputs/mapping/release/missing_rules.txt
-dontwarn org.tensorflow.lite.Delegate
-dontwarn org.tensorflow.lite.**

# Keep native method names for debugging
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep native method names for stack traces
-renamesourcefileattribute SourceFile