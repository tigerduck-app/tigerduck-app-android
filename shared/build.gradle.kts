plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.ntust.app.tigerduck.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.gson)
    // Library QR client is shared so the watch can call api.lib.ntust.edu.tw
    // directly without duplicating the request/response wire schema.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    // ZXing encoder for QR bitmaps; pure Java, works on Wear OS.
    implementation(libs.zxing.core)
    // LibraryService uses Mutex / Dispatchers / withContext.
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
