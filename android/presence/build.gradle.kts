plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.heospresence"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.heospresence"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // La BRAVIA est en 32 bits uniquement
        ndk { abiFilters += "armeabi-v7a" }
    }
}

dependencies {
    implementation(libs.mlkit.face)
}
