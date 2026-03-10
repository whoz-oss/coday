# AgentOS UI Guidelines

## Purpose

`libs/agentos-ui` is an **internal Angular library** (not publishable, no build target). It implements the AgentOS section of the Coday web client, lazy-loaded via `AGENTOS_ROUTES` at `/agentos`.

It is the nascent v2 frontend, growing independently from the Coday frontend. See `apps/client/docs/architecture.md` for the two-frontend context.

## Dependencies

- Consumes `ds-*` components from `libs/design-system`
- Injects services from `libs/agentos-api-client` for all HTTP and SSE communication
- Has no dependency on app-level services from `apps/client`

## Component Conventions

- Selector prefix: `agentos-` (enforced by ESLint and `project.json` `"prefix": "agentos"`)
- `standalone: true`, modern Angular syntax (`@if`, `@for`, `inject()`)
- Signal-based `input()` / `output()` is fine here — this is not a publishable lib
- No direct `HttpClient` usage — always go through `agentos-api-client` services

## CSS Token Contract

Components use `var(--token, fallback)` only. They never define tokens. The full token list is documented in `src/styles/_contract.scss`. Tokens are provided by the host app (`apps/client/src/app/styles/colors.scss`).

## Routing

`src/lib/agentos.routes.ts` exports `AGENTOS_ROUTES`, consumed by `apps/client/app.routes.ts` via `loadChildren`. All routes within the lib use `loadComponent` for lazy loading.

Guards live in `src/lib/guards/`.

## Adding a New Component

1. Create `src/lib/components/<name>/`
2. Export from `src/index.ts` only if the component needs to be accessible outside the lib (rare)
3. Use `agentos-api-client` services for data — never call `HttpClient` directly
4. Use only CSS tokens from the contract
