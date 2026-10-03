# Plan Specification: W6b-partiel - Factory Service Static Cockpit & Multi-Lane Timeline

## Context & Objectives

The goal of this task is to enable `factory-service` (Kotlin/Spring Boot on port 8141) to serve the vanilla ES module cockpit directly on same-origin, and ensure the cockpit's timeline/projection view renders swimlanes (`agent`, `code`, `human`) consuming `factory-service` REST endpoints and SSE streams.

### Strict Scope Boundaries (Out of Scope for W6b-partiel)
- **DO NOT** delete the Node control plane or Node express server.
- **DO NOT** delete or alter the Node `factory/run.mjs` instrument.
- **DO NOT** alter other consumers or repoint port 3141 traffic.
- **DO NOT** touch the `factory-forge-plugin`.
- **ONLY** modify `factory-service` configuration/controllers and cockpit static JS/HTML assets if needed so that opening `http://localhost:8141/cockpit` displays active workflow sessions and multi-lane timeline projections.

---

## Analysis & Architecture Findings

1. **Static Content Serving in Spring Boot (`factory-service`)**:
   - `WebConfig.kt` currently implements `WebMvcConfigurer` but does NOT register any `ResourceHandler`.
   - Asset Location: Cockpit static files live in `factory/dashboard/` at root (`cockpit.html`, `css/**`, `js/**`).
   - Assets Directory Property: `factory.cockpit.assets-dir` (defaulting to `factory/dashboard`). Fallback classpath resource support if configured.
   - **Crucial Requirement**: Spring Boot defaults `.mjs` files to `application/octet-stream` or `text/plain`. ES Modules in modern browsers fail to load unless served as `application/javascript`.
   - A custom `ResourceResolver` or MediaType mapping in Spring MVC (`addResourceHandlers` / `ResourceHandlerRegistry` / `MimeTypes` / `MediaTypeFactory` or explicit MediaType mappings) MUST be registered for `.mjs` files.

2. **Routes & Redirects**:
   - Route `GET /cockpit` or `GET /cockpit/` must serve `cockpit.html` (or forward to `cockpit.html`).
   - Root redirect `GET /` -> `GET /cockpit` or direct static welcome page mapping.
   - API endpoints (`/api/**`) remain handled by `@RestController` mappings without interference from static handlers.

3. **Missing API Endpoint (`/api/config`)**:
   - `ProjectionController` in `js/views/projection.mjs` calls `GET /api/config` to discover `agentosUrl` and `codayExpressUrl`.
   - In `factory-service`, `GET /api/config` does not exist yet.
   - Adding a lightweight `ConfigController` or endpoint `GET /api/config` returning `{ "agentosUrl": null, "codayExpressUrl": null }` (or configured properties) avoids 440/404 errors during cockpit view initialization.

4. **Cockpit API & SSE Alignment**:
   - `ApiClient` uses relative `baseUrl = ''` by default, sending requests to `http://localhost:8141/api/factory/workflows...`.
   - `SseClient` subscribes to `/api/factory/workflows/stream`.
   - `temporal-lanes.mjs` and `workflow-card.mjs` (W8.4) already parse and display step lanes (`agent`, `code`, `human`) and responsibility names from `WorkflowProjection` DTOs.

---

## Detailed Step-by-Step Implementation Plan

### Step 1: Configuration & Static Asset Serving (`factory-service`)

#### Files to Touch
- `factory-service/src/main/kotlin/io/whozoss/factory/config/FactoryProperties.kt` (or new `CockpitProperties.kt`)
- `factory-service/src/main/kotlin/io/whozoss/factory/config/WebConfig.kt`
- New Controller (or forwarding handler) in `io.whozoss.factory.web.CockpitController`

#### Changes
1. Add `factory.cockpit.assets-dir` configuration property (defaulting to `factory/dashboard` or `file:factory/dashboard/`).
2. Update `WebConfig.kt` (`WebMvcConfigurer`):
   - Override `addResourceHandlers(registry: ResourceHandlerRegistry)`.
   - Map `/cockpit/**` and relative static paths (`/js/**`, `/css/**`, `/cockpit.html`) to `file:${factory.cockpit.assets-dir}/` (or classpath if bundled).
   - Configure custom media type handling for `.mjs` extensions to guarantee `Content-Type: application/javascript`.
3. Add `CockpitController` / route mapping for `GET /cockpit` and `GET /cockpit/`:
   - Returns forward to static `cockpit.html` or direct resource rendering.

### Step 2: Minimal Config Endpoint (`/api/config`)

#### Files to Touch
- `factory-service/src/main/kotlin/io/whozoss/factory/config/ConfigController.kt` (or `WorkflowHttp.kt`)

#### Changes
1. Expose `GET /api/config` returning JSON:
   ```json
   {
     "agentosUrl": null,
     "codayExpressUrl": null,
     "factoryServiceUrl": "http://localhost:8141"
   }
   ```
2. Annotate with appropriate TrustContext / Allow-Loopback-Dev handling if required.

### Step 3: Front-end Vanilla Cockpit Verification & Neutralization

#### Files to Touch
- `factory/dashboard/cockpit.html`
- `factory/dashboard/js/views/projection.mjs`
- `factory/dashboard/js/components/temporal-lanes.mjs`

#### Changes
1. Verify relative path calls in `js/services/api-client.mjs` and `js/services/sse-client.mjs`.
2. Ensure `/api/config` fetch failure is non-fatal if not present, and handles response seamlessly.
3. Validate that `projection.mjs` and `temporal-lanes.mjs` correctly render swimlanes (`agent`, `code`, `human`) with step status and responsibility details from `/api/factory/workflows/{id}/projection`.

### Step 4: Integration Tests (`factory-service`)

#### Files to Touch
- `factory-service/src/test/kotlin/io/whozoss/factory/web/CockpitStaticServingIntegrationTest.kt`

#### Changes
1. Write integration test extending `DomainIntegrationTest`:
   - Test `GET /cockpit` returns 200 OK with `text/html`.
   - Test `GET /js/app.mjs` or `/js/views/projection.mjs` returns 200 OK with `Content-Type: application/javascript`.
   - Test `GET /api/config` returns 200 OK with JSON content.
   - Verify workflow projection multi-lane structure in `/api/factory/workflows/{id}/projection` endpoint.

---

## Verification & Manual Instructions

1. **Building and Testing**:
   - Run Kotlin/Spring tests:
     ```bash
     cd factory-service && ./gradlew test
     ```
   - Run Nx affected tests:
     ```bash
     pnpm nx test factory-service
     ```

2. **Manual Verification Procedure**:
   - Start `factory-service`:
     ```bash
     cd factory-service && ./gradlew bootRun
     ```
   - Open browser at `http://localhost:8141/cockpit`.
   - Seeded workflow session (`forge-story-fullstack-ux`) or newly started session should be listed.
   - Click on the session to view the timeline projection.
   - Verify 3 swimlanes (`Humain`, `Agent`, `Code`) display the corresponding steps, status badges, and actor names (`responsibility.name`).
   - Trigger SSE events and confirm live timeline updates.

---

## Summary of Deliverables & Future Scope

- **Deliverables in W6b-partiel**:
  - `factory-service` static cockpit hosting at `:8141/cockpit`.
  - `.mjs` MIME type configuration as `application/javascript`.
  - Endpoint `GET /api/config` added.
  - Multi-lane swimlane timeline verified.
  - Comprehensive integration test suite.

- **Remaining for Full W6b (Future Phase)**:
  - Decommissioning Node control plane server (`factory/dashboard/server.mjs`).
  - Decommissioning `factory/run.mjs` instrument.
  - Repointing other services consuming port 3141 to port 8141.
