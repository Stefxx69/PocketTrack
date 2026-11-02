plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pockettrack.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pockettrack.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so CI can produce an installable release APK.
            // Replace with your own signing config before publishing to a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.ar:core:1.44.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    // QR scanning inside our own app, so it uses PocketTrack's camera permission
    // (Play services' scanner fails silently when Play services lacks camera access).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Scan replay: smooth playback with sound, and exact per-frame timestamps for the overlay.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
}
