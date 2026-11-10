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
        versionCode = 38
        versionName = "0.18.8"

        // the living brushes' engine (MyPaint) is native code, built for tablets/phones (arm64)
        // and the Android Studio emulator (x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // MyPaint brush engine + json-c, built with CMake (Android Studio installs NDK and CMake)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
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

// No external libraries from Maven: Inkora uses only the Android framework + Kotlin, plus the
// open-source MyPaint brush engine (libmypaint, ISC) and json-c (MIT), built from src/main/cpp.
