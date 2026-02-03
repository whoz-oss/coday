plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("kapt")
}

group = "whoz-oss.agentos"
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

// Configure kapt to generate extensions.idx
kapt {
    correctErrorTypes = true
    includeCompileClasspath = false
    arguments {
        arg("pf4j.logLevel", "DEBUG")
    }
}

// Ensure kapt runs before jar task
tasks.named("jar") {
    dependsOn("kaptKotlin")
}

// Create plugin JAR
tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "code-based-agents",
            "Plugin-Version" to version,
            "Plugin-Provider" to "AgentOS",
            "Plugin-Class" to "whoz-oss.agentos.plugins.codebased.CodeBasedPlugin",
        )
    }

    // Include all dependencies in the JAR
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
