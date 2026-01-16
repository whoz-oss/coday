import org.gradle.kotlin.dsl.dependencies

plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    alias(libs.plugins.kotlin.jvm)
}

group = "io.whozoss.agentos.plugins"
version = libs.versions.agentosService.get()
description = "AgentOS filesystem plugins"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // AgentOS SDK - Contains plugin interfaces
    // When using composite builds, reference by coordinates
    compileOnly("io.whozoss.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")

    compileOnly(libs.bundles.jackson)
}


kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
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
