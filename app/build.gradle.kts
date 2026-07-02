plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dark.animetailv2.module"
    compileSdk = 35
    
    defaultConfig {
        applicationId = "com.dark.animetailv2.module"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "3.0.0"
    }
    
    buildFeatures { viewBinding = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

// YOU MUST ADD THIS BLOCK:
dependencies {
    // If you placed api-82.jar in the libs folder as recommended:
    compileOnly(files("libs/api-82.jar"))
    
    // OR if you skipped that and want to download it from JCenter instead:
    // compileOnly("de.robv.android.xposed:api:82")
}
