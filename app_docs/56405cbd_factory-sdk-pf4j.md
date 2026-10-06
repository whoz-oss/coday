# Factory SDK and PF4J plugin host

## What changed

W7.2 adds a standalone `factory-sdk` Gradle/Nx library and connects it to `factory-service` through a Gradle composite build. The SDK is deliberately Spring-free: it exposes PF4J extension points and framework-neutral request/response contracts so plugin implementations do not need Spring MVC types.

The SDK provides:

- `FactoryRoute`, `FactoryRouteRequest`, `FactoryRouteResponse`, and `FactoryRouteHandler` for HTTP route descriptors and handlers.
- `FactoryRouteContributor`, whose default `getRoutes()` returns an empty list.
- `FactoryWorkflowProjectionPublisher`, with `PUBLISHED`, `SKIPPED`, and `UNSUPPORTED` outcomes and an `UNSUPPORTED` default.
- `FactoryRunLaunchContributor`, with a safe no-op default.
- `FactorySseSink`, a minimal `send(event, data)` / `close()` contract.

`factory-sdk/build.gradle.kts` publishes `io.whozoss.factory:factory-sdk:0.0.1-SNAPSHOT` to `mavenLocal()` and depends on PF4J, Jackson annotations, and Kotlin standard library only. `factory-sdk/settings.gradle.kts` reuses the service version catalog, while `factory-sdk/project.json` exposes Nx `build` and `test` targets with JVM library tags.

## Service integration

`factory-service/settings.gradle.kts` includes `../factory-sdk` as a composite build, and `factory-service/build.gradle.kts` consumes the SDK coordinate. The service version catalog adds PF4J `3.13.0`, PF4J Spring `0.10.0`, and Jackson annotations entries. Its Nx build and test targets depend on the corresponding `factory-sdk` targets. The service ignores the runtime `plugins/` directory.

`FactoryPluginConfigProperties` binds `factory.plugins.dir`, defaulting to `plugins/`. `PluginConfiguration` creates that directory when it does not exist and exposes a PF4J `PluginManager`. The manager uses custom APD (application-first) JAR and default plugin loaders, and a null-safe Spring extension factory intended to avoid classloader conflicts and extension instantiation failures when an extension is not owned by an initialized plugin.

`FactoryPluginRouteConfig` reads `FactoryRouteContributor` extensions from PF4J and mounts their descriptors as a Spring MVC `RouterFunction` bean. It adapts methods, paths, request data, response status/content type/headers/body, and handlers between the SDK contracts and Spring MVC. With no contributed routes it installs a never-matching empty router, leaving existing controller routes unaffected.

No Node control-plane/instrument files or Forge/BMAD implementation files are included in the change.

## Verification

`FactoryPluginSystemIntegrationTest`, extending `DomainIntegrationTest`, covers the empty-plugin scenario: PF4J starts with no plugins or route contributors, the plugin router bean exists, `/api/factory/workstreams?namespaceId=test` returns `200`, and `/api/forge/runs` remains mapped and returns the expected `400`/`MISSING_NAMESPACE_ID` validation response when its namespace parameter is absent.

The diff adds Gradle/Nx test entry points for both modules. The captured change does not include command output from `./gradlew clean test`, so use these commands to verify locally:

```bash
cd factory-sdk && ./gradlew clean test
cd ../factory-service && ./gradlew clean test
```

From the repository root, the equivalent Nx targets are `pnpm nx test factory-sdk` and `pnpm nx test factory-service`.

## Files carrying the change

- `factory-sdk/build.gradle.kts`, `factory-sdk/settings.gradle.kts`, `factory-sdk/project.json`, and Gradle wrapper/configuration files: standalone SDK build and publishing.
- `factory-sdk/src/main/kotlin/io/whozoss/factory/sdk/spi/FactoryRoute.kt`
- `factory-sdk/src/main/kotlin/io/whozoss/factory/sdk/spi/FactoryRouteContributor.kt`
- `factory-sdk/src/main/kotlin/io/whozoss/factory/sdk/spi/FactoryWorkflowProjectionPublisher.kt`
- `factory-sdk/src/main/kotlin/io/whozoss/factory/sdk/spi/FactoryRunLaunchContributor.kt`
- `factory-sdk/src/main/kotlin/io/whozoss/factory/sdk/spi/FactorySseSink.kt`
- `factory-service/build.gradle.kts`, `factory-service/settings.gradle.kts`, `factory-service/gradle/libs.versions.toml`, and `factory-service/project.json`: dependency and build integration.
- `factory-service/src/main/kotlin/io/whozoss/factory/config/FactoryPluginConfigProperties.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/PluginConfiguration.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/FactoryPluginRouteConfig.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/plugin/FactoryPluginSystemIntegrationTest.kt`
- `specs/56405cbd_factory_sdk_pf4j_infrastructure.md`: implementation plan recorded with the change.
