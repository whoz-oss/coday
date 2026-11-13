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

## Adding a theme

A theme (variant) only changes the skin: colors, font, radii, shadows. Four places to touch:

1. **Variant table** — add `{ id, label, description, modes }` to `THEME_VARIANT_DEFINITIONS` in `src/lib/services/theme.service.ts`. The `ThemeVariant` type, the skin selector of the profile page and the user menus derive from it (the table order is only the display order: the default variant is `DEFAULT_THEME_VARIANT`, `whoz-oss`, declared right after the `ThemeVariant` type). `modes` lists what the variant renders (Industry, Terminal and Whoz OSS all have `['light', 'dark']`; a light-only theme would list `['light']`: the mode is then forced, but the user's stored mode is never rewritten).
2. **Tokens** — in `apps/client/src/app/styles/colors.scss`, define the full token list (same names as the Industry block, plus the four `--radius-*` with a unit), scoped as `agentos-shell[data-theme='<id>-<mode>'], :is([data-theme='<id>-<mode>']) agentos-shell`. Never define them on `:root` or `html[data-theme]`: `data-theme` is shared with the legacy app. Write the set once in a mixin when it is needed in several scopes.
3. **Overlay scope** — CDK overlay panels (`.ds-kebab-panel`, rendered under `body`) are outside `agentos-shell` and do not inherit its tokens. Apply the same tokens on `[data-theme='<id>-<mode>'] .ds-kebab-panel`, otherwise the panel keeps the Industry fallbacks. Dialogs and snackbars still use the legacy `:root` palette (not themed yet).
4. **Font, color-scheme and legacy alias** — add the Google Fonts link to `apps/client/src/index.html`. A `<id>-dark` mode needs nothing in `styles.scss` (`color-scheme` and the snackbar styles already match `[data-theme$='-dark']`), but it must be added to the legacy dark alias block of `colors.scss` (next to `terminal-dark` and `whoz-oss-dark`), otherwise dialogs, snackbars and legacy components stay on the light `:root` palette.

## Page patterns

Every page of the settings shells is built from the design-system kit (`libs/design-system`), never from local CSS: a page component has **no stylesheet**. Import `DS_PAGE_KIT` and add what the page needs (`TableDirective`, `TagComponent`, `SegmentedComponent`, `DisclosureComponent`, `MemberRowComponent`).

- **Frame**: `<ds-page>` wraps the page. The `ds-section-header` takes the whole content column; everything under it stops at 720px and aligns left (`full` lifts that for a table, `framed` is for a page rendered outside a settings shell: profile, new namespace, namespaces list). The back link of a form is a `dsButton variant="ghost"` projected in the header, so a form title sits at the same height as a list title.
- **List page**: `ds-section-header` (title, description, then the `agentos-table-filter` and a `dsButton variant="primary"` "New …"), then the `dsTable` markup with the `settings-table__*` helpers (global, `libs/design-system/src/styles/_primitives.scss`). Loading, empty, no-match and error states use `settings-table__status` / `settings-table__error`. Row actions are plain buttons in `settings-table__actions`; deleting always goes through `agentos-row-delete-action` with a `createRowDeletionState()` (never `window.confirm()`, which cannot show a server error). A scope column uses `ds-tag` (`neutral` platform, `accent` namespace, `outline` personal / read-only).
- **Form page**: `<form class="ds-form">` of `ds-form-field` (label, required mark, hint, error) wrapping a `dsControl`, grouped in `ds-fieldset` / `ds-group`, closed by `ds-form-actions` with the primary `dsButton` first. A form-level failure is a `ds-notice`. A validation message is the `error` input of the field, never a local `<span>`; a touched invalid `dsControl` already shows the error border.
- **Detail page** (inspect, view): `ds-card` blocks holding a `dl dsDefinitionList`, a `ds-disclosure` or a `<pre class="ds-pre">`.

Tokens and sizes: components never write a font size or a colour, they use the kit. The type scale (`libs/design-system/src/styles/_scale.scss`) is 25 / 15 / 14 / 13 / 11px; a new primitive belongs in the design system, not in a page.

Messages are in English.

## Adding a New Component

1. Create `src/lib/components/<name>/`
2. Export from `src/index.ts` only if the component needs to be accessible outside the lib (rare)
3. Use `agentos-api-client` services for data — never call `HttpClient` directly
4. Use only CSS tokens from the contract
