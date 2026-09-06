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
# No protobuf-keep rule: nothing on the classpath provides
# com.google.protobuf (only DataStore's relocated external-protobuf exists,
# and no source imports com.google.protobuf), so a keep rule for
# GeneratedMessageLite would only produce an "Unresolved class name" warning.
# If a real protobuf dependency is ever added, re-add:
# -keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
# to have at least org.opencv.core.CvException
-keep class org.opencv.core.** { *; }
# OpenCV 5 moved goodFeaturesToTrack here; reached reflectively from
# :imageprocessing (HarrisCorners), so R8 would otherwise strip it.
-keep class org.opencv.features.** { *; }

# TensorFlow Lite / LiteRT - preserve symbols for Google Play
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
# The whole LiteRT graph is pinned to the 1.4.2 line (see libs.versions.toml),
# which still ships org.tensorflow.lite.Delegate. The dontwarn below stays as
# safety for R8 rule extraction, not as a fix for a version skew.
# See app/build/outputs/mapping/release/missing_rules.txt
-dontwarn org.tensorflow.lite.Delegate
-dontwarn org.tensorflow.lite.**

# Keep native method names for debugging
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep native method names for stack traces
-renamesourcefileattribute SourceFile