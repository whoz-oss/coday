plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.springdoc.openapi)
    `maven-publish`
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
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.named("bootJar"))
            artifact(tasks.named("sourcesJar"))

            pom {
                name.set("AgentOS Service")
                description.set("AgentOS Service - Backend orchestration service")
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

dependencies {
    // BOMs as Gradle-native platforms. Spring AI and Spring Cloud come after Spring Boot;
    // Gradle picks the highest version on conflicts across platforms.
    implementation(platform(libs.spring.boot.platform))
    implementation(platform(libs.spring.ai.bom))
    implementation(platform(libs.spring.cloud.bom))

    // AgentOS SDK - Contains plugin interfaces
    // Coordinates are substituted by composite build (see settings.gradle.kts)
    api("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")

    // Hardened server-side Git execution, kept outside the service so plugins can share it
    implementation("whoz-oss.agentos:agentos-git:${libs.versions.agentosService.get()}")

    // PF4J Spring Integration (for service only, not SDK)
    implementation(libs.pf4j.spring) {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }
    // OkHttp — HTTP client + logging interceptor.
    // Declared as a direct dependency so plugins (e.g. copilot) that depend on OkHttp
    // are guaranteed to find it on the classpath regardless of which AI providers are enabled.
    // Also used by Feign when the whoz profile activates feign.okhttp.enabled=true.
    implementation(libs.bundles.okhttp)

    // Jolokia — HTTP/JSON bridge over JMX so Spring Boot Admin can invoke JMX operations
    // (e.g. SchedulerEndpoint pause/resume) via its Jolokia integration.
    // Version kept in sync with libs.versions.toml jolokia entry.
    implementation(libs.jolokia.spring)

    // Spring Boot dependencies
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)

    // Spring Cloud — active only when the 'whoz' profile is used.
    // spring-cloud-starter-bootstrap activates the bootstrap context so that
    // bootstrap-whoz.yml is loaded before the application context starts.
    // spring-cloud-starter-config connects to the Spring Cloud Config Server.
    // spring-cloud-starter-eureka-client registers the service on Eureka.
    // All three are always on the classpath but disabled by default via
    // application-test.yml (tests) and absent of 'whoz' profile (standalone).
    implementation(libs.spring.cloud.starter.config)
    // Eureka client.
    implementation(libs.spring.cloud.starter.eureka.client)

    // OpenAPI / Swagger UI
    implementation(libs.springdoc.openapi.starter)
    implementation(libs.klogger)

    // Logstash Logback Encoder — JSON structured logging (used by logback-spring.xml docker profile)
    runtimeOnly(libs.logstash.logback.encoder)

    // Micrometer Datadog registry — exports tool-call metrics to Datadog.
    // Disabled by default (requires DATADOG_API_KEY + management.datadog.metrics.export.enabled=true).
    implementation(libs.micrometer.registry.statsd)

    // Jackson for JSON processing
    implementation(libs.jackson.module.kotlin)
    // agentos-http-plugin reads YAML OpenAPI documents through the service classloader (explicit contract)
    implementation(libs.jackson.dataformat.yaml)

    // Kotlin
    implementation(libs.bundles.kotlin.common)

    // Kotlin Coroutines
    implementation(libs.bundles.kotlin.coroutines)
    // MDC propagation across coroutines (log correlation, see io.whozoss.agentos.logging.LogContext)
    implementation(libs.kotlinx.coroutines.slf4j)

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
    // Netty 4.2.x and neo4j-java-driver 6.x required by Neo4j 2026.x are both managed by
    // the Spring Boot 4 BOM: no version override needed anymore.
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
    }

    // Test dependencies
    testImplementation(libs.spring.boot.starter.test)
    // Boot 4: MockMvc test auto-configuration (@AutoConfigureMockMvc) lives in its own starter.
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.mockk.spring)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.bundles.testing.spring)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.testcontainers.junit)
    // kotlinx-coroutines-test — UnconfinedTestDispatcher / runTest for coroutine loop tests.
    testImplementation(libs.kotlinx.coroutines.test)

    // Neo4j test harness: starts an embedded Neo4j in-process for testing.
    testImplementation(libs.neo4j.harness) {
        exclude(group = "org.slf4j")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
    }

    // DevTools causes classloader issues with PF4J plugins - DISABLED
    // developmentOnly("org.springframework.boot:spring-boot-devtools")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xemit-jvm-type-annotations")
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget(libs.versions.kotlinJvmTarget.get()),
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Neo4j embedded + Spring context caching across 2500+ tests requires more heap
    // than Gradle's default 512m. 2g gives comfortable headroom for the embedded engine,
    // cached Spring contexts, and parallel coroutine execution (Dispatchers.IO).
    maxHeapSize = "2g"
    // Docker Engine 29.x raised its minimum API version to 1.40.
    // Testcontainers 1.x / docker-java 3.4.x defaults to API v1.32 which is
    // rejected with HTTP 400. Force a supported version until Testcontainers
    // is upgraded to 2.x (which ships docker-java 3.7+ defaulting to 1.44).
    systemProperty("api.version", "1.44")
    // Disable JVM fast-throw optimisation so MockK can read the ClassCastException
    // message and auto-hint the correct type for mocked calls.
    jvmArgs("-XX:-OmitStackTraceInFastThrow")
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

// Dedicated port for the OpenAPI spec generation fork.
// Must differ from agentosPort so that generateOpenApiDocs can start its own
// forked JVM even when a dev instance is already running on agentosPort.
// apiDocsUrl must point to the same port so the plugin reads from the forked
// JVM, not from the live dev instance (which would produce a stale/wrong spec).
val openApiGenPort = 18124

openApi {
    // Output the spec alongside the agentos root so it can be committed
    outputDir.set(file("$rootDir/../openapi"))
    outputFileName.set("agentos-openapi.yaml")
    apiDocsUrl.set("http://localhost:$openApiGenPort/v3/api-docs.yaml")
    // Wait up to 60s for the app to be ready
    waitTimeInSeconds.set(60)
    // Activate the openapi profile so the app starts without real AI API keys.
    // embedded-bolt-port=0 (random OS-assigned port) as a command-line arg because it must
    // beat application-embedded-neo4j.yml (last active profile wins over the openapi one):
    // spec generation must not fail when a locally running dev stack already binds the
    // default embedded port 7688. The driver resolves the actual port at runtime.
    customBootRun {
        args.set(
            listOf(
                "--spring.profiles.active=openapi,embedded-neo4j",
                "--server.port=$openApiGenPort",
                "--agentos.persistence.embedded-bolt-port=0",
            ),
        )
    }
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}
