plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.syncmusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.syncmusic"
        minSdk = 24
        targetSdk = 35
        versionCode = 20
        versionName = "2.7"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0")
}
