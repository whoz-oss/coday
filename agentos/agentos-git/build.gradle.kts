plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.10")
    alias(libs.plugins.kotlin.jvm)
}

group = "whoz-oss.agentos"
version = libs.versions.agentosService.get()
description = "AgentOS Git core - hardened server-side Git execution shared by the service and the GIT plugin"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.kotlinJvmTarget.get())
}

dependencies {
    implementation(libs.klogger)

    // Annotation only: the service binds GitExecutionProperties from `agentos.git`, the GIT plugin
    // builds its own instance. Never bundled, like the annotation-only dependencies of the SDK.
    compileOnly("org.springframework.boot:spring-boot:${libs.versions.springBoot.get()}")

    testImplementation(libs.bundles.testing.common)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get()))
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
