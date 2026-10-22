plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub gives every build a number; it becomes the app's version so "Update available" can compare.
val runNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.lounge.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lounge.tv"
        minSdk = 22
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // A fixed signing key kept in the repo, so every new build can be installed over the old one
    // (a fresh random key each build would make Android refuse updates). It only identifies the app;
    // it is not a password to anything.
    signingConfigs {
        create("lounge") {
            storeFile = file("lounge.jks")
            storePassword = "lounge-open"
            keyAlias = "lounge"
            keyPassword = "lounge-open"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lounge")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lounge")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
