# Add project specific ProGuard rules here.
-keep class org.ntust.app.tigerduck.network.model.** { *; }
-keep class org.ntust.app.tigerduck.data.model.** { *; }
-dontrepackage

# Gson — TypeToken<List<Course>>() {} anonymous subclasses lose their generic
# signature under R8 full mode (default since AGP 8.x), which makes
# fromJson(json, type) deserialize each element as LinkedTreeMap. The cast
# back to the expected element type is unchecked at runtime, and the bug
# only surfaces when downstream code touches a property — e.g. cached
# courses fail to load on reopen because cached.associateBy { it.courseNo }
# throws ClassCastException, which the coroutine scope swallows.
-keepattributes Signature
-keepattributes *Annotation*

-keep class * extends com.google.gson.TypeAdapter { *; }
-keep class * extends com.google.gson.TypeAdapterFactory { *; }
-keep class * extends com.google.gson.JsonSerializer { *; }
-keep class * extends com.google.gson.JsonDeserializer { *; }

-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

-keep,allowobfuscation,allowshrinking,allowoptimization class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking,allowoptimization class * extends com.google.gson.reflect.TypeToken
