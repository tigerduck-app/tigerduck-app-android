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

# As in :app, release builds drop Log.v, Log.d and Log.i, which logcat hands
# to adb and to bug reports, and "return 0" drops a call whose result the
# compiled Kotlin still reads. Log.w and Log.e stay, so they name an
# exception by its class and never print its message.
-assumenosideeffects class android.util.Log {
    public static int v(...) return 0;
    public static int d(...) return 0;
    public static int i(...) return 0;
}

# As in :app, keep the exception class names those warnings and errors log;
# no release keeps the mapping that would decode an obfuscated one.
-keepnames class * extends java.lang.Throwable
