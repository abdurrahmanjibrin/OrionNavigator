# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Gson classes
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class com.orion.navigator.data.** { *; }

# Keep data classes for JSON parsing
-keepclassmembers class com.orion.navigator.data.BeaconInfo { *; }
-keepclassmembers class com.orion.navigator.data.BeaconConfig { *; }
-keepclassmembers class com.orion.navigator.data.BeaconInfoDto { *; }
