plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dark.animetailv2.module"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.dark.animetailv2.module"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { viewBinding = true }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
}
