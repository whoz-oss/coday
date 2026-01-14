plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("kapt")
    kotlin("plugin.serialization") version "1.9.25"
}

group = "io.biznet.agentos.plugins"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // PF4J dependency
    compileOnly("org.pf4j:pf4j:3.12.0")

    // PF4J annotation processor for generating extensions.idx
    kapt("org.pf4j:pf4j:3.12.0")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // YAML parsing
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")

    // Reference to main project (compile only - will be provided at runtime)
    compileOnly(project(":"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// Create plugin JAR
tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "filesystem-agents",
            "Plugin-Version" to version,
            "Plugin-Provider" to "AgentOS",
            "Plugin-Class" to "io.biznet.agentos.plugins.filesystem.FilesystemPlugin",
        )
    }

    // Include all dependencies in the JAR
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
