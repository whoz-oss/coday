plugins {
    id("dev.nx.gradle.project-graph") version("0.1.10")
    kotlin("jvm")
}

group = "com.example.agentos"
version = "1.0.0"
description = "Simple AgentOS Plugin Example"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // AgentOS SDK - provides plugin interfaces
    compileOnly(project(":agentos-sdk"))
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    
    // Test
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Configure JAR for plugin deployment
tasks.jar {
    archiveFileName.set("simple-plugin.jar")
    
    manifest {
        attributes(
            "Plugin-Id" to "simple-plugin",
            "Plugin-Version" to project.version,
            "Plugin-Provider" to "Example Inc.",
            "Plugin-Class" to "com.example.agentos.plugin.SimplePlugin"
        )
    }
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }
}