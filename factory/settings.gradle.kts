rootProject.name = "factory"

// Enable version catalog
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// ── Composite build: all factory modules are included from source ──────────────
// The dependency graph is:
//   factory-sdk               (no factory deps)
//   factory-verification-core (no factory deps, framework-free invariant)
//   factory-service           (depends on factory-sdk, factory-verification-core)
//   factory-forge-plugin      (depends on factory-service → transitively factory-sdk)
//
// Each module keeps its own settings.gradle.kts for independent builds;
// this root composite wires them together so `./gradlew :<module>:<task>`
// works from the factory/ directory, mirroring the agentos/ layout.
//
// NO dependencyResolutionManagement block here: each included build already
// declares its own `libs` catalog pointing at ../gradle/libs.versions.toml.
// Declaring it again at the root would register `libs` twice and cause a
// "version catalog ... has already been declared" error. Gradle deduplicates
// included builds by canonical path; catalog declarations are not deduplicated.

includeBuild("factory-sdk")
includeBuild("factory-verification-core")
includeBuild("factory-service")
includeBuild("factory-forge-plugin")
