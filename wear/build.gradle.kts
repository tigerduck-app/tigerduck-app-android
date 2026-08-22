plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.ntust.app.tigerduck.wear"
    compileSdk = 36

    defaultConfig {
        // The wear app is shipped only alongside the play distribution of
        // the phone. F-Droid users do not get a wear build (decision:
        // wear depends on play-services-wearable for pairing, which is
        // GMS and incompatible with F-Droid policy). Hence no flavors —
        // wear is a single-variant module that pairs with the canonical
        // play phone applicationId.
        applicationId = "org.ntust.app.tigerduck"
        minSdk = 30
        targetSdk = 36
        versionCode = 21
        versionName = "1.4.3"
    }

    buildTypes {
        debug { }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// ---- Localization pipeline (mirrors :app) ------------------------------
//
// Wear strings live alongside phone strings in the `~/app-translation`
// submodule's source/*.json files (under the `shared` group, since they're
// reused on Apple Watch). The submodule's Python generator emits per-locale
// strings.xml under localization/generated/android/values-*/, and this opt-in
// task copies them into wear/src/main/res/. Run with `-PsyncLocalizations` to
// regenerate; otherwise builds use the committed copies.

val syncLocalizations by tasks.registering(Exec::class) {
    group = "localization"
    description = "Generate Android localization files from shared JSON sources."
    workingDir = rootProject.projectDir
    commandLine("python3", "tools/localization/sync_localizations.py")
    doFirst {
        val script = "tools/localization/sync_localizations.py"
        val python = listOf("python3", "python", "py").firstOrNull { candidate ->
            runCatching {
                val proc = ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                proc.waitFor() == 0 && output.contains("Python 3")
            }.getOrDefault(false)
        } ?: throw GradleException(
            "syncLocalizations requires Python 3 on PATH (tried python3, python, py)."
        )
        commandLine(python, script)
    }
}

val copyGeneratedAndroidLocalizations by tasks.registering(Copy::class) {
    group = "localization"
    description = "Copy localization/generated/android values-* resources into wear/src/main/res."
    dependsOn(syncLocalizations)

    val sourceDir = rootProject.layout.projectDirectory.dir("localization/generated/android")
    val destDir = layout.projectDirectory.dir("src/main/res")

    from(sourceDir) {
        include("values*/strings.xml")
        include("values-b+*/strings.xml")
    }
    into(destDir)
    includeEmptyDirs = false

    doFirst {
        val resDir = destDir.asFile
        fileTree(resDir) {
            include("values*/strings.xml")
            include("values-b+*/strings.xml")
        }.forEach { it.delete() }

        resDir.listFiles()
            ?.filter { it.isDirectory && (it.name.startsWith("values-") || it.name.startsWith("values-b+")) }
            ?.forEach { dir ->
                val remaining = dir.listFiles()
                if (remaining == null || remaining.isEmpty()) {
                    dir.delete()
                }
            }
    }
}

if (providers.gradleProperty("syncLocalizations").isPresent) {
    tasks.named("preBuild") {
        dependsOn(syncLocalizations)
        dependsOn(copyGeneratedAndroidLocalizations)
    }
}

dependencies {
    implementation(project(":shared"))

    // AppCompat ships AppCompatDelegate.setApplicationLocales — used to
    // mirror the phone's chosen UI language on the watch.
    implementation(libs.androidx.appcompat)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    // For Icons.Filled.Add / .Remove used in PaddingSettingsScreen.
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.tiles.material)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material3)
    implementation(libs.androidx.wear.protolayout.expression)

    implementation(libs.androidx.wear.watchface.complications.data.source)
    implementation(libs.androidx.wear.watchface.complications.data.source.ktx)

    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.play.services.wearable)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    // EncryptedSharedPreferences for the watch-side library credential mirror —
    // the synced phone password and token must not sit on disk in plain text.
    implementation(libs.security.crypto)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.gson)
}
