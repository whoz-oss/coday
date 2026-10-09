rootProject.name = "factory-verification-core"

// TYPESAFE_PROJECT_ACCESSORS is intentionally absent: the composite root is named
// "factory", which would generate a getFactory() accessor colliding with Gradle's
// internal RootProjectAccessor base class (Gradle 9.4+). None of the factory
// modules reference `projects.*`, so the preview is unused.

// This module is a PURE Kotlin/JVM library: it must compile and run even when
// Spring, PostgreSQL, AgentOS and node_modules are unavailable. It reuses the
// shared factory version catalog (factory/gradle/libs.versions.toml) so the JVM
// toolchain, Kotlin and Jackson versions stay aligned — the catalog is a build-time
// file, not a runtime dependency on the measured product.
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
