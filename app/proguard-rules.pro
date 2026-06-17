# Add project specific ProGuard rules here.
-keep class org.ntust.app.tigerduck.network.model.** { *; }
-keep class org.ntust.app.tigerduck.data.model.** { *; }
# Course moved from data.model to :shared in v1.4.0. Without this keep,
# R8 renames Course's fields and Gson can't repopulate them from cache
# files written by v1.3.x — every reference field deserializes as null
# and WearScheduleBridge$CourseDto.<init> NPEs from TigerDuckApp.onCreate
# on the first open after upgrade.
-keep class org.ntust.app.tigerduck.shared.** { *; }
# Gson DTOs that live outside the model.** packages. Most fields here lack
# @SerializedName, so without { *; } R8 renames the JVM fields and Gson
# silently deserializes nulls — same failure mode as the TypeToken bug.
-keep class org.ntust.app.tigerduck.announcements.BulletinSummary { *; }
-keep class org.ntust.app.tigerduck.announcements.BulletinDetail { *; }
-keep class org.ntust.app.tigerduck.announcements.BulletinListResponse { *; }
-keep class org.ntust.app.tigerduck.announcements.OrgLabel { *; }
-keep class org.ntust.app.tigerduck.announcements.TagLabel { *; }
-keep class org.ntust.app.tigerduck.announcements.TaxonomyResponse { *; }
-keep class org.ntust.app.tigerduck.announcements.SubscriptionRule { *; }
-keep class org.ntust.app.tigerduck.announcements.SubscriptionsResponse { *; }
-keep class org.ntust.app.tigerduck.announcements.SubscriptionsPutRequest { *; }
-keep class org.ntust.app.tigerduck.data.cache.DataCache$* { *; }
# What's-new content (assets/whatsnew.json) is Gson-deserialized into
# WhatsNewContent. Without this keep, R8 renames its fields and Gson reads
# nulls — same failure mode as the other unannotated DTOs above.
-keep class org.ntust.app.tigerduck.update.WhatsNewContent { *; }
# Wire DTO Gson-serializes to the watch. Unannotated fields, so R8 must
# not rename them — otherwise the phone sends obfuscated JSON keys the
# watch-side CourseWire can't recognize.
-keep class org.ntust.app.tigerduck.wear.WearScheduleBridge$* { *; }

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
