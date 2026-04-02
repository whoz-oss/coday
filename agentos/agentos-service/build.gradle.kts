plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.springdoc.openapi)
}

group = "whoz-oss.agentos"
version = libs.versions.agentosService.get()
description = "AgentOS Service - Backend orchestration service"

java {
    toolchain {
        languageVersion =
            JavaLanguageVersion.of(
                libs.versions.java
                    .get()
                    .toInt(),
            )
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.kotlinJvmTarget.get())
}

dependencies {
    // AgentOS SDK - Contains plugin interfaces
    // Coordinates are substituted by composite build (see settings.gradle.kts)
    api("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")

    // PF4J Spring Integration (for service only, not SDK)
    implementation(libs.pf4j.spring) {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }

    // Spring Boot dependencies
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    // OpenAPI / Swagger UI
    implementation(libs.springdoc.openapi.starter)
    implementation(libs.klogger)

    // Jackson for JSON processing
    implementation(libs.jackson.module.kotlin)

    // Kotlin
    implementation(libs.bundles.kotlin.common)

    // Kotlin Coroutines
    implementation(libs.bundles.kotlin.coroutines)

    // Spring AI
    implementation(libs.bundles.spring.ai)

    // Neo4j persistence (Bolt-based, for server/container deployments)
    implementation(libs.spring.boot.starter.data.neo4j)

    // Neo4j embedded engine (Community Edition)
    // Included as optional runtime — only activated when agentos.persistence.mode=embedded-neo4j.
    // The embedded engine starts an in-process Neo4j instance and exposes a Bolt port;
    // Spring Data Neo4j connects to it identically to a standalone server.
    // This eliminates the Docker prerequisite for local single-user deployments.
    //
    // Exclusions:
    // - slf4j-nop: Neo4j 2026.x pulls in a competing SLF4J binding that shadows Logback,
    //   causing Spring Boot's LogbackLoggingSystem to fail with NOPLoggerFactory.
    //   Spring Boot's own Logback binding (via spring-boot-starter-logging) must win.
    // - neo4j-java-driver: Neo4j 2026.x requests driver 6.x; Spring Boot BOM pins 5.28.9.
    //   We let Spring Boot win to avoid two versions of the driver on the classpath.
    implementation(libs.neo4j.embedded) {
        // ── Logging ─────────────────────────────────────────────────────────────
        // Neo4j 2026.x bundles its own SLF4J API and provider JARs.
        // Having a second copy of slf4j-api on the classpath causes SLF4J's
        // ServiceLoader to bind to NOPLoggerFactory (no Logback visible in that
        // classloader context), which then wins the global static binding race
        // before Spring Boot can register LogbackServiceProvider.
        // Fix: exclude ALL SLF4J artifacts from Neo4j — use Spring Boot's copy.
        exclude(group = "org.slf4j")
        // Also exclude any log4j-over-slf4j bridges Neo4j may pull in
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
        // ── Driver ──────────────────────────────────────────────────────────────
        // Neo4j 2026.x requests driver 6.x; Spring Boot BOM pins 5.28.9.
        // Exclude to let Spring Boot's version win and avoid two driver versions.
        exclude(group = "org.neo4j.driver", module = "neo4j-java-driver")
    }

    // Test dependencies
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.bundles.testing.spring)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.testcontainers.junit)
    // Neo4j test harness: starts an embedded Neo4j in-process for testing.
    // neo4j-harness 2026.x requires Netty 4.2.x (BoltServer uses 4.2 APIs).
    // Spring Boot BOM pins Netty 4.1.x, which Gradle's conflict resolution selects
    // by default, causing NoClassDefFoundError at BoltServer startup.
    // We force Netty 4.2.x on the testRuntimeClasspath via a constraint below.
    testImplementation(libs.neo4j.harness) {
        exclude(group = "org.slf4j")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
    }

    // DevTools causes classloader issues with PF4J plugins - DISABLED
    // developmentOnly("org.springframework.boot:spring-boot-devtools")
}

dependencyManagement {
    imports {
        mavenBom(
            libs.spring.ai.bom
                .get()
                .toString(),
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget(libs.versions.kotlinJvmTarget.get()),
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Neo4j 2026.x (embedded engine + test harness) requires Netty 4.2.x for BoltServer.
// Spring Boot BOM pins Netty 4.1.x, which Gradle's conflict resolution selects by
// default (lower version wins). Force only the core Netty transport modules to 4.2.x
// on the test classpath so that BoltServer can load its dependencies.
//
// Excluded from forcing:
// - netty-tcnative-* : native TLS helper — only published for specific 4.1.x builds;
//   forcing 4.2.x causes a resolution failure (artifact does not exist on Maven Central).
//
// Spring Boot's HTTP stack uses spring-boot-starter-web (Tomcat), NOT Reactor Netty,
// so upgrading these Netty modules on the test classpath has no effect on the HTTP server.
val nettyCoreModules =
    setOf(
        "netty-common",
        "netty-buffer",
        "netty-transport",
        "netty-transport-native-epoll",
        "netty-transport-native-kqueue",
        "netty-transport-native-unix-common",
        // netty-transport-classes-* contain the platform-specific IoHandler classes
        // (e.g. KQueueIoHandler, EpollIoHandler) introduced in Netty 4.2.x.
        // BoltServer references these at init time; they must match the forced version.
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
configurations.testRuntimeClasspath {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.netty" && requested.name in nettyCoreModules) {
            useVersion(libs.versions.netty.get())
            because("neo4j-embedded 2026.x requires Netty 4.2.x; Spring Boot BOM pins 4.1.x")
        }
    }
}

// Configure the bootJar task
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("agentos-service.jar")
}

// ========================================
// OpenAPI spec generation
// ========================================
val agentosPort = 8124

openApi {
    // Output the spec alongside the agentos root so it can be committed
    outputDir.set(file("$rootDir/../openapi"))
    outputFileName.set("agentos-openapi.yaml")
    apiDocsUrl.set("http://localhost:$agentosPort/v3/api-docs.yaml")
    // Wait up to 60s for the app to be ready
    waitTimeInSeconds.set(60)
    // Activate the openapi profile so the app starts without real AI API keys
    customBootRun {
        args.set(listOf("--spring.profiles.active=openapi", "--server.port=$agentosPort"))
    }
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}
