rootProject.name = "factory-forge-plugin"

// Enable version catalog (libs.versions.toml)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// The Forge plugin compiles against the host Factory Service (and, transitively,
// the Factory SDK it exposes via `api`). Both are `compileOnly`: at runtime the
// plugin is loaded by PF4J into the service JVM and reuses the host
// classloader for Spring, Jackson, PF4J, the SDK and the core error/persistence
// types (the host uses the APD class-loading strategy).
includeBuild("../factory-service") {
    dependencySubstitution {
        substitute(module("io.whozoss.factory:factory-service")).using(project(":"))
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
    repositories {
        mavenCentral()
        mavenLocal()
    }
}
