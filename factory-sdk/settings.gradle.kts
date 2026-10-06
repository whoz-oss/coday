rootProject.name = "factory-sdk"

// Enable version catalog (libs.versions.toml)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// The SDK reuses factory-service's version catalog so the JVM toolchain, Kotlin
// and dependency versions stay aligned with the service that hosts the plugins.
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
