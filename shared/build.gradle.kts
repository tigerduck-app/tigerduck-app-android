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

    // CourseChangeFixture, the Thursday both this module's timeline tests and the app's screen
    // tests build their feeds from, so there is one copy of it.
    testFixtures {
        enable = true
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
    // org.json is Android framework code, stubbed in plain unit tests; CourseChanges.decode reads
    // the feed with it. Test-only, so the licence lists do not change.
    testImplementation(libs.json)
    testFixturesImplementation(libs.json)
    // TaipeiClockTest drives minuteTicks() in virtual time.
    testImplementation(libs.kotlinx.coroutines.test)
    // Hand-made certificates for PinnedTrustManagerTest's local TLS server.
    testImplementation(libs.okhttp.tls)
}
