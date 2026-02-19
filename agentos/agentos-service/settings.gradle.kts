rootProject.name = "agentos-service"

// Enable version catalog
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Configure dependency resolution
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

// Include SDK as composite build with dependency substitution
includeBuild("../agentos-sdk") {
    dependencySubstitution {
        substitute(module("whoz-oss.agentos:agentos-sdk")).using(project(":"))
    }
}
