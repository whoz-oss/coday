import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.springdoc.openapi)
    `maven-publish`
}

group = "whoz-oss.factory"
version = libs.versions.factoryService.get()
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
    // Factory SDK — Spring-agnostic plugin extension points.
    // Coordinates substituted by the composite build (see settings.gradle.kts).
    api("whoz-oss.factory:factory-sdk:${libs.versions.factorySdk.get()}")

    // Factory Verification Core — pure Kotlin deterministic verification
    // primitives (target-repo `factory/verification.json` manifest + oracle
    // execution). Coordinates substituted by the composite build.
    implementation("whoz-oss.factory:factory-verification-core:${libs.versions.factoryService.get()}")

    // PF4J plugin framework + Spring integration (service runtime only, never in the SDK).
    implementation(libs.pf4j) {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }
    implementation(libs.pf4j.spring) {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }

    // Spring Boot
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    // Persistence — Spring Data Neo4j (embedded engine or standalone server).
    // Flyway, the PostgreSQL driver and the interim JDBC adapters were removed as
    // part of the Postgres → embedded Neo4j swap: every factory aggregate is now
    // graph-backed and there is no relational datasource left.
    implementation(libs.spring.boot.starter.data.neo4j)

    // Netty 4.2.x — explicit direct dependency to override Spring Boot BOM's 4.1.x pin.
    // Neo4j 2026.x BoltServer requires MultiThreadIoEventLoopGroup and KQueueIoHandler,
    // introduced in Netty 4.2.x. See the resolutionStrategy below.
    runtimeOnly("io.netty:netty-transport-classes-epoll:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-transport-classes-kqueue:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-common:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-buffer:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-transport:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-handler:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-codec:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-resolver:${libs.versions.netty.get()}")

    // Neo4j embedded engine (Community Edition) — activated only when
    // factory.persistence.mode=embedded-neo4j. The embedded engine starts an
    // in-process Neo4j instance and exposes a Bolt port; Spring Data Neo4j
    // connects to it exactly like to a standalone server. This removes the
    // Docker prerequisite for local single-user deployments.
    implementation(libs.neo4j.embedded) {
        // Neo4j ships a competing SLF4J provider/binding that shadows Logback,
        // causing Spring Boot's LogbackLoggingSystem to fail with NOPLoggerFactory.
        exclude(group = "org.slf4j")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
        // Neo4j 2026.x requests driver 6.x; Spring Boot BOM pins 5.x.
        // Let Spring Boot's driver win to keep a single driver on the classpath.
        exclude(group = "org.neo4j.driver", module = "neo4j-java-driver")
    }

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
    testImplementation(libs.mockk)
    testImplementation(libs.mockk.spring)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Neo4j test harness — starts an in-process Neo4j for repository tests
    // without Docker and without the full embedded engine / Netty 4.2 BoltServer.
    testImplementation(libs.neo4j.harness) {
        exclude(group = "org.slf4j")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
    }
}

// Neo4j 2026.x ships org.neo4j:neo4j-slf4j-provider, which registers an SLF4J
// service provider that can win the ServiceLoader race over Logback. Excluding
// it from every configuration guarantees it never shadows Spring Boot's logging.
configurations.all {
    exclude(group = "org.neo4j", module = "neo4j-slf4j-provider")
}

// Neo4j 2026.x (embedded engine and test harness) requires Netty 4.2.x for
// BoltServer. Spring Boot BOM pins Netty 4.1.x, which Gradle's conflict
// resolution selects by default (lower version wins). Force the core Netty
// transport modules to 4.2.x across all configurations.
//
// Excluded from forcing: netty-tcnative-* (native TLS helper) only exists for
// specific 4.1.x builds; forcing 4.2.x would fail resolution.
val nettyCoreModules =
    setOf(
        "netty-common",
        "netty-buffer",
        "netty-transport",
        "netty-transport-native-epoll",
        "netty-transport-native-kqueue",
        "netty-transport-native-unix-common",
        "netty-transport-classes-epoll",
        "netty-transport-classes-kqueue",
        "netty-handler",
        "netty-handler-proxy",
        "netty-codec",
        "netty-codec-http",
        "netty-codec-http2",
        "netty-codec-socks",
        "netty-resolver",
        "netty-resolver-dns",
    )
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.netty" && requested.name in nettyCoreModules) {
            useVersion(libs.versions.netty.get())
            because("neo4j 2026.x requires Netty 4.2.x; Spring Boot BOM pins 4.1.x")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Neo4j embedded/harness + cached Spring contexts need more than Gradle's
    // default heap.
    maxHeapSize = "2g"
    jvmArgs("-XX:-OmitStackTraceInFastThrow")
    // JDK 25 restricted native access: Neo4j's embedded engine and the test
    // harness load native helpers reflectively, which the JVM reports as a
    // warning unless native access is enabled for the unnamed module.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("factory-service.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = projectDir
}

// The two narrow HTTP_API contracts are versioned under openapi/ and remain the
// source of truth. Package them into the application classpath so serving them
// does not depend on the process working directory.
tasks.named<ProcessResources>("processResources") {
    from("openapi") {
        include(
            "factory-worker-http-api-submit-step-result.yaml",
            "factory-workstream-http-api.yaml",
        )
        into("openapi")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.named("bootJar"))

            pom {
                name.set("Factory Service")
                description.set("Factory Service — autonomous Kotlin/Spring Boot backend for the Factory")
                url.set("https://github.com/whoz-oss/coday")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("whoz-oss")
                        name.set("Whoz OSS")
                        email.set("oss@whoz.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/whoz-oss/coday.git")
                    developerConnection.set("scm:git:ssh://github.com/whoz-oss/coday.git")
                    url.set("https://github.com/whoz-oss/coday")
                }
            }
        }
    }

    repositories {
        mavenLocal()

        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/whoz-oss/coday")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
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
                "--spring.profiles.active=openapi,embedded-neo4j",
                "--server.port=$openApiGenPort",
            ),
        )
    }
}
