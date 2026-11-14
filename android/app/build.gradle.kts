plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.heosdemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.heosdemo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Doit correspondre aux cibles de scripts/build-android.sh
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Optional shared signing identity, supplied locally or restored by CI.
    // Never commit keystores or passwords. Without it, Android's local debug key is used.
    val signingPath = System.getenv("HEOS_KEYSTORE_FILE")
    if (!signingPath.isNullOrBlank()) {
        val sharedSigning = signingConfigs.create("heosShared") {
            storeFile = file(signingPath)
            storePassword = requireNotNull(System.getenv("HEOS_KEYSTORE_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("HEOS_KEY_ALIAS"))
            keyPassword = System.getenv("HEOS_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: storePassword
        }
        buildTypes.getByName("debug").signingConfig = sharedSigning
    }

    buildFeatures { compose = true }

    // jniLibs/ est rempli par scripts/build-android.sh (cargo-ndk)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jna) { artifact { type = "aar" } }
}
