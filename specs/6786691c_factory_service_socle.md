# Plan W0: Factory Service Socle (Kotlin / Spring Boot 3.5.9)

## Executive Summary
This plan establishes the foundation ("socle") for the autonomous Kotlin Spring Boot service under `factory-service/` at the repository root.
It sets up an independent Gradle build (Java 25, Kotlin 2.3.20, Spring Boot 3.5.9, Spring Data JDBC, Flyway, PostgreSQL, Springdoc 2.8.9), copies exact migration scripts `V1`..`V7` from Node `factory/`, wires application configuration, establishes security & HTTP boundary filters (Correlation ID, TrustContext extraction, AdminGuard), exception hierarchy & error envelope matching the Node contract, ScopedRepository skeleton, OpenAPI spec generation check script, Nx project integration, and integration tests using PostgreSQL Testcontainers.
No domain aggregates or business logic controllers will be included.

---

## 1. Directory & Build Setup

### 1.1 `factory-service/settings.gradle.kts`
- Set `rootProject.name = "factory-service"`.

### 1.2 `factory-service/gradle/libs.versions.toml`
- Create version catalog copying relevant dependency versions from `agentos/gradle/libs.versions.toml`:
  - `java = "25"`
  - `kotlinJvmTarget = "25"`
  - `kotlin = "2.3.20"`
  - `springBoot = "3.5.9"`
  - `springDependencyManagement = "1.1.7"`
  - `springdoc = "2.8.9"`
  - `springdocPlugin = "1.9.0"`
  - `klogger = "2.0.4"`
  - `testcontainers = "1.20.4"`
  - `mockk = "1.13.5"`
  - `mockk-spring = "3.1.2"`
  - `jackson = "2.19.4"`
  - Define libraries:
    - `kotlin-stdlib`, `kotlin-reflect`, `kotlin-test-junit5`
    - `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-data-jdbc`, `spring-boot-starter-test`
    - `flyway-core`, `flyway-database-postgresql`
    - `postgresql` (driver)
    - `springdoc-openapi-starter` (`org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9`)
    - `klogger` (`io.github.microutils:kotlin-logging-jvm:2.0.4`)
    - `jackson-module-kotlin`
    - `testcontainers-junit` (`org.testcontainers:junit-jupiter`), `testcontainers-postgresql` (`org.testcontainers:postgresql`)
    - `mockk`, `mockk-spring` (`com.ninja-squad:springmockk`)
    - `junit-platform-launcher`
  - Define plugins:
    - `kotlin-jvm`, `kotlin-spring`, `spring-boot`, `spring-dependency-management`, `springdoc-openapi`

### 1.3 `factory-service/build.gradle.kts`
- Apply plugins:
  - `alias(libs.plugins.kotlin.jvm)`
  - `alias(libs.plugins.kotlin.spring)`
  - `alias(libs.plugins.spring.boot)`
  - `alias(libs.plugins.spring.dependency.management)`
  - `alias(libs.plugins.springdoc.openapi)`
- Configuration:
  - Group: `io.whozoss.factory`
  - Version: `0.0.1-SNAPSHOT`
  - Java toolchain languageVersion 25, targetCompatibility 25.
  - Kotlin `jvmTarget.set(JvmTarget.JVM_25)`.
  - Compiler args: `-Xjsr305=strict`, `-Xemit-jvm-type-annotations`.
- Dependencies:
  - `implementation(libs.spring.boot.starter.web)`
  - `implementation(libs.spring.boot.starter.actuator)`
  - `implementation(libs.spring.boot.starter.data.jdbc)`
  - `implementation(libs.flyway.core)`
  - `implementation(libs.flyway.database.postgresql)`
  - `runtimeOnly(libs.postgresql)`
  - `implementation(libs.springdoc.openapi.starter)`
  - `implementation(libs.klogger)`
  - `implementation(libs.jackson.module.kotlin)`
  - `implementation(libs.kotlin.stdlib)`
  - `implementation(libs.kotlin.reflect)`
  - `testImplementation(libs.spring.boot.starter.test)`
  - `testImplementation(libs.testcontainers.junit)`
  - `testImplementation(libs.testcontainers.postgresql)`
  - `testImplementation(libs.mockk)`
  - `testImplementation(libs.mockk.spring)`
  - `testImplementation(libs.kotlin.test.junit5)`
  - `testRuntimeOnly(libs.junit.platform.launcher)`
- Configure `tasks.withType<Test>` to `useJUnitPlatform()` and set system property `api.version = "1.44"`.
- Configure `bootJar` with `archiveFileName.set("factory-service.jar")`.
- Configure `openApi` task block for OpenAPI spec generation (output to `openapi/factory-openapi.yaml` or `src/main/resources/openapi/`, e.g. `outputDir.set(file("$rootDir/openapi"))`, `outputFileName.set("factory-openapi.yaml")`).

### 1.4 Gradle Wrapper
- Copy/create Gradle wrapper standard files under `factory-service/`:
  - `gradlew` (executable `chmod +x`)
  - `gradlew.bat`
  - `gradle/wrapper/gradle-wrapper.jar`
  - `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.12 or 8.10)

---

## 2. Nx Integration (`factory-service/project.json`)

Create `factory-service/project.json` matching repository conventions:
```json
{
  "name": "factory-service",
  "$schema": "../node_modules/nx/schemas/project-schema.json",
  "projectType": "application",
  "sourceRoot": "factory-service/src",
  "targets": {
    "build": {
      "executor": "nx:run-commands",
      "cache": true,
      "options": {
        "command": "./gradlew build",
        "cwd": "factory-service"
      }
    },
    "test": {
      "executor": "nx:run-commands",
      "cache": false,
      "options": {
        "command": "./gradlew test",
        "cwd": "factory-service"
      }
    },
    "bootRun": {
      "executor": "nx:run-commands",
      "cache": false,
      "options": {
        "command": "./gradlew bootRun",
        "cwd": "factory-service"
      }
    },
    "bootJar": {
      "executor": "nx:run-commands",
      "cache": true,
      "outputs": ["{projectRoot}/build/libs"],
      "options": {
        "command": "./gradlew bootJar",
        "cwd": "factory-service"
      }
    },
    "generate-openapi-spec": {
      "executor": "nx:run-commands",
      "cache": false,
      "outputs": ["{workspaceRoot}/factory-service/openapi/factory-openapi.yaml"],
      "options": {
        "command": "./gradlew generateOpenApiDocs --no-configuration-cache",
        "cwd": "factory-service"
      }
    },
    "check-openapi-spec": {
      "executor": "nx:run-commands",
      "cache": false,
      "options": {
        "command": "./check-openapi-spec.sh",
        "cwd": "factory-service"
      }
    }
  },
  "tags": ["type:app", "platform:jvm", "scope:service"]
}
```

---

## 3. Flyway Migrations Copy

Copy migration SQL files strictly without modifying contents or names from `factory/infra/migrations/` to `factory-service/src/main/resources/db/migration/`:
- `V1__init_workflow_pilot_schema.sql`
- `V2__tenant_and_membership.sql`
- `V3__workflow_core.sql`
- `V4__outbox_and_idempotency.sql`
- `V5__evidence_and_interaction.sql`
- `V6__artifacts_oracle_agentstep.sql`
- `V7__lease_protocol.sql`

---

## 4. Application Configuration (`factory-service/src/main/resources/application.yml`)

Create `application.yml` with:
```yaml
server:
  port: 8141

spring:
  application:
    name: factory-service
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/factory}
    username: ${SPRING_DATASOURCE_USERNAME:factory}
    password: ${SPRING_DATASOURCE_PASSWORD:factory}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: true
    baseline-on-migrate: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics

factory:
  bind:
    host: ${FACTORY_BIND_HOST:127.0.0.1}
    unsafe-allow-remote-bind: ${FACTORY_UNSAFE_ALLOW_REMOTE_BIND:false}
  tenant:
    organization-id: ${FACTORY_ORGANIZATION_ID:default}
    workstream-id: ${FACTORY_WORKSTREAM_ID:default}
  security:
    allow-loopback-dev: ${FACTORY_ALLOW_LOOPBACK_DEV:true}
    fake-idp-secret: ${FACTORY_FAKE_IDP_SECRET:dev-fake-idp-secret-key-change-in-prod-32bytes}
```

---

## 5. Application & Core Code Structure (`factory-service/src/main/kotlin/io/whozoss/factory/`)

Package base: `io.whozoss.factory`

### 5.1 Main Entry Point
- `FactoryServiceApplication.kt`:
  ```kotlin
  package io.whozoss.factory

  import org.springframework.boot.autoconfigure.SpringBootApplication
  import org.springframework.boot.runApplication

  @SpringBootApplication
  class FactoryServiceApplication

  fun main(args: Array<String>) {
      runApplication<FactoryServiceApplication>(*args)
  }
  ```

### 5.2 Configuration (`io.whozoss.factory.config`)
- `JacksonConfig.kt`:
  - Configures `ObjectMapper` with `JavaTimeModule`, disables `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS`, enables `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`.
- `OpenApiConfig.kt`:
  - `factoryOpenApi()` bean defining OpenAPI Info ("Factory Service API", "0.0.1").
  - `methodNameOperationCustomizer()` OperationCustomizer bean implementing standard operationId formatting `${methodName}${EntityName}` matching AgentOS pattern.

### 5.3 Exception Hierarchy & Response Envelope (`io.whozoss.factory.error`)
- Data classes for HTTP Error Envelope:
  ```kotlin
  data class ErrorResponse(val error: ErrorDetail)
  data class ErrorDetail(
      val code: String,
      val message: String,
      val details: Any? = null
  )
  ```
- Custom Exception hierarchy:
  - `abstract class FactoryException(val statusCode: Int, val errorCode: String, message: String, val details: Any? = null, cause: Throwable? = null) : RuntimeException(message, cause)`
  - Derived classes:
    - `ResourceNotFoundException(message: String = "Resource not found", details: Any? = null)` -> 404, "NOT_FOUND"
    - `ConflictException(message: String = "Resource conflict", details: Any? = null)` -> 409, "CONFLICT"
    - `RevisionConflictException(message: String = "Revision conflict", details: Any? = null)` -> 409, "REVISION_CONFLICT"
    - `UnprocessableEntityException(message: String = "Unprocessable entity", details: Any? = null)` -> 422, "UNPROCESSABLE_ENTITY"
    - `ForbiddenAdminRequiredException(message: String = "Admin authorization required", details: Any? = null)` -> 403, "FORBIDDEN_ADMIN_REQUIRED"
    - `UnauthenticatedException(message: String = "Authentication required", details: Any? = null)` -> 401, "UNAUTHENTICATED"
    - `BadRequestException(message: String = "Bad request", details: Any? = null)` -> 400, "BAD_REQUEST"
- `FactoryExceptionHandler.kt` (`@RestControllerAdvice`):
  - `@ExceptionHandler(FactoryException::class)`: returns `ResponseEntity.status(e.statusCode).body(ErrorResponse(ErrorDetail(e.errorCode, e.message ?: e.errorCode, e.details)))`
  - `@ExceptionHandler(IllegalArgumentException::class)`: returns 400 `ErrorResponse(ErrorDetail("BAD_REQUEST", e.message ?: "Invalid argument", null))`
  - `@ExceptionHandler(Exception::class)`: returns 500 `ErrorResponse(ErrorDetail("INTERNAL_ERROR", e.message ?: "Internal server error", null))`
  - Guarantees exact JSON structure: `{ "error": { "code": "...", "message": "...", "details": null } }`.

### 5.4 Web & Security Context (`io.whozoss.factory.web`)
- `CorrelationIdFilter.kt` (`@Component`, implements `Filter` or `OncePerRequestFilter`):
  - Inbound header check `X-Correlation-Id` (or `x-correlation-id`).
  - If missing/blank, generates `coday-corr-${UUID.randomUUID()}`.
  - Sets attribute on Request `CORRELATION_ID_ATTRIBUTE` and adds header `X-Correlation-Id` to `HttpServletResponse`.
- `TrustContext.kt` (data class):
  - Fields matching Node `extractTrustContext`:
    - `principalId: String?`
    - `principalType: String` ("human" / "service")
    - `organizationId: String?`
    - `workstreamId: String?`
    - `squadId: String?`
    - `roles: List<String>`
    - `scopes: List<String>`
    - `correlationId: String?`
    - `authenticationMethod: String` ("jwt", "proxy-signature", "loopback-dev", "anonymous")
    - `serviceIdentityId: String?`
    - `loopback: Boolean`
- `TrustContextFilter.kt` (`OncePerRequestFilter`):
  - Extracts JWT token (`Authorization: Bearer <token>`) using `FACTORY_FAKE_IDP_SECRET` HMAC validation if present.
  - Checks signed proxy headers (`x-proxy-signature`).
  - Fallback: Checks if socket remote address is loopback (`127.0.0.1`, `::1`, etc.) AND `factory.security.allow-loopback-dev` is true. If so, assigns `loopback-dev` method with `principalId` from header `x-factory-actor-id` or `"LOOPBACK_DEV_PRINCIPAL_ID"`, `scopes = ["*"]`.
  - Fallback: Anonymous context with null principal, empty roles, empty scopes.
  - Attaches `TrustContext` to request attribute `TRUST_CONTEXT_ATTRIBUTE = "trustContext"`.
- `AdminGuard.kt` (service/component or static utility methods):
  - `checkAdminAuthorization(trustContext: TrustContext?)`: checks if `trustContext` is authenticated AND (roles contain "admin" OR scopes contain "admin:*" OR scopes contain "*").
  - `requireAdminRole(trustContext: TrustContext?)`: if `!checkAdminAuthorization(trustContext).authorized`, throws `ForbiddenAdminRequiredException`.

### 5.5 Persistence Skeleton (`io.whozoss.factory.persistence`)
- `TenantScope.kt` (data class):
  ```kotlin
  data class TenantScope(
      val organizationId: String,
      val workstreamId: String
  )
  ```
- `TenantScopeProvider.kt` (Spring `@Component`):
  - Reads defaults from `factory.tenant.organization-id` and `factory.tenant.workstream-id`.
- `ScopedRepository.kt` (Interface):
  ```kotlin
  package io.whozoss.factory.persistence

  interface ScopedRepository<T, ID> {
      fun findById(scope: TenantScope, id: ID): T?
      fun deleteById(scope: TenantScope, id: ID): Boolean
  }
  ```

---

## 6. Kotlin Tests (`factory-service/src/test/kotlin/io/whozoss/factory/`)

### 6.1 `PostgresContainerSpec.kt`
- Abstract test base class utilizing Testcontainers `PostgreSQLContainer("postgres:16-alpine")`.
- `@DynamicPropertySource` overriding `spring.datasource.url`, `username`, `password`.

### 6.2 `FactoryServiceApplicationIntegrationTest.kt`
- Extends `PostgresContainerSpec`.
- `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`.
- Autowires `JdbcTemplate` and `TestRestTemplate`.
- Tests:
  1. Context loads successfully.
  2. Flyway applies migrations V1 to V7 onto the testcontainer.
  3. Verifies schema tables exist by querying `information_schema.tables` for:
     - `workflow_instances`
     - `artifacts`
     - `work_unit_leases`
     - `outbox_events`
     - `tenant_memberships`
  4. Calls `/actuator/health` and asserts HTTP 200 with status `"UP"`.

### 6.3 `HttpBoundaryIntegrationTest.kt`
- `@SpringBootTest` or `@WebMvcTest`.
- Tests:
  1. Correlation ID header: requests receive `X-Correlation-Id` echo in response headers.
  2. Error envelope: triggering a route or throw returns `{ "error": { "code": "...", "message": "...", "details": null } }`.
  3. Anonymous caller gets anonymous context with zero privileges.
  4. Admin Guard: calling admin guard with unauthorized/anonymous trust context throws 403 `FORBIDDEN_ADMIN_REQUIRED` and error envelope.

---

## 7. Utility Script (`factory-service/check-openapi-spec.sh`)

Create `factory-service/check-openapi-spec.sh` (modeled after AgentOS):
```bash
#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "Regenerating Factory Service OpenAPI spec..."
./gradlew generateOpenApiDocs --no-configuration-cache -q

echo "Checking for diff..."
if ! git diff --exit-code openapi/factory-openapi.yaml; then
  echo ""
  echo "❌ Factory Service OpenAPI spec is out of date."
  echo "   Please run: nx run factory-service:generate-openapi-spec"
  echo "   Then commit the updated factory-service/openapi/factory-openapi.yaml"
  exit 1
fi

echo "✅ Factory Service OpenAPI spec is up to date."
```
Ensure executable permissions (`chmod +x`).

---

## 8. Verification Plan & Acceptance Criteria

1. `./gradlew build` inside `factory-service/` compiles Kotlin without errors and executes tests.
2. `./gradlew test` executes JUnit tests with Testcontainers running Postgres and Flyway migrations V1->V7.
3. `/actuator/health` returns status `UP`.
4. Error responses conform strictly to `{ "error": { "code": "...", "message": "...", "details": null } }`.
5. `X-Correlation-Id` header is preserved or auto-generated on responses.
6. `project.json` targets `build`, `test`, `bootRun`, `bootJar` pass through Nx (`pnpm nx test factory-service`).
7. Directories `factory/` and `agentos/` remain completely untouched.

---

## Implementation Steps for Builder
1. Create directory structure under `factory-service/`.
2. Populate `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts`, and copy wrapper scripts.
3. Copy migration SQL files from `factory/infra/migrations/` to `factory-service/src/main/resources/db/migration/`.
4. Create `application.yml` and `project.json`.
5. Implement Kotlin classes in `io.whozoss.factory` (Application, config, error, web, persistence).
6. Implement integration tests using Postgres Testcontainers.
7. Add `check-openapi-spec.sh` and make executable.
8. Verify build and test execution via `./gradlew build` and `pnpm nx test factory-service`.
