rootProject.name = "factory-service"

// Enable version catalog (libs.versions.toml)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Independent from agentos: its version catalog lives locally in
// factory-service/gradle/libs.versions.toml. The Factory SDK is consumed as a
// composite build so both modules are always compiled from source together.
includeBuild("../factory-sdk") {
    dependencySubstitution {
        substitute(module("io.whozoss.factory:factory-sdk")).using(project(":"))
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}
