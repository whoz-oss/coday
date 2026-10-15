import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * factory-verification-core — PURE Kotlin/JVM library.
 *
 * Architectural invariant: this module is a library, not a service, not a
 * plugin, not an executable. It carries NO framework dependency:
 *   - no Spring (Core / Boot / Data / JDBC),
 *   - no HTTP client (OkHttp / Ktor / RestClient),
 *   - no PF4J, no database driver.
 *
 * Allowed and used: Kotlin standard library, JDK standard APIs
 * (`ProcessBuilder`, `java.nio.file`, `java.security.MessageDigest`,
 * `java.time`) and a light JSON library (Jackson) for the JSONL registry.
 *
 * It must compile and run even if Spring, PostgreSQL, AgentOS and node_modules
 * are unavailable — that is the central invariant of the instrument.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.whozoss.factory"
version = "0.0.1-SNAPSHOT"
description = "Factory Verification Core — pure Kotlin deterministic verification primitives"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.kotlinJvmTarget.get())
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get()))
    }
}

dependencies {
    // Kotlin standard library.
    implementation(libs.kotlin.stdlib)

    // Light JSON library for the append-only JSONL registry. jackson-module-kotlin
    // pulls jackson-databind / jackson-core / jackson-annotations transitively.
    implementation(libs.jackson.module.kotlin)

    // Test — pure unit tests, no Testcontainers, no database.
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
