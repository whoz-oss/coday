# Plan: Fix PostgresContainerSpec testcontainers singleton container lifecycle startup

## Executive Summary
Fix `PostgresContainerSpec` singleton container lifecycle so that PostgreSQL Testcontainers start explicitly once per JVM initialization rather than relying on JUnit `@Testcontainers` / `@Container` annotations on companion object fields of abstract classes. This eliminates `SQLTransientConnectionException` / `PSQLException: Connection refused` issues across integration tests and ensures reliable test execution.

## Proposed Changes

### `factory-service`

#### [PostgresContainerSpec.kt](file:///work/app/factory-service/src/test/kotlin/io/whozoss/factory/PostgresContainerSpec.kt)

1. Remove `@Container` annotation and `import org.testcontainers.junit.jupiter.Container`.
2. Explicitly start the `FactoryPostgresContainer` instance upon initialisation in companion object:
   ```kotlin
   companion object {
       protected val postgres: FactoryPostgresContainer =
           FactoryPostgresContainer()
               .withDatabaseName("factory_test")
               .withUsername("factory")
               .withPassword("factory")
               .apply { start() }
   ...
   ```
   (Using `.apply { start() }` or `.also { it.start() }` ensures `start()` is called immediately when the companion object is initialized before Spring's `@DynamicPropertySource` accesses `postgres.jdbcUrl`).
3. Keep `@DynamicPropertySource @JvmStatic fun registerDatasourceProperties(...)` as is.
4. Update KDoc comments to document that `postgres` container is started explicitly when the companion object is initialized, and Testcontainers Ryuk handles cleanup on JVM termination.

## Verification Plan

### Automated Tests
Run the factory-service test suite with task re-execution to ensure all integration tests pass end-to-end:
- Command: `cd factory-service && ./gradlew test --rerun-tasks`
- Command: `pnpm nx test factory-service`
- Run global quality checks:
  - `pnpm nx affected -t test --base="$(cat /work/data/baseline 2>/dev/null || echo 'HEAD~1')" --parallel=2` or `pnpm test`

### Manual Verification
- Verify `PostgresContainerSpec.kt` contains no `@Container` annotations.
- Verify companion object initializes and starts `postgres` container cleanly before properties are registered.
