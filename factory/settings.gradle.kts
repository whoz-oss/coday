rootProject.name = "factory"

// Type-safe project accessors are unused in this composite root.
// Do not enable them: the root name "factory" generates a getFactory()
// accessor that collides with Gradle's internal accessor base class.

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
// Gradle automatically imports this root's gradle/libs.versions.toml as `libs`.
// Do not add an explicit from(...) import for the same root catalog.
// Included builds have independent catalogs configured in their own settings.

includeBuild("factory-sdk")
includeBuild("factory-verification-core")
includeBuild("factory-service")
includeBuild("factory-forge-plugin")
