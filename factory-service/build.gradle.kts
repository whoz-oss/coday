import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.springdoc.openapi)
}

group = "io.whozoss.factory"
version = "0.0.1-SNAPSHOT"
description = "Factory Service — autonomous Kotlin/Spring Boot backend for the Factory"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.kotlinJvmTarget.get())
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xemit-jvm-type-annotations")
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get()))
    }
}

dependencies {
    // Spring Boot
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jdbc)

    // Persistence — Spring Data JDBC (NOT JPA) + Flyway on PostgreSQL
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    // Embedded DB for the `openapi` profile only (spec generation without PostgreSQL).
    runtimeOnly(libs.h2)

    // OpenAPI / Swagger UI
    implementation(libs.springdoc.openapi.starter)

    // Logging
    implementation(libs.klogger)

    // Jackson
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    // Kotlin
    implementation(libs.bundles.kotlin.common)

    // Test
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mockk)
    testImplementation(libs.mockk.spring)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker Engine 29.x raised its minimum API version to 1.40.
    // Testcontainers 1.x / docker-java 3.4.x defaults to API v1.32 which is
    // rejected with HTTP 400. Force a supported version.
    systemProperty("api.version", "1.44")
    jvmArgs("-XX:-OmitStackTraceInFastThrow")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("factory-service.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = projectDir
}

// ========================================
// OpenAPI spec generation
// ========================================
// springdoc-openapi-gradle-plugin 1.9.0 is not compatible with Gradle's
// configuration cache: its forkedSpringBootRun task holds references to other
// task instances which cannot be serialised. Exclude the affected tasks until
// the plugin ships a fix.
listOf("forkedSpringBootRun", "forkedSpringBootStop", "generateOpenApiDocs").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        notCompatibleWithConfigurationCache(
            "springdoc-openapi-gradle-plugin 1.9.0 holds task references incompatible with configuration cache",
        )
    }
}

// Dedicated port for the OpenAPI spec generation fork, distinct from the dev
// port (8141) so generation can run while a dev instance is already up.
val openApiGenPort = 18141

openApi {
    outputDir.set(file("$projectDir/openapi"))
    outputFileName.set("factory-openapi.yaml")
    apiDocsUrl.set("http://localhost:$openApiGenPort/v3/api-docs.yaml")
    waitTimeInSeconds.set(60)
    customBootRun {
        args.set(
            listOf(
                "--spring.profiles.active=openapi",
                "--server.port=$openApiGenPort",
            ),
        )
    }
}
