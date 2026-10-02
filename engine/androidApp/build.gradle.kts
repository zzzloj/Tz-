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
        // Server address. Default: the test server on Railway. A server on the
        // developer's machine from the emulator: -Ptz.serverUrl=http://10.0.2.2:8080
        val serverUrl = (project.findProperty("tz.serverUrl") as String?) ?: "https://tz-engine-production.up.railway.app"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
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
    implementation(libs.ktor.client.core)

    // Screen tests on the JVM (Robolectric), no emulator needed.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.ktor.client.mock)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}
