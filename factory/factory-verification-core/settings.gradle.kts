rootProject.name = "factory-verification-core"

// Enable version catalog (libs.versions.toml)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// This module is a PURE Kotlin/JVM library: it must compile and run even when
// Spring, PostgreSQL, AgentOS and node_modules are unavailable. It reuses the
// factory-service version catalog only so the JVM toolchain, Kotlin and Jackson
// versions stay aligned — the catalog is a build-time file, not a runtime
// dependency on the measured product.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../factory-service/gradle/libs.versions.toml"))
        }
    }
    repositories {
        mavenCentral()
        mavenLocal()
    }
}
