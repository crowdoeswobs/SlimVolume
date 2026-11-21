import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing key lives outside the repo; keystore.properties (git-ignored) points at it.
// Debug and release use the same key so in-app updates install over test builds.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.thorhud.volume"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thorhud.volume"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Where the in-app update check looks for releases.
        buildConfigField("String", "GITHUB_REPO", "\"crowdoeswobs/thor-slim-volume\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val release = signingConfigs.findByName("release")
        getByName("debug") { if (release != null) signingConfig = release }
        getByName("release") {
            isMinifyEnabled = false
            if (release != null) signingConfig = release
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
