# Applied to every app that minifies :shared, so the watch keeps what the
# phone keeps. LibraryService writes and reads the library API's request and
# response classes with Gson, which matches JSON keys to field names, and none
# of them names its fields with @SerializedName: renamed by R8 they post
# {"a":…} and read every answer as empty. :app has always kept the whole
# package itself (app/proguard-rules.pro); :wear had no such rule, so the
# watch's library QR could not work in a release build.
-keep class org.ntust.app.tigerduck.shared.** { *; }
