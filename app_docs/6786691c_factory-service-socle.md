# Factory Service W0 socle

## What changed

A new, independent Kotlin/Spring Boot service was added under `factory-service/`. It is intentionally a foundation only: `FactoryServiceApplication` boots the HTTP, security, persistence, Flyway, actuator, and OpenAPI infrastructure, with no domain controllers or aggregate implementations.

The build is isolated from `agentos/` and `factory/`: `settings.gradle.kts` names the project `factory-service`, the local version catalog pins Java/Kotlin/Spring/Testcontainers versions, and the Gradle wrapper is included. `build.gradle.kts` targets Java/Kotlin 25, uses Spring Web, Actuator, Spring Data JDBC (not JPA), PostgreSQL/Hikari, Flyway, Jackson, springdoc, and the Kotlin logging library. `project.json` exposes Nx `build`, `test`, `bootRun`, `bootJar`, plus OpenAPI generation/check targets.

## Runtime foundation

- `src/main/resources/application.yml` configures port 8141, PostgreSQL environment overrides, Flyway validation, actuator health/info/metrics, and the `factory.*` bind, tenant, loopback-development, and Fake IdP settings.
- `FactoryProperties.kt` binds those settings into typed configuration. `JacksonConfig.kt` installs Java time handling, disables timestamp date serialization and unknown-property failures, and preserves nullable error details in JSON.
- `CorrelationIdFilter.kt` propagates a trimmed inbound `X-Correlation-Id`, or generates `coday-corr-<uuid>`, stores it as a request attribute, and echoes it on the response.
- `TrustContextExtractor.kt`, `TrustContextFilter.kt`, `TrustContext.kt`, `FakeIdp.kt`, and `MembershipResolver.kt` establish the request trust boundary. They accept only a verified HS256 bearer token or fresh HMAC-signed proxy headers; otherwise they allow the loopback-dev identity only for loopback requests when explicitly enabled, and otherwise produce an anonymous context with no principal, roles, or scopes. `TrustContextArgumentResolver.kt` and `WebConfig.kt` make the resolved context available to controller parameters.
- `AdminGuard.kt` centralizes the admin decision. It accepts the normalized `admin` role, `admin:*`, or `*` scope and throws `FORBIDDEN_ADMIN_REQUIRED` for missing/unauthorized contexts.
- `TenantScope.kt`, `TenantScopeProvider.kt`, and `ScopedRepository.kt` provide the fail-closed `(organizationId, workstreamId)` repository boundary for later domain work.

## Errors and API description

`FactoryException.kt` defines transport-ready 400, 401, 403, 404, 409, revision-conflict, and 422 exceptions. `FactoryExceptionHandler.kt` maps these, `IllegalArgumentException`, and unexpected exceptions to the Node-compatible envelope:

```json
{"error":{"code":"...","message":"...","details":null}}
```

`OpenApiConfig.kt` supplies the API metadata and stable operation IDs based on controller method/entity names. The committed `openapi/factory-openapi.yaml` currently has no paths because the socle adds no business endpoints. The `openapi` Spring profile uses H2 and disables Flyway so `generateOpenApiDocs` can run without PostgreSQL. `check-openapi-spec.sh` regenerates the document and fails if the committed file differs.

## Database and verification

The seven SQL files under `src/main/resources/db/migration/` are present with the V1–V7 names and PostgreSQL schema used by the Factory. `PostgresContainerSpec.kt` supplies a PostgreSQL 16 Testcontainers fixture and injects its JDBC properties into Spring. `FactoryServiceApplicationIntegrationTest.kt` checks context startup, successful Flyway history entries 1 through 7, expected tables including `workflow_instances`, `artifacts`, `work_unit_leases`, and `outbox_events`, and `/actuator/health` returning `UP`.

`HttpBoundaryIntegrationTest.kt` and the supporting test controller/unit tests cover generated and propagated correlation IDs, canonical error responses, anonymous zero-privilege behavior, loopback-dev behavior, admin rejection/authorization, Fake IdP verification, and trust-context argument resolution. The PostgreSQL integration tests are annotated to skip when Docker is unavailable.

From the repository root, use `pnpm nx build factory-service` or `pnpm nx test factory-service`. Directly, run `./gradlew build` or `./gradlew test` from `factory-service/`. To validate the checked-in API description, run `./check-openapi-spec.sh` from that directory. No changes were made to the existing `factory/` or `agentos/` trees.
