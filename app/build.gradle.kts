plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.abdulmedia.inkora"
    compileSdk = 36
    // the NDK and CMake installed from Android Studio's SDK Manager
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.abdulmedia.inkora"
        minSdk = 29
        targetSdk = 36
        versionCode = 75
        versionName = "0.35.0"
        manifestPlaceholders["appLabel"] = "Inkora"

        // the living brushes' engine (MyPaint) is native code, built for tablets/phones (arm64)
        // and the Android Studio emulator (x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // the interface fonts are read straight from the package: keep them uncompressed
    androidResources {
        noCompress += listOf("ttf")
    }

    // MyPaint brush engine + json-c, built with CMake (Android Studio installs NDK and CMake)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // One key for the Run button on every computer (each computer would otherwise make its own),
    // so "Inkora Dev" installed from home updates from work too, and the other way round.
    // Only for test builds: Google Play builds are signed with the upload key.
    signingConfigs {
        create("dev") {
            storeFile = file("inkora-dev.jks")
            storePassword = "inkoradev"
            keyAlias = "inkoradev"
            keyPassword = "inkoradev"
        }
    }

    buildTypes {
        // the Run button in Android Studio: installs beside the Inkora from Google Play / the website
        // (its own name and icon, its own artworks), so nothing has to be uninstalled to test
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Inkora Dev"
            signingConfig = signingConfigs.getByName("dev")
        }
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

// No external libraries from Maven: Inkora uses only the Android framework + Kotlin, plus the
// open-source MyPaint brush engine (libmypaint, ISC) and json-c (MIT), built from src/main/cpp.
