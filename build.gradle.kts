plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.likewa.gamelingo"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.likewa.gamelingo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.1"
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
