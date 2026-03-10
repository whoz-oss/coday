# AgentOS API Client Guidelines

## Purpose

Generated Angular HTTP client for the AgentOS backend. Consumed by `agentos-ui` components and services.

## Directory Structure

```
src/
  lib/       ← GENERATED — never edit by hand
  custom/    ← HAND-WRITTEN — safe to edit
  index.ts   ← public API (re-exports both lib and custom)
```

All hand-written additions go in `src/custom/` and are exported via `src/index.ts`. The generator overwrites `src/lib/` but never touches `src/custom/` or `src/index.ts`.

## Regenerating the Client

See `apps/client/docs/nx-tasks.md` for the commands. Generator configuration lives in `libs/agentos-api-client/openapitools.json`.

After regeneration, verify `src/index.ts` still exports everything needed — the generator overwrites `src/lib/index.ts`.

## Why SSE Is Hand-Written

The SSE endpoint (`GET /api/cases/{caseId}/events`) is excluded from generation because `EventSource` cannot be expressed as a standard OpenAPI HTTP operation. `CaseEventSseService` in `src/custom/` wraps it manually, returning `Observable<CaseEvent>` and managing the `EventSource` lifecycle.

## Consuming the Client

```typescript
// app.config.ts — registered once
provideApi({ basePath: '/api/agentos' })

// in a service or component
private readonly casesApi = inject(CasesService)
this.casesApi.getCases().subscribe(...)
```

Generated services are `providedIn: 'root'` — no module registration needed.
