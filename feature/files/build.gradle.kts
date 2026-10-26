// The file viewer: what a downloaded file opens in when the app can show it
// itself (files/ and ui/screen/files/), with the libraries only it uses: Media3
// for video and sound, Commons Compress and XZ for archives, and the Office
// viewer's scripts (assets/viewer/). Each file keeps its package, and the
// Activity keeps its declaration in :app's manifest, so moving here changed no
// import and no merged manifest. What it takes from the app, it takes through
// FileViewerHost, which :app binds (di/FilesModule.kt).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.ntust.app.tigerduck.feature.files"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        // The archives' Zstandard, which the app leaves out; see the file.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // As in :app: android.util.Log returns rather than throws "not mocked".
            isReturnDefaultValues = true
            // Merged resources for Robolectric: stringResource, and the
            // ui-test-manifest's ComponentActivity.
            isIncludeAndroidResources = true
            // As in :app: Robolectric's API 36 sandbox needs jdk.internal.access.
            all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
            // As in :app, whose suite these tests ran in.
            all { it.maxHeapSize = "1g" }
        }
    }
}

dependencies {
    implementation(project(":core:strings"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.markdown.renderer.m3)

    // Video and sound.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Archives: ZIP, tar and 7z, and XZ for 7z's LZMA and .xz.
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // FileViewerActivity is an @AndroidEntryPoint; FileViewerHost comes through Hilt.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
