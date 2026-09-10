# Add project specific ProGuard rules here.
-keep class org.ntust.app.tigerduck.network.model.** { *; }
-keep class org.ntust.app.tigerduck.data.model.** { *; }
# Course moved from data.model to :shared in v1.4.0. Without this keep,
# R8 renames Course's fields and Gson can't repopulate them from cache
# files written by v1.3.x — every reference field deserializes as null
# and WearScheduleBridge$CourseDto.<init> NPEs from TigerDuckApp.onCreate
# on the first open after upgrade.
-keep class org.ntust.app.tigerduck.shared.** { *; }
-keep class org.ntust.app.tigerduck.data.cache.DataCache$* { *; }
# Wire DTO Gson-serializes to the watch. Unannotated fields, so R8 must
# not rename them — otherwise the phone sends obfuscated JSON keys the
# watch-side CourseWire can't recognize.
-keep class org.ntust.app.tigerduck.wear.WearScheduleBridge$* { *; }

# Glance addresses a widget provider by its *canonical class name* across
# upgrades: GlanceAppWidgetManager persists a receiver -> provider name map in
# its own DataStore, getGlanceIds(provider) resolves through the reverse of it,
# and the map is never pruned — it is only rebuilt when missing outright. So a
# provider name that gets reused for a different class in a later build resolves
# to whichever receiver held it in the previous one, and a layout can be pushed
# into another widget's appWidgetId. That is the same shape as the v1.4.0 Gson
# incident above: a name that outlives the build that wrote it must not be
# obfuscated.
#
# The manifest pins the receivers. It does NOT pin the GlanceAppWidget
# subclasses — checked against seeds.txt, no rule seeds them — yet R8 leaves
# their names alone today anyway, for reasons no rule in the merged
# configuration accounts for. That is luck, not a guarantee, so pin them.
# Cheap: -keepnames still allows shrinking and member obfuscation.
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver

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
