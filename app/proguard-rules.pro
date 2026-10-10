# Vosk
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** {
	volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.admin.DeviceAdminReceiver
-keep public class * extends android.accessibilityservice.AccessibilityService
