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
        versionCode = 10030
        versionName = "2.4.0"
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

    // As in :app. The watch takes the language the phone app is set to
    // (MainActivity.attachBaseContext), not the watch's own, and Play's
    // per-language splits install only the watch's: any other choice would
    // fall back to values/ (English). Every language goes in the base APK.
    bundle {
        language {
            enableSplit = false
        }
    }

    testOptions {
        unitTests {
            // Merged resources, so a Robolectric test reads the strings the watch ships.
            isIncludeAndroidResources = true
            // As in :app: Robolectric's API 36 sandbox needs jdk.internal.access.
            all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
            // As in :app, so ReleaseLogRulesTest runs again when the R8 rules change.
            all { it.inputs.file("proguard-rules.pro") }
        }
    }
}

// Wear strings live alongside the phone's in app-translation's source/*.json
// (under the `shared` group, since they're reused on Apple Watch).
apply(from = rootProject.file("gradle/app-translation.gradle.kts"))
val appTranslationStrings = tasks.named("generateAppTranslationStrings")

androidComponents {
    onVariants { variant ->
        // The task's class lives in that script, so its output is reached by name.
        variant.sources.res?.addGeneratedSourceDirectory(appTranslationStrings) { it.property("outputDir") as DirectoryProperty }
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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // Hand-made pin documents for WatchTlsPinsTest.
    testImplementation(libs.bouncycastle.bcprov)
    // Compose UI tests under Robolectric (the padding bars); the manifest's
    // test activity goes into debug builds only, never a release, so these
    // run as debug unit tests, the variant CI runs, as in the phone app.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// The watch's own dependencies, exported into the phone app's play
// resources: the watch declares `standalone = false`, so it never reaches a
// user who doesn't also have the phone app, and the phone's Open-source
// licences page is a far better place to read a licence than a watch face.
// Committed and applied only under -PexportLicenses, for the same reasons as
// the phone's own list — see app/build.gradle.kts. The bundled notices that
// go with this list are written by :app:exportBundledNoticesWear, which this
// task is finalized by.
if (providers.gradleProperty("exportLicenses").isPresent) {
    apply(plugin = libs.plugins.aboutlibraries.get().pluginId)
    extensions.configure<com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension> {
        collect {
            configPath.set(rootProject.file("app/aboutlibraries"))
            includePlatform.set(false)
        }
        export {
            prettyPrint.set(true)
        }
        exports {
            create("release") {
                outputFile.set(rootProject.file("app/src/play/res/raw/aboutlibraries_wear.json"))
            }
        }
    }
}
