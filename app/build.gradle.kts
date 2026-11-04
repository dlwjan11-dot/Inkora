plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.abdulmedia.inkora"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abdulmedia.inkora"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.7.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // For testing on your own tablet the debug key is fine.
            // Before publishing on Google Play, create your own release key (Build > Generate Signed App Bundle).
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

// No external libraries: Inkora uses only the Android framework + Kotlin.
