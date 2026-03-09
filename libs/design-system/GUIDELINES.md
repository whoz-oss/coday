# Design System Guidelines

## Purpose

`libs/design-system` is an **internal Angular library** (not publishable, no build target). It provides low-level, reusable UI primitives with no business logic and no knowledge of any specific application.

Current components: `ds-icon-button`.

## Component Conventions

- Selector prefix: `ds-` (enforced by ESLint and `project.json` `"prefix": "ds"`)
- `standalone: true`, modern Angular syntax (`@if`, `@for`, `inject()`)
- Public API via `@Input()` / `@Output()` — keep it explicit and self-documenting
- No app-level service injection (no `Router`, no state services)
- `ViewEncapsulation.Emulated` (default) — never `None`

## CSS Token Contract

Components **consume** CSS custom properties via `var(--token, fallback)`. They **never define** tokens — that is the host application's responsibility.

Rule: if a value appears in two or more places, use a `var()` reference. Never hardcode a color or shadow that has a token equivalent.

Token definitions for the Coday app live in `apps/client/src/app/styles/colors.scss`. The full token list expected by both `design-system` and `agentos-ui` is documented in `libs/agentos-ui/src/styles/_contract.scss`.

## Adding a New Component

1. Create `src/lib/components/<name>/<name>.component.ts|html|scss`
2. Export from `src/index.ts`
3. Use only tokens from the contract with fallback values
4. Keep the component focused — no business logic, no state management
