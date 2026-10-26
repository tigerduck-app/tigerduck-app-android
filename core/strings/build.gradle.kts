// The phone app's localized strings, and its list of locales for Android 13+'s
// per-app language picker, generated from the app-translation submodule's
// source/*.json on every build (gradle/app-translation.gradle.kts). Nothing
// else: no code, no other resources. A module of their own so that the app's
// modules can share them, and so that a translation change rebuilds them alone.
// :wear generates its own copy and does not use this one.
//
// android.nonTransitiveRClass is on, so code reaches these as
// org.ntust.app.tigerduck.core.strings.R.string.x, never through :app's R;
// tools/dev/rewrite_string_imports.py moves code over.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.ntust.app.tigerduck.core.strings"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

apply(from = rootProject.file("gradle/app-translation.gradle.kts"))
val appTranslationStrings = tasks.named("generateAppTranslationStrings")

androidComponents {
    onVariants { variant ->
        // The task's class lives in that script, so its output is reached by name.
        variant.sources.res?.addGeneratedSourceDirectory(appTranslationStrings) { it.property("outputDir") as DirectoryProperty }
    }
}
