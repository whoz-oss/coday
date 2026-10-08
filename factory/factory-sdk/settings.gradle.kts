rootProject.name = "factory-sdk"

// TYPESAFE_PROJECT_ACCESSORS is intentionally absent: the composite root is named
// "factory", which would generate a getFactory() accessor colliding with Gradle's
// internal RootProjectAccessor base class (Gradle 9.4+). None of the factory
// modules reference `projects.*`, so the preview is unused.

// The SDK reuses the shared factory version catalog (factory/gradle/libs.versions.toml)
// so the JVM toolchain, Kotlin and dependency versions stay aligned across all factory modules.
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
