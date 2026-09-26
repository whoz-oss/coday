rootProject.name = "factory-service"

// Enable version catalog (libs.versions.toml)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Independent build: this service is not a composite build of agentos.
// Its version catalog lives locally in factory-service/gradle/libs.versions.toml.

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}
