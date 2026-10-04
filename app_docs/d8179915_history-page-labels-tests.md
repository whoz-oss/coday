# Cockpit v2 history page: completed-run labels and coverage

## What changed

The History page now presents the lifecycle states in the requested French UI vocabulary:

- The header describes completed, stopped, and destroyed runs, including their costs and phases.
- Status filters read `Tous`, `En cours`, and `Terminés / Arrêtés`, while retaining the `all`, `working`, and `destroyed` filter values.
- The status column renders active rows as `en cours` and destroyed rows as `terminé / arrêté`.

The underlying history component logic remains based on the existing `FactoryStore.sandboxes()` rows. The new tests exercise the derived rows, status/query filtering, cost fallback and aggregation, cost bars, table wiring, and CSV export across both active and destroyed data, including destroyed sandboxes with no run but a final cost.

## Files

- `apps/cockpit-v2/src/app/features/history/history-page.component.html` — updated the page description, filter labels, and status-chip labels.
- `apps/cockpit-v2/src/app/features/history/history-page.component.spec.ts` — added the Angular/Jest unit suite. It covers component rendering, all three status filters, query matching across sandbox/run fields, combined filters, cost calculations and bars, paginator/sort attachment, and CSV download generation.

No core, sandbox-feature, or layout files are part of this change.

## Verification

From the repository root, run:

```sh
pnpm nx test cockpit-v2
pnpm nx lint cockpit-v2
```

The tests use a stubbed `FactoryStore` signal with active, destroyed-with-run, and destroyed-without-run fixtures. The CSV test mocks the browser URL/blob/download APIs; the sort and paginator test checks that the Material table receives its view-child bindings and the expected cost-descending defaults are present.
