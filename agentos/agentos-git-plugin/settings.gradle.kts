rootProject.name = "agentos-git-plugin"

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

// Include the SDK and the shared Git core as composite builds
includeBuild("../agentos-sdk")
includeBuild("../agentos-git")
