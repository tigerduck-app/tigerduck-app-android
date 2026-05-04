import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// google-services plugin is consumed only by the `play` flavor. The JSON
// lives under src/play/ (not app root) so the plugin's per-variant scanner
// only registers a `process<Variant>GoogleServices` task for play* — fdroid
// variants stay clean and don't trip on the package-id mismatch caused by
// our `.fdroid` applicationIdSuffix. The plugin still has to be applied at
// project level, which we gate on the JSON's presence so a fresh checkout
// without it (e.g. the F-Droid buildserver) is buildable end-to-end.
val hasGoogleServices = file("src/play/google-services.json").exists() ||
    file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    // Without this, building the fdroid flavor fails because the plugin
    // walks every variant and demands a google-services.json for each. With
    // the JSON only under src/play/, fdroid* variants now warn instead of
    // erroring; play* variants still pick up the file and process it.
    extensions.configure<com.google.gms.googleservices.GoogleServicesPlugin.GoogleServicesPluginConfig> {
        missingGoogleServicesStrategy =
            com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy.IGNORE
    }
}

// Pull dev push-server config out of root-level local.properties so the URL
// + shared secret never end up in VCS. project.findProperty() only reads
// gradle.properties, so do it manually here.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = ""): String =
    localProps.getProperty(key) ?: (project.findProperty(key) as? String) ?: default

android {
    namespace = "org.ntust.app.tigerduck"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.ntust.app.tigerduck"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "1.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Earliest pin-set expiration across network_security_config.xml.
        // Used by TigerDuckApp to surface a logcat warning when pins are
        // within 30 days of expiry; without a runtime check, post-expiry the
        // platform silently falls back to system CA trust with no UI signal.
        // 2027-01-18T00:00:00Z = 1800230400000L epoch ms.
        buildConfigField("long", "PIN_EXPIRY_EPOCH", "1800230400000L")
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "PUSH_BASE_URL",
                "\"${localProp("pushBaseUrl", "https://api.tigerduck.app/v2")}\"",
            )
            buildConfigField(
                "String",
                "PUSH_SHARED_SECRET",
                "\"${localProp("pushSharedSecret")}\"",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystoreFile = file("keystore.jks")
            if (keystoreFile.exists() && System.getenv("KEYSTORE_PASSWORD")?.isNotEmpty() == true) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField(
                "String",
                "PUSH_BASE_URL",
                "\"${System.getenv("PUSH_BASE_URL")
                    ?: localProp("pushBaseUrlRelease", "https://api.tigerduck.app/v2")}\"",
            )
            buildConfigField(
                "String",
                "PUSH_SHARED_SECRET",
                "\"${System.getenv("PUSH_SHARED_SECRET") ?: ""}\"",
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

    // Two distribution channels:
    //   * play   — Google Play Store + sideload. Uses FCM for real-time push.
    //   * fdroid — F-Droid. 100% FOSS, no Google Play Services. The
    //              announcements list still works (manual refresh / open-app
    //              poll); push is simply absent. Add UnifiedPush here later
    //              if real-time delivery becomes a requirement.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            // No suffix — this is the canonical applicationId.
        }
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            versionNameSuffix = "-fdroid"
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(rootProject.file("name-abbr").path)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.jsoup)

    // Security
    implementation(libs.security.crypto)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // QR Code
    implementation(libs.zxing.core)

    // Firebase Cloud Messaging — confined to the `play` flavor so the
    // F-Droid APK contains zero Google Play Services code. FcmService.kt
    // and FcmBootstrap.kt live under src/play/ and are not compiled when
    // building fdroid* variants.
    "playImplementation"(platform(libs.firebase.bom))
    "playImplementation"(libs.firebase.messaging)

    // In-app browser (Custom Tabs)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.appcompat)

    // Background work scheduling
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Glance (home screen widgets)
    implementation(libs.glance.appwidget)

    // Markdown rendering for announcement bodies
    implementation(libs.markdown.renderer.m3)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

val syncLocalizations by tasks.registering(Exec::class) {
    group = "localization"
    description = "Generate Android localization files from shared JSON sources."
    workingDir = rootProject.projectDir
    // Placeholder; the real interpreter is resolved in doFirst so detection
    // happens at execution time, not project sync.
    commandLine("python3", "tools/localization/sync_localizations.py")
    doFirst {
        val script = "tools/localization/sync_localizations.py"
        // Probe the common Python 3 launchers across Linux / macOS / Windows.
        val python = listOf("python3", "python", "py").firstOrNull { candidate ->
            runCatching {
                val proc = ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                proc.waitFor() == 0 && output.contains("Python 3")
            }.getOrDefault(false)
        } ?: throw GradleException(
            "syncLocalizations requires Python 3 on PATH (tried python3, python, py). " +
                "Install Python 3 from https://www.python.org/ and re-run."
        )
        commandLine(python, script)
    }
}

val copyGeneratedAndroidLocalizations by tasks.registering(Copy::class) {
    group = "localization"
    description = "Copy localization/generated/android values-* resources into app/src/main/res."

    // Ensure the generator ran first.
    dependsOn(syncLocalizations)

    val sourceDir = rootProject.layout.projectDirectory.dir("localization/generated/android")
    val destDir = layout.projectDirectory.dir("src/main/res")

    // Only copy valid Android resource qualifier directories.
    from(sourceDir) {
        include("values*/strings.xml")
        include("values-b+*/strings.xml")
    }

    into(destDir)
    includeEmptyDirs = false

    // Atomically clear stale generated files just before copying. If syncLocalizations
    // fails, this doFirst never runs, so committed locale files remain on disk.
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
