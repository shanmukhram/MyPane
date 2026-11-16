plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.pane.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.pane.launcher"
        minSdk = 26
        // The target TV runs API 29; targeting it keeps legacy storage for the photo picker.
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Sideloaded only: reuse the local debug key so `adb install -r` keeps working.
        getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
    buildFeatures { buildConfig = false }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging { resources { excludes += setOf("META-INF/**", "kotlin/**", "**.kotlin_builtins", "DebugProbesKt.bin") } }
    dependenciesInfo { includeInApk = false }
}
