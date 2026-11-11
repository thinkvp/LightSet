plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.lightset"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lightset"
        minSdk = 29
        targetSdk = 35
        // CI passes the run number so each release installs over the last.
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toInt()
        versionCode = buildNumber ?: 1
        versionName = if (buildNumber != null) "0.1.$buildNumber" else "0.1"
        // MapLibre's native code for 64-bit ARM phones only (the Edge 60 Pro and nearly every
        // phone since 2019); all four ABIs would double the APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key lives in GitHub secrets; CI exposes it through these env vars.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = "lightset"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Sun position, sunrise/sunset and golden/blue hour.
    implementation("org.shredzone.commons:commons-suncalc:3.11")
    // The Sun tab's camera view.
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    // The Sun tab's map (OpenStreetMap tiles from OpenFreeMap, no key needed).
    implementation("org.maplibre.gl:android-sdk:12.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
