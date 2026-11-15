plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.siyu.musique"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.siyu.musique"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        // Development version only. Standing rule: tag a version only when
        // its features are complete, never one version per commit. Step 0
        // gets no version tag; v0.1 is tagged only when search and play
        // are complete and tested.
        versionName = "0.1.0-dev"
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
    // Compose BOM 2025.10.00 pins matching Compose library versions.
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Step 1: Bilibili API stack. Versions verified Nov 14, 2026
    // against Maven Central / Google Maven metadata:
    // - OkHttp 4.12.0 (jar present on Maven Central); no Retrofit,
    //   WBI signing needs manual query building.
    // - kotlinx-serialization-json 1.8.1 (jvm jar present on Maven
    //   Central). Only the JsonElement API is used, so no
    //   serialization compiler plugin is needed.
    // - Coil 2.7.0 (coil-compose AAR present on Maven Central).
    // - DataStore preferences 1.3.0 (latest/release on Google Maven).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
