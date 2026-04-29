plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.ntust.app.tigerduck"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.ntust.app.tigerduck"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    sourceSets {
        getByName("main") {
            assets.directories.add(rootProject.file("course-name-abbr").path)
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

    // In-app browser (Custom Tabs)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.appcompat)

    // Background work scheduling
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Glance (home screen widgets)
    implementation(libs.glance.appwidget)

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

val cleanCopiedAndroidLocalizations by tasks.registering(Delete::class) {
    group = "localization"
    description = "Remove previously copied localized strings.xml resources from app/src/main/res."

    val resDir = layout.projectDirectory.dir("src/main/res")

    // Delete generated strings.xml files first.
    delete(fileTree(resDir) {
        include("values*/strings.xml")
        include("values-b+*/strings.xml")
    })

    // Then remove any now-empty locale-specific values-* directories.
    doLast {
        val root = resDir.asFile
        root.listFiles()
            ?.filter { it.isDirectory && (it.name.startsWith("values-") || it.name.startsWith("values-b+")) }
            ?.forEach { dir ->
                val remaining = dir.listFiles()
                if (remaining == null || remaining.isEmpty()) {
                    dir.delete()
                }
            }
    }
}

val copyGeneratedAndroidLocalizations by tasks.registering(Copy::class) {
    group = "localization"
    description = "Copy localization/generated/android values-* resources into app/src/main/res."

    // Ensure the generator ran first.
    dependsOn(syncLocalizations)
    dependsOn(cleanCopiedAndroidLocalizations)

    val sourceDir = rootProject.layout.projectDirectory.dir("localization/generated/android")
    val destDir = layout.projectDirectory.dir("src/main/res")

    // Only copy valid Android resource qualifier directories.
    from(sourceDir) {
        include("values*/strings.xml")
        include("values-b+*/strings.xml")
    }

    into(destDir)
    includeEmptyDirs = false
}

tasks.named("preBuild") {
    dependsOn(syncLocalizations)
    dependsOn(copyGeneratedAndroidLocalizations)
}
