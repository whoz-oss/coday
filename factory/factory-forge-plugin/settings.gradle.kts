rootProject.name = "factory-forge-plugin"

// TYPESAFE_PROJECT_ACCESSORS is intentionally absent: the composite root is named
// "factory", which would generate a getFactory() accessor colliding with Gradle's
// internal RootProjectAccessor base class (Gradle 9.4+). None of the factory
// modules reference `projects.*`, so the preview is unused.

// The Forge plugin compiles exclusively against the Factory SDK (`factory-sdk`),
// never against `factory-service` internal classes. At runtime the plugin is
// loaded by PF4J into the service JVM and reuses the host classloader for
// Spring, Jackson, PF4J and the SDK (the host uses the APD class-loading strategy).
includeBuild("../factory-sdk") {
    dependencySubstitution {
        substitute(module("whoz-oss.factory:factory-sdk")).using(project(":"))
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
