plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.ntust.app.tigerduck.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        // See consumer-rules.pro: the classes Gson reads and writes, kept in every app.
        consumerProguardFiles("consumer-rules.pro")
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
    // Ed25519 for the TLS pin document's signature (PinDocumentVerifier). Conscrypt has no
    // Ed25519 on every Android version minSdk 29 covers; the lightweight API is used directly,
    // with no JCA provider installed.
    implementation(libs.bouncycastle.bcprov)
    // Library QR client is shared so the watch can call api.lib.ntust.edu.tw
    // directly without duplicating the request/response wire schema.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    // ZXing encoder for QR bitmaps; pure Java, works on Wear OS.
    implementation(libs.zxing.core)
    // LibraryService uses Mutex / Dispatchers / withContext.
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // TaipeiClockTest drives minuteTicks() in virtual time.
    testImplementation(libs.kotlinx.coroutines.test)
    // Hand-made certificates for PinnedTrustManagerTest's local TLS server.
    testImplementation(libs.okhttp.tls)
}
