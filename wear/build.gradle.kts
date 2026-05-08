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
        versionCode = 17
        versionName = "1.3.8"
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

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.gson)
}
