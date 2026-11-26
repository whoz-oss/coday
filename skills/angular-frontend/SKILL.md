---
name: angular-frontend
description: Angular client and AgentOS UI work in apps/client and libs (components, services, styles, design system tokens, agentos-ui and agentos-api-client). Read before changing any browser-side code.
---

# Angular frontend

Use this skill for any change to browser-side code: the Angular client (`apps/client`), the Factory cockpit
(`apps/factory-cockpit`) and the Angular libraries `libs/design-system`, `libs/agentos-ui` and
`libs/agentos-api-client`.

## Read before coding

Open with `FILES` the documents that match the task, and follow them over this summary:

- `apps/client/docs/angular-guidelines.md`: mandatory Angular patterns (standalone, `inject()`, `@if`/`@for`, signals, typed forms, OnPush)
- `apps/client/docs/architecture.md`: layout of the client and the two frontends
- `apps/client/docs/entity-crud-pattern.md`: when building list/form/detail pages for an entity
- `apps/client/docs/nx-tasks.md`: build, lint, test and client generation commands
- `libs/design-system/GUIDELINES.md`: when touching `ds-*` primitives, tokens or the type scale
- `libs/agentos-ui/GUIDELINES.md`: when touching AgentOS pages, themes or page patterns
- `libs/agentos-api-client/GUIDELINES.md`: when consuming or regenerating the API client

## Component architecture

- **Small and focused**: a component does one thing well. If it fetches data, handles interaction and renders
  complex display logic, split it.
- **Smart/dumb separation**: container components handle state and data, presentational components receive inputs
  and emit outputs. The presentational layer is reusable, the container is not.
- **Composition over inheritance**: build complex UIs by composing small components.
- **Clear public API**: `@Input()`/`@Output()` (or signal `input()`/`output()` where the lib allows it) are the
  contract. Prefer explicit names (`threadId`, `messageContent`, `onDelete`) over `data` or `value`. Never name an
  output like a DOM event.

## Services and state

- API services are a pure HTTP layer (1:1 with backend endpoints); state services own business logic and state.
- Components always use state services and never call API services or `HttpClient` directly.
- New state uses signals; do not migrate existing `BehaviorSubject` state unless you are already touching it.

## Styling

- Use design-system tokens and primitives, never hardcoded colours, sizes or shadows that have a token.
- Never define tokens in a component, never use `::ng-deep`.
- A page of the AgentOS settings shells is built from the design-system kit, without its own stylesheet.

## Backend contract

- Do not invent backend APIs. If the task needs an endpoint or a field that does not exist, describe the contract
  you need (path, method, payload) in your report instead of faking it.
- Never edit generated files under `libs/agentos-api-client/src/lib/`. Hand-written code goes in
  `libs/agentos-api-client/src/custom/`. The generated client follows `agentos/openapi/agentos-openapi.yaml`,
  itself generated from the Kotlin controllers.

## Verification

Run what applies and report exactly what you ran and what you could not run:

```bash
pnpm nx lint <project>
pnpm nx test <project>
pnpm nx build <project>
pnpm nx affected -t lint test
```

For a visible change, check it in the browser when a CHROME integration and a running stack are available.
