rootProject.name = "factory-service"

// Enable version catalog (libs.versions.toml)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// The shared factory version catalog lives at factory/gradle/libs.versions.toml.
// All factory modules (sdk, service, verification-core, forge-plugin) reference it
// from their respective settings.gradle.kts using a relative path.
// The Factory SDK is consumed as a composite build so both modules are always
// compiled from source together.
includeBuild("../factory-sdk") {
    dependencySubstitution {
        substitute(module("io.whozoss.factory:factory-sdk")).using(project(":"))
    }
}

// The pure, framework-free verification library (target-repo manifest + oracle
// execution). Consumed as a composite build so it is always compiled from source
// together with the service, and never requires a prior mavenLocal publish.
includeBuild("../factory-verification-core") {
    dependencySubstitution {
        substitute(module("io.whozoss.factory:factory-verification-core")).using(project(":"))
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
