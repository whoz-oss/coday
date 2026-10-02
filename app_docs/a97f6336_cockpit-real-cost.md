# Cockpit v2 real-cost integration

## What changed

`apps/cockpit-v2` now consumes the additive `realCost` data returned by factory-service metrics. The mapper layer accepts a nested `metrics.realCost` block and also tolerates a flattened metrics payload. It defensively reads numeric fields and falls back to projection, snapshot, or existing metrics costs when no usable real-cost value is present.

- `RunSummary`, `SessionDetail`, and `CostSummary` now carry optional `unknownCostCount`. A non-zero count means the stored cost is a lower bound, not an exact total.
- `realCost.cost` maps to `costUsd`; `realCost.unknownCostCount` is retained explicitly; positive `realCost.liveTokens` can supply session tokens.
- Session enrichment remaps both the session and the matching sandbox run after metrics arrive. `FactoryStore.costs()` sums active run costs and sums their unknown-cost counts.
- Sandbox fleet-container costs, `archayUsd`, and `destroyedUsd` remain explicitly marked as mock values because there is no sandbox-lifecycle backend API yet.

## Where it lives

- `apps/cockpit-v2/src/app/core/mappers.ts`: `RealCost`, `extractRealCost`, and real-cost-aware run/session mapping.
- `apps/cockpit-v2/src/app/core/models.ts`: uncertainty fields and their lower-bound semantics.
- `apps/cockpit-v2/src/app/core/factory.store.ts`: enrichment of attached runs and active-cost aggregation.
- `apps/cockpit-v2/src/app/shared/pipes/format.pipes.ts`: `UsdPipe` accepts an optional unknown-count argument and prefixes uncertain values with `≥`.
- `apps/cockpit-v2/src/app/shared/ui/metric-chip.component.ts`: uncertainty-aware tooltip support.
- `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.html`, `apps/cockpit-v2/src/app/features/session/session-page.component.html`, and `apps/cockpit-v2/src/app/layout/shell.component.html`: pass uncertainty through to cost displays and the shell summary.

## Verification and tests

Coverage was added in `apps/cockpit-v2/src/app/core/mappers.spec.ts`, `apps/cockpit-v2/src/app/core/factory.store.spec.ts`, and `apps/cockpit-v2/src/app/shared/pipes/format.pipes.spec.ts` for real-cost mapping, unknown costs, fallback behavior, live tokens, aggregation, and lower-bound formatting.

To verify the change, run:

```sh
pnpm nx test cockpit-v2
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```

No backend Kotlin changes or factory API service changes are included in this change.
