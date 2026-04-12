plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vouchflow.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        // Library version surfaced in POM for consumers.
        version = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Play Integrity (attestation)
    implementation("com.google.android.play:integrity:1.4.0")

    // Biometric — 1.2.0-alpha accepts ComponentActivity (no AppCompat required)
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // ComponentActivity — required by BiometricPrompt 1.2.0-alpha
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Lifecycle — ProcessLifecycleOwner for background detection
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
