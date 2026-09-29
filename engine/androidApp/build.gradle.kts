plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin { jvmToolchain(21) }

android {
    namespace = "tz.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.territoriyazla.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // Server address: -Ptz.serverUrl=https://… (default: a server on the
        // developer's machine as seen from the Android emulator).
        val serverUrl = (project.findProperty("tz.serverUrl") as String?) ?: "http://10.0.2.2:8080"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
}
