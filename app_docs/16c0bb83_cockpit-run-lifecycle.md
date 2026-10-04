# cockpit-v2 run lifecycle core

Implemented the workflow lifecycle API and store behavior needed to represent active and removed runs, stop real attempts, and soft-remove/restore workflows without inventing backend state.

## What changed

- `apps/cockpit-v2/src/app/core/factory-api.service.ts` now exposes:
  - `removeWorkflow()`: `DELETE /api/factory/workflows/:id`
  - `restoreWorkflow()`: `POST /api/factory/workflows/:id/restore`
  - `purgeWorkflow()`: `POST /api/factory/workflows/:id/purge`
  Workflow IDs are URL-encoded, and the methods reuse the existing request helpers, including namespace and correlation handling.
- `apps/cockpit-v2/src/app/core/models.ts` adds optional `activeAttemptId` and `activeAttemptRevision` fields to `SessionDetail`.
- `apps/cockpit-v2/src/app/core/mappers.ts` derives those fields from an authorized `cancel_attempt` action first, or otherwise from a real running attempt. Missing backend data remains missing.
- `apps/cockpit-v2/src/app/core/factory.store.ts` loads active and removed workflow lists concurrently. Removed snapshots are merged into `sandboxes` with `status: 'destroyed'`; only active snapshots are enriched through the live timing/evidence/metrics/interactions/attempts/actions endpoints. Existing `showDestroyed`, `visibleSandboxes`, and `destroyedSandboxes` signals remain the visibility mechanism.
- The store still degrades to empty authoritative state when loading fails, and an individual active/removed list failure is treated as an empty half so the other list can remain visible. No mock fleet is introduced by `load()`.
- `FactoryStore.stop()` delegates to `cancelAttempt()` only when the loaded session supplies a real active attempt ID, passing its real revision when available and `reason: 'stop'`. It does nothing when no attempt can be resolved. `remove()` and `restore()` call the new API methods, reload on success, and silently ignore failures.

## Tests and verification

`apps/cockpit-v2/src/app/core/factory-api.service.spec.ts` covers encoded routes, HTTP methods, request bodies, namespace/correlation metadata, response unwrapping, and normalized failures for all three lifecycle endpoints.

`apps/cockpit-v2/src/app/core/factory.store.spec.ts` covers active-plus-removed loading, destroyed/visible sandbox behavior, partial and total load degradation, stopping from an authorized action or running attempt, the no-fabrication stop case, and successful/failing remove and restore actions.

Verify with:

```sh
pnpm nx test cockpit-v2
```

The lifecycle plan and verification checklist are also captured in `specs/16c0bb83_cockpit_run_lifecycle_core.md`.
