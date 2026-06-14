plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
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
    // AgentOS SDK - Contains plugin interfaces
    // Coordinates are substituted by composite build (see settings.gradle.kts)
    api("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")

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

    // Spring Boot dependencies
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)

    // Spring Cloud — active only when the 'whoz' profile is used.
    // spring-cloud-starter-bootstrap activates the bootstrap context so that
    // bootstrap-whoz.yml is loaded before the application context starts.
    // spring-cloud-starter-config connects to the Spring Cloud Config Server.
    // spring-cloud-starter-eureka-client registers the service on Eureka.
    // All three are always on the classpath but disabled by default via
    // application-test.yml (tests) and absent of 'whoz' profile (standalone).
    // Spring Cloud BOM (Northfields 2025.0.x) — imported via platform() so Gradle's native
    // dependency resolution picks up the managed versions. The spring-dependency-management
    // plugin's dependencyManagement {} block does not reliably propagate BOM versions for
    // artifacts without an explicit version in composite builds.
    implementation(platform(libs.spring.cloud.bom))
    implementation(libs.spring.cloud.starter.config)
    // Eureka client.
    implementation(libs.spring.cloud.starter.eureka.client)

    // OpenAPI / Swagger UI
    implementation(libs.springdoc.openapi.starter)
    implementation(libs.klogger)

    // Logstash Logback Encoder — JSON structured logging (used by logback-spring.xml docker profile)
    runtimeOnly(libs.logstash.logback.encoder)

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

    // Neo4j Migrations — Flyway-equivalent for Neo4j.
    // Applies versioned .cypher files from classpath:neo4j/migrations/ at startup.
    // Replaces the manual Neo4jSchemaInitializer ApplicationRunner.
    implementation(libs.neo4j.migrations.spring.boot.starter)

    // Neo4j embedded engine (Community Edition)
    // Activated when agentos.persistence.mode=embedded-neo4j.
    // Version 5.26.x is aligned with the production server (neo4j:5.26-community),
    // so Spring Boot's managed driver (5.28.9) is compatible with both.
    // The embedded engine starts an in-process Neo4j instance and exposes a Bolt port;
    // Spring Data Neo4j connects to it identically to a standalone server.
    implementation(libs.neo4j.embedded) {
        // Neo4j ships org.slf4j:slf4j-api as a transitive dependency, creating a duplicate
        // SLF4J API JAR that causes the ServiceLoader to bind NOPLoggerFactory before Logback.
        // Exclude the whole org.slf4j group; Spring Boot's Logback binding must win.
        exclude(group = "org.slf4j")
        // Exclude log4j→SLF4J bridges that Neo4j may pull in transitively.
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
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
    // Version 5.26.x is compatible with Spring Boot's managed Netty 4.1.x — no version override needed.
    testImplementation(libs.neo4j.harness) {
        exclude(group = "org.slf4j")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
    }
    // Note: no Netty version override needed — neo4j 5.26.x is compatible with
    // Spring Boot's managed Netty 4.1.x.

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
        // Spring Cloud BOM (Northfields / 2025.0.x) — manages all spring-cloud-* dependency versions.
        // Northfields is the release train compatible with Spring Boot 3.5.x.
        // Must come after spring-ai-bom so that Spring Cloud wins for shared
        // artifacts (e.g. spring-cloud-commons) over any Spring AI transitive pulls.
        mavenBom(
            libs.spring.cloud.bom
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
    // Disable JVM fast-throw optimisation so MockK can read the ClassCastException
    // message and auto-hint the correct type for mocked calls.
    jvmArgs("-XX:-OmitStackTraceInFastThrow")
}

// Neo4j ships org.neo4j:neo4j-slf4j-provider which registers SLF4JLogBridge as an SLF4J
// service provider. When it wins the ServiceLoader race over Logback, Spring Boot's
// LogbackLoggingSystem fails with "LoggerFactory is not a Logback LoggerContext".
// This global exclusion guarantees it never appears on any classpath configuration.
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
        args.set(listOf("--spring.profiles.active=openapi,embedded-neo4j", "--server.port=$agentosPort"))
    }
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}
