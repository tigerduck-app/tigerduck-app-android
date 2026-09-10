plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.ntust.app.tigerduck.wear"
    compileSdk = 37

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
        // Phone versionCode + 10000. Play namespaces version codes per
        // applicationId, not per artifact, so the watch bundle cannot reuse
        // the phone's — the second upload is rejected with "version code
        // already used". The offset also keeps the watch code the *higher*
        // of the two: where both artifacts match a device, Play serves the
        // highest version code, and only the watch bundle requires
        // android.hardware.type.watch. It separates the ranges only while the
        // phone stays under 10000, which version-bumped.yaml asserts rather
        // than leaves to chance. versionName still tracks the phone exactly.
        versionCode = 10025
        versionName = "2.0.2"
    }

    // Mirrors :app. The watch APK/AAB carries the same applicationId as the
    // play phone build, so Play requires it to be signed with the same key —
    // an upload signed with anything else is rejected as a different app. The
    // release workflow decodes the shared keystore into wear/keystore.jks
    // alongside app/keystore.jks; see .github/workflows/release-manual.yaml.
    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug { }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Guarded exactly like :app so a checkout without the keystore
            // still builds — CI's PR gate compiles this variant unsigned, and
            // an unsigned build lands as wear-release-unsigned.apk rather than
            // failing configuration.
            val keystoreFile = file("keystore.jks")
            if (keystoreFile.exists() && System.getenv("KEYSTORE_PASSWORD")?.isNotEmpty() == true) {
                signingConfig = signingConfigs.getByName("release")
            }
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
// strings.xml under app-translation/generated/android/values-*/, and this opt-in
// task copies them into wear/src/main/res/. Run with `-PsyncLocalizations` to
// regenerate; otherwise builds use the committed copies.

val syncLocalizations = tasks.register<Exec>("syncLocalizations") {
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

val copyGeneratedAndroidLocalizations = tasks.register<Copy>("copyGeneratedAndroidLocalizations") {
    group = "localization"
    description = "Copy app-translation/generated/android values-* resources into wear/src/main/res."
    dependsOn(syncLocalizations)

    val sourceDir = rootProject.layout.projectDirectory.dir("app-translation/generated/android")
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
