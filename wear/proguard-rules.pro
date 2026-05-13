# SchedulePersistence$CourseWire is Gson-deserialized from the phone-sent
# JSON. Without { *; } R8 renames the JVM fields and Gson silently leaves
# every reference field null — the constructor map to Course then NPEs and
# the cached schedule disappears from the watch.
-keep class org.ntust.app.tigerduck.wear.data.SchedulePersistence$* { *; }

# TypeToken<List<CourseWire>> needs its generic signature to survive R8
# full mode (default since AGP 8.x), otherwise Gson falls back to
# LinkedTreeMap. Same failure mode the phone-side rules guard against.
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
