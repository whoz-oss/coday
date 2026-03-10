# Angular Client Architecture

## Two Frontends, One App

The client hosts two distinct frontend experiences that coexist under the same Angular app:

**Coday frontend** — the original interface, rooted at `/project/:projectName`. Mature, thread-centric, tightly coupled to the Express backend via REST + SSE. Components live in `apps/client/src/app/components/`, services in `apps/client/src/app/core/services/`.

**AgentOS frontend** — a nascent v2 interface, lazy-loaded at `/agentos`. Built in `libs/agentos-ui`, it talks exclusively to the AgentOS Kotlin backend via the generated `agentos-api-client`. It grows independently from the Coday frontend and will eventually become the primary interface.

The two frontends share the app shell, the Angular Material + design-system token layer, and the router. They do **not** share state services or API services.

## Two-Layer Service Pattern

Both frontends follow the same mandatory separation:

| Layer | Suffix | Responsibility |
|---|---|---|
| API | `*-api.service.ts` | Pure HTTP, 1:1 endpoint mapping, returns `Observable<T>` |
| State | `*-state.service.ts` | Business logic, reactive state, coordinates API calls |

Components inject only state services. Never API services directly.

## Real-Time Layer — SSE

Both frontends use SSE but through separate services:

- **Coday**: `EventStreamService` wraps `EventSource` for `GET /api/projects/:project/threads/:id/event-stream`, emits `CodayEvent` instances.
- **AgentOS**: `CaseEventSseService` (in `agentos-api-client`) wraps `EventSource` for `GET /api/cases/:id/events`, emits `CaseEvent` instances.

Both run `EventSource` callbacks outside `NgZone` and re-enter on emit.

## App Shell

`appConfig` (`app.config.ts`) bootstraps both frontends:
- `provideRouter(appRoutes)` with the Coday routes and the `loadChildren` lazy import for AgentOS
- `APP_INITIALIZER` for `ProjectStateService` (Coday) and `OAuthService`
- `provideApi({ basePath: '/api/agentos' })` registers the AgentOS API client base path

## Guards

Functional guards synchronise URL parameters into state services before component activation — the deep-link mechanism.

- Coday: `projectStateGuard`, `threadStateGuard`
- AgentOS: `agentosReadyGuard`

## Proxy

The Angular dev server proxies `/api/agentos/*` to the AgentOS backend (port 8123). All other `/api/*` calls reach the Express server. Config: `apps/client/proxy.conf.json`.
