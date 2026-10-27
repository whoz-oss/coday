# Angular Client Architecture

## Overview

`apps/client` is a thin Angular host for the **AgentOS frontend**, served at `/`. The UI itself lives in `libs/agentos-ui`, lazy-loaded through `AGENTOS_ROUTES`, and talks exclusively to the AgentOS Kotlin backend via the generated `agentos-api-client`.

The host app only provides the shell: bootstrap, router, global styles / token layer (Angular Material + design system), and `provideApi`.

> The former Coday (thread-centric) frontend that used to be served at `/` has been removed. A temporary route in `app.routes.ts` redirects old `/agentos/...` URLs to `/...`; it can be dropped once `libs/agentos-ui` no longer navigates to absolute `/agentos/...` paths.

## Two-Layer Service Pattern

| Layer | Suffix | Responsibility |
|---|---|---|
| API | `*-api.service.ts` | Pure HTTP, 1:1 endpoint mapping, returns `Observable<T>` |
| State | `*-state.service.ts` | Business logic, reactive state, coordinates API calls |

Components inject only state services. Never API services directly.

## Real-Time Layer — SSE

`CaseEventSseService` (in `agentos-api-client`) wraps `EventSource` for `GET /api/cases/:id/events`, emits `CaseEvent` instances. `EventSource` callbacks run outside `NgZone` and re-enter on emit.

## App Shell

`appConfig` (`app.config.ts`):
- `provideRouter(appRoutes)` — root route lazy-loads `AGENTOS_ROUTES`
- `APP_INITIALIZER` loading the current user (`UserStateService.loadMe()`)
- `provideApi({ basePath: '/api/agentos' })` registers the AgentOS API client base path

## Guards

Functional guards synchronise URL parameters into state services before component activation — the deep-link mechanism. AgentOS: `agentosReadyGuard`.

## Theme

Theming is owned by `AgentosThemeService` (`libs/agentos-ui`), exposed through `THEME_PORT`.

## Proxy

The Angular dev server proxies `/api/agentos/*` to the AgentOS backend (port 8123). Config: `apps/client/proxy.conf.json`.
