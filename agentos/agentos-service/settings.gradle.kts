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
        // springdoc 3.2.1-SNAPSHOT (swagger-core 3, Jackson 3): restricted to springdoc snapshots.
        // Remove once springdoc 3.2.1 is released on Maven Central.
        maven {
            name = "CentralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent {
                snapshotsOnly()
                includeGroup("org.springdoc")
            }
        }
    }
}

// Include SDK as composite build with dependency substitution
includeBuild("../agentos-sdk") {
    dependencySubstitution {
        substitute(module("whoz-oss.agentos:agentos-sdk")).using(project(":"))
    }
}

// Shared hardened Git execution, substituted like the SDK
includeBuild("../agentos-git") {
    dependencySubstitution {
        substitute(module("whoz-oss.agentos:agentos-git")).using(project(":"))
    }
}
