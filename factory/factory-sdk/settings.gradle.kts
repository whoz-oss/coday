rootProject.name = "factory-sdk"

// Enable version catalog (libs.versions.toml)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
