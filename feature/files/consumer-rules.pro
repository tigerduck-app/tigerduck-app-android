# Applied by every app that minifies :feature:files.

# The file viewer's archives: Commons Compress reads Zstandard through the
# zstd-jni native library, which the app doesn't ship; Archives marks a
# Zstandard entry as unsupported before anything reaches that class.
# R8-generated via missing_rules.txt.
-dontwarn com.github.luben.zstd.ZstdInputStream
