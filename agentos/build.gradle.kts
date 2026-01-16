// Root build file for AgentOS composite build
// Individual modules (agentos-sdk, agentos-service) are now composite builds
// with their own settings.gradle.kts and build configurations
plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

group = "whoz-oss.agentos"
version = "0.0.1-SNAPSHOT"
    
tasks.named("clean") {
    dependsOn(gradle.includedBuilds.map { it.task(":clean") })
}

tasks.named("build") {
    dependsOn(gradle.includedBuilds.map { it.task(":build") })
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}

