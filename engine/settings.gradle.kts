rootProject.name = "territoriya-zla"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// -Ptz.serverOnly: build only what the server needs (no Android SDK), as in
// engine/Dockerfile.
if (providers.gradleProperty("tz.serverOnly").isPresent) {
    include(":shared", ":server")
} else {
    include(":shared", ":server", ":androidApp")
}
