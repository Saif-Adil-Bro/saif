# Keep Gson-serialized models (used directly as Retrofit bodies and Room entities)
-keep class com.saif.mosque.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Retrofit / OkHttp / Gson
-keep class retrofit2.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
