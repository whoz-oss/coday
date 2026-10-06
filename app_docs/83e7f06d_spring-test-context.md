# Spring integration test context harmonization

## What changed

`factory-service` integration tests now inherit one canonical Spring Boot test setup from `PostgresContainerSpec`:

- `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` is declared on the shared base fixture.
- The test application is explicitly composed from `FactoryServiceApplication` and `SharedIntegrationTestConfiguration`.
- PostgreSQL/Testcontainers datasource and Flyway properties remain registered centrally, alongside shared oracle definitions, fake IdP secret, and forwarded-header handling.
- `SharedIntegrationTestConfiguration` supplies the test `OracleArtifactPublisher` bean previously imported only by the oracle repository test.

The per-class `@SpringBootTest` declarations were removed from the affected tests, eliminating differences such as the default `MOCK` web environment, inline properties, and test-only imports that could produce distinct Spring context cache keys. The oracle controller and repository tests now use the shared definitions directory. The authentication test keeps the common context and simulates a non-loopback caller with `X-Forwarded-For` instead of disabling loopback development access via a class-specific property.

This makes the integration suite eligible to reuse a single cached `ApplicationContext`, keeping its datasource/Hikari pool from being closed as contexts are evicted between otherwise related tests.

## Files carrying the change

- `factory-service/src/test/kotlin/io/whozoss/factory/PostgresContainerSpec.kt` contains the canonical `@SpringBootTest`, shared dynamic properties, shared oracle fixture, and `SharedIntegrationTestConfiguration`.
- `factory-service/src/test/kotlin/io/whozoss/factory/FactoryServiceApplicationIntegrationTest.kt` now inherits the base setup.
- `factory-service/src/test/kotlin/io/whozoss/factory/artifact/infrastructure/persistence/PostgresArtifactStoreIntegrationTest.kt` and `factory-service/src/test/kotlin/io/whozoss/factory/artifact/web/ArtifactAdminControllerIntegrationTest.kt` no longer define divergent Spring test annotations.
- `factory-service/src/test/kotlin/io/whozoss/factory/environment/EnvironmentLifecycleControllerTest.kt` and `WorkEnvironmentLifecycleServiceTest.kt`, `factory-service/src/test/kotlin/io/whozoss/factory/lease/LeaseFencingAndExpiryTest.kt`, and `factory-service/src/test/kotlin/io/whozoss/factory/workunit/WorkUnitAndWorkerIntegrationTest.kt` and `WorkUnitLeaseConcurrencyTest.kt` inherit the shared configuration through their existing integration-test hierarchy.
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleControllerAuthenticationTest.kt` uses the forwarded non-loopback request path; `OracleControllerIntegrationTest.kt` and `OracleExecutionRepositoryTest.kt` consume the shared oracle fixture and publisher bean rather than defining local context customizations.
- `specs/83e7f06d_spring_test_context_harmonization.md` records the context-key analysis and verification plan.

## Verification

Run the complete integration suite from `factory-service`:

```bash
cd factory-service
./gradlew clean test
```

A successful run should complete with zero failures. To check the intended cache behavior, inspect the test logs for Spring Boot context startup count and confirm that the suite does not report Hikari pool eviction, closed-datasource, or connection-refused failures. The implementation plan also describes `./gradlew test --rerun-tasks` as an equivalent fresh-suite check.
