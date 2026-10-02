# Cockpit v2 real workflow REST/SSE integration

## What changed

`apps/cockpit-v2` now has a real workflow data path for runs and sessions while retaining the existing mock sandbox fleet and cost aggregation. Angular HTTP support is enabled in `apps/cockpit-v2/src/app/app.config.ts` with `provideHttpClient()`.

The new `FactoryApiService` in `apps/cockpit-v2/src/app/core/factory-api.service.ts` wraps the factory-service endpoints under `/api/factory/workflows`: active/removed list, workflow detail, timing, evidence, and metrics. It unwraps `{ data }` responses, accepts raw JSON payloads, adds an `X-Correlation-Id`, conditionally adds the trimmed namespace query/header, URL-encodes workflow IDs, and converts HTTP/transport failures into `FactoryApiError` objects.

The new `SseService` in `apps/cockpit-v2/src/app/core/sse.service.ts` connects to `/api/factory/workflows/stream`, optionally filtered by namespace. It listens for updated, removed, restored, and purged projection events, exposes invalidation and connection-state observables, and reconnects with bounded exponential delays. A reconnect after an established connection emits `reconnected$`, allowing REST state to be refreshed. `EVENT_SOURCE_FACTORY` makes the browser `EventSource` replaceable in tests and safe to handle when unavailable.

`apps/cockpit-v2/src/app/core/mappers.ts` adds defensive, pure Projection v2 mappers. They:

- map workflow/step states into cockpit run statuses and phase segments;
- derive duration, current phase, token/cost fields, session steps, and evidence-backed `RunEvent`s;
- classify lanes from explicit `lane`, `responsibility.kind`, or name hints;
- group human steps into `engineer`, code steps into `code`/workspace, and agents into `agent:<name>` lanes;
- calculate relative timeline block positions, minimum visible durations, ticks/error ticks, tones, request blocks, and context percentages;
- combine projection, timing, evidence, and metrics payloads into `SessionDetail`.

`apps/cockpit-v2/src/app/core/factory.store.ts` now loads active workflows on construction, listens for SSE invalidations/reconnects, maps real workflow runs onto active mock sandboxes, and asynchronously enriches loaded sessions with timing/evidence/metrics. Its public signals and methods remain unchanged. The store comments explicitly identify workflow runs, sessions, and SSE refreshes as real data, while the sandbox fleet and aggregated Archay/destroyed costs remain mocked. REST failures clear real workflow/session state but preserve the mock fleet and demo-session fallback.

## Tests and verification

The change adds or updates focused Angular/Jest tests in:

- `apps/cockpit-v2/src/app/core/factory-api.service.spec.ts` — envelopes, raw arrays, correlation/namespace headers, and normalized errors;
- `apps/cockpit-v2/src/app/core/sse.service.spec.ts` — named events, namespace URLs, malformed payloads, reconnect notifications, and close behavior;
- `apps/cockpit-v2/src/app/core/mappers.spec.ts` — status, phase, lane/timing, and complete session mapping;
- `apps/cockpit-v2/src/app/core/factory.store.spec.ts` — initial loading, real run/session mapping, SSE-triggered refresh, mock preservation, and API failure degradation.

Verify with `pnpm nx test cockpit-v2`; the requested project checks are `pnpm nx lint cockpit-v2` and `pnpm nx build cockpit-v2`.

The accompanying implementation plan is recorded in `specs/04a3274c_cockpit_v2_real_rest_sse_runs_sessions.md`.
