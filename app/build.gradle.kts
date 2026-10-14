plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.matheus.arceusnative"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.matheus.arceusnative"
        minSdk = 26
        targetSdk = 28
        versionCode = 6
        versionName = "0.6"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
