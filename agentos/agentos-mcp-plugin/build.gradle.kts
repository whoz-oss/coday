plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt) // Required for PF4J annotation processing
}

group = "whoz-oss.agentos"
version = libs.versions.agentosService.get()
description = "AgentOS MCP plugin - connects to local MCP servers and exposes their tools"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.kotlinJvmTarget.get())
}

dependencies {
    implementation(libs.klogger)

    // AgentOS SDK - Contains plugin interfaces
    compileOnly("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")

    // PF4J - Required for @Extension annotation processing
    compileOnly(libs.pf4j)
    kapt(libs.pf4j)

    // Jackson for JSON handling
    compileOnly(libs.bundles.jackson)

    // MCP Java SDK - for connecting to local MCP servers via stdio
    implementation(libs.mcp.sdk)

    // Testing
    testImplementation("whoz-oss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")
    testImplementation(libs.bundles.jackson)
    testImplementation(libs.bundles.testing.common)
    testImplementation(libs.pf4j)
    testRuntimeOnly(libs.junit.platform.launcher)
    kaptTest(libs.pf4j)
}

// Configure kapt for PF4J extension processing
kapt {
    arguments {
        arg("pf4j.storageClassName", "org.pf4j.processor.LegacyExtensionStorage")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get()))
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Plugin-Id" to "agentos-mcp-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "whoz-oss",
            "Plugin-Class" to "io.whozoss.agentos.plugins.mcp.McpPlugin",
        )
    }
    // Bundle MCP SDK and its transitive deps (reactor, snakeyaml, etc.) into the plugin JAR
    // so the plugin classloader can resolve them independently of the service classpath.
    // Jackson is declared compileOnly and therefore NOT in runtimeClasspath -- it is provided
    // by the service classloader at runtime, avoiding the LinkageError.
    from(configurations.runtimeClasspath.map { fc -> fc.filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("kotlin/**", "kotlinx/**")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}
