plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes the run number, so every published APK is newer than the last one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.karianoot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.karianoot.app"
        minSdk = 31
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        ndk { abiFilters += "arm64-v8a" }
    }

    // One fixed key, so each new APK installs over the old one and keeps the downloaded models.
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/karianoot.jks")
            storePassword = "karianoot"
            keyAlias = "karianoot"
            keyPassword = "karianoot"
        }
    }

    // "personal": the owner's own build (GitHub releases, includes the personal-only voice "נמר").
    // "store": the Google Play build (no personal-only voices; its own package name; signed with the Play upload key).
    flavorDimensions += "channel"
    productFlavors {
        create("personal") {
            dimension = "channel"
            // The fixed key, so each new APK installs over the old one and keeps the downloaded models.
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "STORE", "false")
        }
        create("store") {
            dimension = "channel"
            applicationId = "com.karianoot.narration"
            buildConfigField("boolean", "STORE", "true")
            // The upload key comes from CI secrets only (never in git); without them the bundle is left unsigned.
            System.getenv("KARIANOOT_UPLOAD_KEYSTORE")?.takeIf { it.isNotEmpty() }?.let { path ->
                signingConfig = signingConfigs.create("upload") {
                    storeFile = file(path)
                    storePassword = System.getenv("KARIANOOT_UPLOAD_STORE_PASSWORD")
                    keyAlias = System.getenv("KARIANOOT_UPLOAD_KEY_ALIAS")
                    keyPassword = System.getenv("KARIANOOT_UPLOAD_KEY_PASSWORD")
                }
            }
        }
    }
    // Where "דיווח על תוכן" sends reports (set in CI; empty = the user picks an app to send with).
    defaultConfig { buildConfigField("String", "SUPPORT_EMAIL", "\"${System.getenv("KARIANOOT_SUPPORT_EMAIL") ?: ""}\"") }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    // Native libraries stay inside the APK (not unpacked at install), which keeps installs small.
    packaging { jniLibs { useLegacyPackaging = false } }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":engine"))
    // ONNX Runtime (CPU). The NPU build (onnxruntime-android-qnn + qnn-runtime, ~235 MB of libraries)
    // comes back when a model is actually compiled for the NPU.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")

    // Screenshot tests (Robolectric + Roborazzi): render the screens on the JVM in CI.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
