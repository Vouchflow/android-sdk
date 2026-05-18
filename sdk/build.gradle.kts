import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish") version "0.29.0"
}

android {
    namespace = "dev.vouchflow.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Biometric — 1.2.0-alpha accepts ComponentActivity (no AppCompat required)
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // ComponentActivity — required by BiometricPrompt 1.2.0-alpha
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Lifecycle — ProcessLifecycleOwner for background detection
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Encrypted storage — fallback token store (EncryptedPrefsTokenStore) used when
    // AccountManager is unavailable (fresh-install race, work profiles) or disabled by config.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.code.gson:gson:2.10.1")

    // Instrumented integration tests
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // FragmentActivity is available transitively via biometric → appcompat → fragment;
    // no explicit appcompat dep needed (biometric pins appcompat:1.2.0 via strict constraint).
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    androidTestImplementation("junit:junit:4.13.2")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates(
        groupId = "dev.vouchflow",
        artifactId = "android-sdk",
        version = providers.gradleProperty("VERSION_NAME").getOrElse("1.0.0"),
    )

    pom {
        name = "Vouchflow Android SDK"
        description = "Device-native identity verification for Android apps."
        url = "https://github.com/vouchflow/android-sdk"
        inceptionYear = "2025"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "vouchflow"
                name = "Vouchflow"
                url = "https://github.com/vouchflow"
            }
        }
        scm {
            url = "https://github.com/vouchflow/android-sdk"
            connection = "scm:git:git://github.com/vouchflow/android-sdk.git"
            developerConnection = "scm:git:ssh://git@github.com/vouchflow/android-sdk.git"
        }
    }
}
