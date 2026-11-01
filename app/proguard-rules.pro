# Libraries ship their own consumer rules; these only silence optional references.
-dontwarn org.chromium.net.**
-dontwarn com.google.android.gms.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-keep class io.anibiar.app.BuildConfig { *; }
-keepclassmembers class * extends org.json.JSONObject { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-keep class org.mozilla.javascript.** { *; }

# the clip maker finds this constructor by reflection
-keep class androidx.media3.transformer.DefaultAssetLoaderFactory { <init>(...); }

# FFmpeg (clip smoothing)
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**
