plugins {
    id("dev.nx.gradle.project-graph") version("0.1.10")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "io.biznet.agentos"
version = libs.versions.agentosService.get()
description = "AgentOS Service - Backend orchestration service"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // AgentOS SDK - Contains plugin interfaces
    // When using composite builds, reference by coordinates
    api("io.biznet.agentos:agentos-sdk:${libs.versions.agentosSdk.get()}")
    
    // PF4J Spring Integration (for service only, not SDK)
    implementation(libs.pf4j.spring) {
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }
    
    // Spring Boot dependencies
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    
    // Jackson for JSON processing
    implementation(libs.jackson.module.kotlin)
    
    // Kotlin
    implementation(libs.bundles.kotlin.common)
    
    // Kotlin Coroutines
    implementation(libs.bundles.kotlin.coroutines)
    
    // Spring AI
    implementation(libs.bundles.spring.ai)
    
    // Test dependencies
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.bundles.testing.spring)
    testRuntimeOnly(libs.junit.platform.launcher)
    
    // DevTools causes classloader issues with PF4J plugins - DISABLED
    // developmentOnly("org.springframework.boot:spring-boot-devtools")
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.ai.bom.get().toString())
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Configure the bootJar task
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("agentos-service.jar")
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}