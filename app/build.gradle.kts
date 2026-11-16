plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.siyu.musique"
    compileSdk = 36

    // Signing: CI signs every debug build with one persistent
    // key, so updates never trip a signature mismatch (CI
    // runners are ephemeral, and the default debug key changed
    // on every run). The keystore lives ONLY in the repo's
    // GitHub Actions secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD,
    // KEY_ALIAS): the workflow decodes it to a temp file and
    // points KEYSTORE_PATH at it. When KEYSTORE_PATH is unset
    // (local Studio builds), no signing config is set and AGP
    // falls back to the default debug key; a local build and a
    // CI build carry different signatures and must not be mixed
    // without an uninstall. NEVER commit a keystore or a password
    // to this repo: it is PUBLIC. The first key (keystore/
    // musique.keystore, committed Nov 15, 2026) was exposed and
    // abandoned the same day; the current key has never been in
    // the repo. Never regenerate the key casually, or installed
    // copies will refuse the next update.
    val ciKeystorePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    if (ciKeystorePath != null) {
        signingConfigs {
            create("musique") {
                storeFile = file(ciKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                // PKCS12 stores a single password: the key
                // password is the store password.
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.siyu.musique"
        minSdk = 26
        targetSdk = 36
        // The versionCode is the CI run number on GitHub Actions
        // (monotonic across runs, so every published APK compares
        // newer than the last) and 1 for local builds. The in-app
        // update check compares this against the versionCode in
        // the release's version.json asset.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        // v0.1.1 tagged Nov 15, 2026 (English UI, playlist
        // rename, follow-finger player dismiss). Standing rule:
        // tag a version only when its features are complete,
        // never one version per commit.
        versionName = "0.1.3"
    }

    buildTypes {
        debug {
            if (ciKeystorePath != null) {
                signingConfig = signingConfigs.getByName("musique")
            }
        }
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

    // Step 3: Media3 ExoPlayer for streaming playback, plus the
    // datasource artifact for CacheDataSource. Version 1.11.1
    // verified Nov 14, 2026 against Google Maven metadata
    // (androidx/media3 maven-metadata.xml): the latest stable;
    // 1.12.0-beta01 is a pre-release and is skipped.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    // Background playback: MediaSessionService + notification.
    // Same 1.11.1 train; the AAR was downloaded and its API
    // (MediaSessionService, MediaSession.Callback, connection
    // result, notification provider) verified Nov 15, 2026.
    implementation("androidx.media3:media3-session:1.11.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
