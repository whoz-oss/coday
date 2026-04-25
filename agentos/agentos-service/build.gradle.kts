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
    implementation(libs.spring.boot.starter.security)

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

    // Netty 4.2.x — explicit direct dependency to override Spring Boot BOM's 4.1.x pin.
    // Neo4j 2026.x BoltServer requires MultiThreadIoEventLoopGroup and KQueueIoHandler
    // which were introduced in Netty 4.2.x. Spring Boot BOM pins 4.1.x; without an
    // explicit direct dependency IntelliJ's run configuration classpath uses 4.1.x
    // regardless of resolutionStrategy rules (which only apply to Gradle's own resolution).
    // Declaring these as direct runtime dependencies ensures IntelliJ picks up 4.2.x
    // after a Gradle sync, with no VM option workarounds needed.
    runtimeOnly("io.netty:netty-transport-classes-epoll:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-transport-classes-kqueue:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-common:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-buffer:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-transport:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-handler:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-codec:${libs.versions.netty.get()}")
    runtimeOnly("io.netty:netty-resolver:${libs.versions.netty.get()}")

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
        // Neo4j 2026.x ships two competing logging artifacts:
        //
        // 1. org.slf4j:slf4j-api — a second copy of the SLF4J API JAR.
        //    Having two copies causes SLF4J's ServiceLoader to see duplicate
        //    providers and bind to NOPLoggerFactory before Logback can register.
        //    Fix: exclude the whole org.slf4j group; Spring Boot owns that copy.
        exclude(group = "org.slf4j")
        //
        // 2. org.neo4j:neo4j-slf4j-provider is excluded globally via configurations.all
        //    below, which ensures it is removed from ALL configurations including
        //    IntelliJ's module classpath after Gradle sync.
        //
        // Also exclude any log4j→SLF4J bridges Neo4j may pull in transitively.
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
        // ── Driver ──────────────────────────────────────────────────────────────
        // Neo4j 2026.x requests driver 6.x; Spring Boot BOM pins 5.28.9.
        // Exclude to let Spring Boot's version win and avoid two driver versions.
        exclude(group = "org.neo4j.driver", module = "neo4j-java-driver")
    }

    // Test dependencies
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.mockk.spring)
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
    // Docker Engine 29.x raised its minimum API version to 1.40.
    // Testcontainers 1.x / docker-java 3.4.x defaults to API v1.32 which is
    // rejected with HTTP 400. Force a supported version until Testcontainers
    // is upgraded to 2.x (which ships docker-java 3.7+ defaulting to 1.44).
    systemProperty("api.version", "1.44")
}

// Neo4j 2026.x ships org.neo4j:neo4j-slf4j-provider which registers SLF4JLogBridge
// as an SLF4J service provider. When it wins the ServiceLoader race over Logback,
// Spring Boot's LogbackLoggingSystem fails with:
//   "LoggerFactory is not a Logback LoggerContext"
// Excluding it from the individual neo4j-embedded dependency declaration is not
// always sufficient (IntelliJ may resolve a stale classpath). This global exclusion
// across ALL configurations guarantees it never appears regardless of how the
// classpath is assembled.
configurations.all {
    exclude(group = "org.neo4j", module = "neo4j-slf4j-provider")
}

// Neo4j 2026.x (embedded engine) requires Netty 4.2.x for BoltServer.
// Spring Boot BOM pins Netty 4.1.x, which Gradle's conflict resolution selects by
// default (lower version wins). Force the core Netty transport modules to 4.2.x
// across ALL configurations — runtime, test, and IntelliJ's module classpath.
//
// Excluded from forcing:
// - netty-tcnative-* : native TLS helper — only published for specific 4.1.x builds;
//   forcing 4.2.x causes a resolution failure (artifact does not exist on Maven Central).
//
// Spring Boot's HTTP stack uses spring-boot-starter-web (Tomcat), NOT Reactor Netty.
// Reactor Netty is not on this module's runtime classpath, so this upgrade is safe.
val nettyCoreModules =
    setOf(
        "netty-common",
        "netty-buffer",
        "netty-transport",
        "netty-transport-native-epoll",
        "netty-transport-native-kqueue",
        "netty-transport-native-unix-common",
        // netty-transport-classes-* contain the platform-specific IoHandler classes
        // (e.g. KQueueIoHandler, EpollIoHandler, MultiThreadIoEventLoopGroup)
        // introduced in Netty 4.2.x. BoltServer references these at init time.
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
            because("neo4j-embedded 2026.x requires Netty 4.2.x; Spring Boot BOM pins 4.1.x")
        }
    }
}

// Configure the bootJar task
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("agentos-service.jar")
}

// Set the working directory for bootRun to the agentos/ root so that
// relative paths in application.yml (plugins/, agents/, aimodel/, aiprovider/,
// data/) resolve to the same location regardless of which directory Gradle
// is invoked from. Without this, bootRun uses the subproject directory
// (agentos-service/) and misses the plugin JARs and YAML configs at the root.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // rootDir is agentos-service/ (subproject root); parentFile is agentos/ (composite root)
    // where plugins/, agents/, aimodel/, aiprovider/, and data/ all live.
    workingDir = rootDir.parentFile
}

// ========================================
// OpenAPI spec generation
// ========================================
// springdoc-openapi-gradle-plugin 1.9.0 is not compatible with Gradle 9's
// configuration cache: its forkedSpringBootRun task holds references to other
// task instances which cannot be serialised. Exclude the affected tasks from
// the configuration cache until the plugin ships a fix.
listOf("forkedSpringBootRun", "forkedSpringBootStop", "generateOpenApiDocs").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        notCompatibleWithConfigurationCache(
            "springdoc-openapi-gradle-plugin 1.9.0 holds task references incompatible with configuration cache",
        )
    }
}

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
