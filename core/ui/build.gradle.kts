// The phone app's theme (ui/theme) and the components its screens share
// (ui/component), with the little they need that is no screen's: the haptics
// (ui/haptics), AppProcessing and the iOS placeholder helper (util); and what
// the app shares with the file viewer, the one link opener (ui/OpenLink.kt) and
// the FileProvider's authority (util). Each keeps its package, so moving here
// changed no import. What they ask of the app, they ask through an interface
// :app's Hilt graph binds (CoreUiModule): HapticPreferences,
// ScreenCaptureProtectionSwitch; PinMismatchHost takes a PinMismatchQuestions.
// Nothing here reaches :app, and no data class comes down with it.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.ntust.app.tigerduck.core.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        // The device tests of the scrollbar, the list anchor and the CJK centering.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // SecureScreen's BuildConfig.DEBUG: the library's debug variant is the one
        // the app's debug build takes, so it reads as the app's did.
        buildConfig = true
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
    implementation(project(":shared"))
    // Its R is the strings'; :app reaches them through its own dependency.
    implementation(project(":core:strings"))

    implementation(libs.androidx.core.ktx)
    // OpenLink's Custom Tabs.
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    // SecureScreen's and Haptics' entry points, and AppProcessing's @Inject.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
