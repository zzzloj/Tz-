plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("tz.server.MainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.bcrypt)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Tests read the real world data from the repository's content/.
    systemProperty("tz.content", rootProject.projectDir.resolve("../content").absolutePath)
    // Database tests run when TZ_TEST_DATABASE_URL points to an empty PostgreSQL database.
    System.getenv("TZ_TEST_DATABASE_URL")?.let { environment("TZ_TEST_DATABASE_URL", it) }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.named<JavaExec>("run") {
    systemProperty("tz.content", rootProject.projectDir.resolve("../content").absolutePath)
}
