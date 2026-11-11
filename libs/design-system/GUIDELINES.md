# Design System Guidelines

## Purpose

`libs/design-system` is an **internal Angular library** (not publishable, no build target). It provides the reusable UI primitives of the app, with no business logic and no knowledge of any specific application. The page building blocks of the settings pages (`ds-page`, `ds-section-header`, `ds-form-field`, `ds-fieldset`, `ds-group`, `ds-form-actions`, `ds-card`, `ds-notice`, `ds-segmented`, `ds-disclosure`, `ds-member-row`, `dsButton`, `dsControl`, `dsCheck`, `dsDefinitionList`, `dsTable`, `ds-tag`) live here, and `DS_PAGE_KIT` imports the common ones at once.

## Component Conventions

- Selector prefix: `ds-` (enforced by ESLint and `project.json` `"prefix": "ds"`); attribute directives are `dsXxx`.
- `standalone: true`, modern Angular syntax (`@if`, `@for`, `inject()`, signal `input()` / `output()`)
- Public API via inputs / outputs — keep it explicit and self-documenting
- No app-level service injection (no `Router`, no state services)
- `ViewEncapsulation.Emulated` (default) — never `None`

## Two kinds of primitive

- **A component with its own markup** (`ds-page`, `ds-form-field`, `ds-card`…): its stylesheet is in the component, so it is encapsulated and ships once.
- **A directive on a native element** (`dsButton`, `dsControl`, `dsTable`…): the element belongs to the consumer's template, so a component stylesheet cannot reach it (and `::ng-deep` is not allowed). Its look is a plain class (`.ds-button`, `.ds-control`, `.ds-table`…) in `src/styles/_primitives.scss`, which **the host app includes once** in its global styles (`@use 'libs/design-system/src/styles/primitives'`). It is not forwarded by the `styles` barrel, so a component never ships a copy.

## Type scale

`src/styles/_scale.scss` is the only place a font size is decided: title 25px, section heading 15px, body 14px, small 13px, micro 11px. Two families, both tokens of the skin: `--font-body` for text, `--font-heading` for buttons and micro labels, `--font-code` for code.

## CSS Token Contract

Components **consume** CSS custom properties via `var(--token, fallback)`. They **never define** tokens — that is the host application's responsibility.

Rule: if a value appears in two or more places, use a `var()` reference. Never hardcode a colour or shadow that has a token equivalent.

Token definitions for the Coday app live in `apps/client/src/app/styles/colors.scss`. The full token list expected by both `design-system` and `agentos-ui` is documented in `libs/agentos-ui/src/styles/_contract.scss`.

## Adding a New Component

1. Create `src/lib/components/<name>/<name>.component.ts|scss` (a template file only when the markup is long)
2. Export from `src/index.ts`; add it to `DS_PAGE_KIT` if every page uses it
3. Use only tokens from the contract with fallback values, and sizes from the scale
4. Keep the component focused — no business logic, no state management
