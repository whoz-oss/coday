# Implementation Plan - Spring Test Context Harmonization for Factory Service

## Context & Problem Statement
When running the full test suite for `factory-service` (`cd factory-service && ./gradlew test`), Spring Test creates separate `ApplicationContext` instances for integration test classes with differing `@SpringBootTest` configurations (e.g. `MOCK` vs `RANDOM_PORT`, default vs explicit `@SpringBootTest`, or classes with differing inline `@TestPropertySource` / `@SpringBootTest(properties = [...])`). When Spring Test's context cache limit is reached or when contexts close during or after execution, active `DataSource` / `HikariPool` instances are closed mid-suite or evicted. Subsequent tests sharing or expecting an active context can fail with database connection errors (`SQLTransientConnectionException / Connection refused`).

To eliminate context eviction, ensure 100% context reuse, and guarantee zero test failures across the entire suite, all Spring Boot integration test classes in `factory-service` must share a **single, unified `@SpringBootTest` configuration**.

---

## Analysis of Existing Spring Test Classes in `factory-service`

There are 10 Spring Boot integration test classes (subclasses of `PostgresContainerSpec`) across `factory-service`:

1. `FactoryServiceApplicationIntegrationTest`: `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`
2. `PostgresArtifactStoreIntegrationTest`: `@SpringBootTest` (defaults to `MOCK`)
3. `ArtifactAdminControllerIntegrationTest`: `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["factory.security.fake-idp-secret=artifact-admin-test-secret"])`
4. `OracleExecutionRepositoryTest`: `@SpringBootTest` + `@Import(OracleArtifactPublisherTestConfiguration::class)` + `@DynamicPropertySource` for oracle definitions
5. `OracleControllerAuthenticationTest`: `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["factory.security.allow-loopback-dev=false"])`
6. `OracleControllerIntegrationTest`: `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` + `@DynamicPropertySource` for oracle definitions
7. `WorkUnitLeaseConcurrencyTest`: `@SpringBootTest` (inherits `DomainIntegrationTest`)
8. `WorkUnitAndWorkerIntegrationTest`: `@SpringBootTest` (inherits `DomainIntegrationTest`)
9. `WorkEnvironmentLifecycleServiceTest`: `@SpringBootTest` (inherits `DomainIntegrationTest`)
10. `EnvironmentLifecycleControllerTest`: `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` (inherits `DomainIntegrationTest`)
11. `LeaseFencingAndExpiryTest`: `@SpringBootTest` (inherits `DomainIntegrationTest`)

### Root Causes of Context Cache Keys Diverging
1. **Divergent `webEnvironment`**: Some classes set `RANDOM_PORT` while others default to `MOCK`.
2. **Divergent `@SpringBootTest(properties = [...])`**:
   - `ArtifactAdminControllerIntegrationTest` sets `factory.security.fake-idp-secret=artifact-admin-test-secret`. However, setting a fake IDP secret globally or using JWT headers in tests works uniformly without custom per-class properties.
   - `OracleControllerAuthenticationTest` sets `factory.security.allow-loopback-dev=false`. Overriding this property dynamically or setting authorization headers explicitly in the test method/client allows using the shared context.
3. **Divergent `@Import` / `@TestConfiguration`**: `OracleExecutionRepositoryTest` imports a custom bean `OracleArtifactPublisherTestConfiguration`. If defined globally or provided as a primary `@Bean` / `@TestConfiguration` in the base class / standard test config, it avoids context key divergence.
4. **Divergent `@DynamicPropertySource`**:
   - `OracleExecutionRepositoryTest` and `OracleControllerIntegrationTest` both create temp directories for oracle definitions and register `factory.oracle.definitions-root`. If the base class initializes a shared temp directory once (or registers dynamic properties centrally), all classes share the context.

---

## Proposed Changes

### 1. Unified Base Integration Class: `PostgresContainerSpec` / `DomainIntegrationTest`

We will unify all Spring integration test configurations in `PostgresContainerSpec` or `DomainIntegrationTest`.

#### Step 1.1: Standardize `PostgresContainerSpec`
In `factory-service/src/test/kotlin/io/whozoss/factory/PostgresContainerSpec.kt`:
- Add `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` to `PostgresContainerSpec` (or `DomainIntegrationTest`).
- Standardize common properties in `@SpringBootTest` if necessary, or ensure test properties (e.g. `factory.oracle.definitions-root`, `factory.security.fake-idp-secret`) are registered dynamically in `registerDatasourceProperties` / companion object initialization so that **every subclass inherits the exact same context key**.
- Provide a static oracle definitions temp directory initialized once in `PostgresContainerSpec.companion object` so `factory.oracle.definitions-root` is set globally for all tests via `@DynamicPropertySource`.
- Provide default fallback test configurations (e.g., test `OracleArtifactPublisher` bean) so `@Import(OracleArtifactPublisherTestConfiguration::class)` is not needed per test class, or make `OracleArtifactPublisherTestConfiguration` part of standard test bean configuration.

#### Step 1.2: Standardize `DomainIntegrationTest`
In `factory-service/src/test/kotlin/io/whozoss/factory/DomainIntegrationTest.kt`:
- `DomainIntegrationTest` inherits from `PostgresContainerSpec`.
- Remove `@SpringBootTest` from subclasses extending `DomainIntegrationTest` so they rely on the parent's annotation, or ensure every subclass has `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` with identical parameters.

---

### 2. Refactoring Test Classes to Align Context Keys

#### 2.1 `FactoryServiceApplicationIntegrationTest.kt`
- Inherits from `PostgresContainerSpec`. Uses canonical `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` configuration.

#### 2.2 `PostgresArtifactStoreIntegrationTest.kt`
- Update annotation from `@SpringBootTest` to inherit `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` from `PostgresContainerSpec`.

#### 2.3 `ArtifactAdminControllerIntegrationTest.kt`
- Remove class-level `properties = ["factory.security.fake-idp-secret=artifact-admin-test-secret"]`.
- Set `factory.security.fake-idp-secret` globally in `PostgresContainerSpec` `@DynamicPropertySource` (e.g. `registry.add("factory.security.fake-idp-secret") { "artifact-admin-test-secret" }`), so all integration tests share the secret without context splitting.

#### 2.4 `OracleExecutionRepositoryTest.kt` & `OracleControllerIntegrationTest.kt`
- Move `oracleDefinitionsRoot` temp directory creation and `@DynamicPropertySource` property `factory.oracle.definitions-root` into `PostgresContainerSpec` companion object so it's registered globally for all Spring integration tests.
- Move `OracleArtifactPublisherTestConfiguration` to a shared location or include a fallback `@TestConfiguration` / mock bean so `OracleExecutionRepositoryTest` does not split the context with `@Import`.

#### 2.5 `OracleControllerAuthenticationTest.kt`
- Remove class-level `properties = ["factory.security.allow-loopback-dev=false"]`.
- To test unauthenticated access without disabling loopback-dev globally (which would break other HTTP tests relying on loopback-dev), test with non-loopback headers or use `TestRestTemplate` with headers that simulate non-local remote IP (e.g. `X-Forwarded-For: 198.51.100.1`) OR adjust the test request so loopback-dev does not auto-login as admin when testing anonymous endpoints requiring specific auth tokens.

#### 2.6 `WorkUnitLeaseConcurrencyTest.kt`, `WorkUnitAndWorkerIntegrationTest.kt`, `WorkEnvironmentLifecycleServiceTest.kt`, `EnvironmentLifecycleControllerTest.kt`, `LeaseFencingAndExpiryTest.kt`
- All extend `DomainIntegrationTest` (which extends `PostgresContainerSpec`).
- Standardize annotation: eliminate per-class `@SpringBootTest` overrides or ensure all use the identical parent annotation with `webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT`.

---

## Detailed Step-by-Step Implementation

### Step 1: Update `PostgresContainerSpec.kt`
- Add `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` to `PostgresContainerSpec`.
- In `companion object`:
  - Create static shared temp directory `oracleDefinitionsRoot` with `smoke@1.0.0.json`.
  - In `registerDatasourceProperties(registry: DynamicPropertyRegistry)`:
    - Add `registry.add("factory.oracle.definitions-root") { oracleDefinitionsRoot.toAbsolutePath().toString() }`
    - Add `registry.add("factory.security.fake-idp-secret") { "artifact-admin-test-secret" }`

### Step 2: Update `OracleArtifactPublisher` Test Bean
- Ensure a default `OracleArtifactPublisher` bean is present in the main test context (e.g. via a standard `@TestConfiguration` or `@Bean` in a shared test configuration class, or conditional bean in test scope) so `OracleExecutionRepositoryTest` does not need custom `@Import`.

### Step 3: Refactor Test Classes
1. **`ArtifactAdminControllerIntegrationTest.kt`**: Remove `properties` parameter from `@SpringBootTest`.
2. **`OracleControllerAuthenticationTest.kt`**: Remove `properties = ["factory.security.allow-loopback-dev=false"]` from `@SpringBootTest`. Update test method to pass header `X-Forwarded-For: 203.0.113.195` (or non-loopback IP) so `TrustContextExtractor` treats request as non-loopback and tests 401 response without modifying Spring context properties.
3. **`OracleExecutionRepositoryTest.kt`**: Remove custom `@Import` and local `@DynamicPropertySource`.
4. **`OracleControllerIntegrationTest.kt`**: Remove local `@DynamicPropertySource`.
5. **`DomainIntegrationTest.kt`** and all domain test subclasses (`WorkUnitLeaseConcurrencyTest`, `WorkUnitAndWorkerIntegrationTest`, `WorkEnvironmentLifecycleServiceTest`, `EnvironmentLifecycleControllerTest`, `LeaseFencingAndExpiryTest`): Remove redundant `@SpringBootTest` annotations from individual classes, inheriting `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` from `PostgresContainerSpec`.
6. **`PostgresArtifactStoreIntegrationTest.kt`**: Remove redundant `@SpringBootTest` annotation, inheriting from `PostgresContainerSpec`.
7. **`FactoryServiceApplicationIntegrationTest.kt`**: Inherit `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` from `PostgresContainerSpec`.

### Step 4: Verification & Context Reuse Check
- Run `cd factory-service && ./gradlew test --rerun-tasks`.
- Inspect Gradle test logs / Spring startup logs to confirm Spring `ApplicationContext` initializes **exactly 1 time** for all integration tests.
- Confirm 0 test failures, 0 `HikariPool` eviction errors, and 0 `Connection refused` exceptions.

---

## Verification Plan

### Command
```bash
cd factory-service && ./gradlew test --rerun-tasks
```

### Expected Output
- `BUILD SUCCESSFUL`
- All tests pass (0 failures).
- Single Spring Boot application context startup log across all integration test executions.
