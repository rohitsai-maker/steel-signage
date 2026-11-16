plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.steelsignage.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.steelsignage.player"
        minSdk = 21          // Android 5.0+ (covers almost every Android TV / TV box / Fire TV)
        targetSdk = 34
        // Each GitHub Actions build gets a higher number, so TVs can detect and install updates.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "0.1.$build"
        buildConfigField("String", "UPDATE_REPO", "\"rohitsai-maker/steel-signage\"")
        // Where the TV gets its content when nothing is configured on the TV itself.
        // A GitHub release link = simple mode (no server). Set to "" to always ask for a server address.
        buildConfigField("String", "DEFAULT_SOURCE", "\"https://github.com/rohitsai-maker/steel-signage/releases/tag/gym-screen\"")
    }
    buildFeatures { buildConfig = true }

    // Every build must be signed with the SAME key, otherwise TVs refuse to install updates.
    // The workflow creates android/signing/steel.jks on its first run and commits it, then reuses it.
    val steelKey = file("../signing/steel.jks")
    signingConfigs {
        create("steel") {
            storeFile = steelKey
            storePassword = "steelsignage"
            keyAlias = "steel"
            keyPassword = "steelsignage"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (steelKey.exists()) signingConfigs.getByName("steel") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
