# Plan: Migrate Factory Delivery domain to TypeScript (tranche 8)

## Objective

Migrate the entire Factory **delivery** subsystem from legacy `.mjs` files under
`factory/lib/delivery-*.mjs` to TypeScript sources under `factory/src/`, following the
**exact same pattern** already used for tranches 1–7 (workflow, evidence, interaction,
agent-attempt, oracle, work-unit-environment). The migration MUST be 100%
behaviorally backward-compatible: identical error codes, identical persisted data
formats, identical validation regexes, identical HTTP shapes, identical exports.

**Scope guard:** only files under `factory/` may be modified. Do NOT touch `agentos/**`.

## Reference pattern (READ FIRST)

This work is a mechanical repeat of the tranche-7 migration. Study these already-migrated
files as the template for style, structure, typing, and shim conventions:

- Domain example: `factory/src/domain/environment/work-unit-environment.ts`
- Store adapter example: `factory/src/adapters/persistence/work-unit-environment-store.ts`
- Application/controller example: `factory/src/application/environment/work-unit-environment-controller.ts`
- Entrypoint: `factory/src/entrypoints/factory-operational.ts` (bottom section)
- Shim example: `factory/lib/work-unit-environment.mjs` (thin re-export from `../runtime/factory-operational.mjs`)
- Toolchain build: `factory/toolchain/build.mjs`, run via `cd factory/toolchain && npm run build`
- Operational test suite: `factory/tests/typescript-factory-operational.mjs` (tranche-7 block starts ~line 256)

Also read the tranche-7 spec `specs/8635a59f_migrate_work_environment_tranche_7.md` for
step framing.

## The 12 legacy files to migrate

Current sources (in `factory/lib/`), with their public exports:

| Legacy `.mjs` | Public exports |
|---|---|
| `delivery-definition.mjs` | `DELIVERY_DEFINITION_SCHEMA_VERSION`, `DELIVERY_STAGES`, `DELIVERY_EVIDENCE_KINDS`, `validateDeliveryDefinition`, `hashDeliveryDefinition`, `defaultDeliveryDefinition` |
| `delivery-policy.mjs` | `DELIVERY_INITIAL_STAGE`, `validateDeliveryPromotionRequest`, `deliveryScopeHash`, `deliverySemanticHash`, `evaluateDeliveryPromotion`, `applyDeliveryPromotion` |
| `delivery-operation-definition.mjs` | `DELIVERY_OPERATION_KINDS`, `DELIVERY_OPERATION_STATES`, `DELIVERY_OPERATION_ERROR_CODES`, `canonicalDeliveryHash`, `normalizeDeliveryOperationRequest`, `deriveDeliveryOperationIdentity`, `validateDeliveryOperationTransition`, `validateDeliveryOperationRecord` |
| `delivery-operation-policy.mjs` | `evaluateDeliveryOperationPolicy`, `resolveDeliveryVerificationRequest` |
| `delivery-store.mjs` | `DeliveryStore` |
| `delivery-evidence-store.mjs` | `validateDeliveryEvidence`, `DeliveryEvidenceStore` |
| `delivery-target-registry.mjs` | `DeliveryTargetRegistry`, `unavailableDeliveryTargetRegistry` |
| `delivery-git-control-plane.mjs` | `DeliveryGitControlPlane` |
| `delivery-pr-adapter.mjs` | `DeliveryPullRequestAdapter` |
| `delivery-deployment-adapter.mjs` | `DELIVERY_ADAPTER_OUTCOMES`, `normalizeDeliveryAdapterOutcome`, `DeliveryDeploymentAdapter`, `DeliveryVerificationAdapter`, `UnconfiguredDeliveryDeploymentAdapter`, `UnconfiguredDeliveryVerificationAdapter` |
| `delivery-controller.mjs` | `DeliveryController`, `handleDeliveryRequest` |
| `delivery-operation-controller.mjs` | `DeliveryOperationController` |

Internal dependency graph (informs migration order and import rewrites):
- `delivery-definition` → `node:crypto` only (leaf, pure)
- `delivery-operation-definition` → `node:crypto` only (leaf, pure)
- `delivery-operation-policy` → pure (no local imports) (pure)
- `delivery-policy` → `node:crypto`, `delivery-definition` (pure)
- `delivery-target-registry` → `delivery-operation-definition` (uses `canonicalDeliveryHash`)
- `delivery-evidence-store` → `node:crypto`, `node:fs/promises`, `node:path` (adapter)
- `delivery-store` → `node:crypto`, `node:fs/promises`, `node:path`, `delivery-policy`, `delivery-operation-definition` (adapter)
- `delivery-git-control-plane` → `node:crypto`, `node:fs/promises`, `node:path`, `createExecFileRunner` from `git-worktree.mjs` (adapter)
- `delivery-pr-adapter` → (adapter)
- `delivery-deployment-adapter` → (adapter)
- `delivery-controller` → `delivery-definition`, `delivery-policy` (application; other collaborators injected via constructor)
- `delivery-operation-controller` → `delivery-operation-definition`, `delivery-operation-policy` (application)

## Target TypeScript layout

### 1. Pure domain — `factory/src/domain/delivery/`

These files MUST contain pure logic only — **no `node:fs`, no HTTP, no AgentOS, no Git CLI**.
`node:crypto` (createHash / randomUUID) is acceptable as it is used for hashing/id-generation
in the current pure files and mirrors existing domain files.

- `factory/src/domain/delivery/delivery-definition.ts`
  Port from `lib/delivery-definition.mjs`. Preserve the 5 stages **exactly**:
  `DELIVERY_STAGES = ['implementation-ready', 'artifact-ready', 'release-approved', 'deployed', 'production-verified']`.
  Keep `DELIVERY_DEFINITION_SCHEMA_VERSION`, `DELIVERY_EVIDENCE_KINDS`, `validateDeliveryDefinition`,
  `hashDeliveryDefinition`, `defaultDeliveryDefinition`. Add explicit TS types/interfaces
  (`DeliveryDefinition`, `DeliveryStage`, validation result shapes).
- `factory/src/domain/delivery/delivery-policy.ts`
  Port from `lib/delivery-policy.mjs`. Keep `DELIVERY_INITIAL_STAGE`, `validateDeliveryPromotionRequest`,
  `deliveryScopeHash`, `deliverySemanticHash`, `evaluateDeliveryPromotion`, `applyDeliveryPromotion`.
  Import `DELIVERY_STAGES` from `./delivery-definition.js`.
- `factory/src/domain/delivery/delivery-operation-definition.ts`
  Port from `lib/delivery-operation-definition.mjs`. Keep `DELIVERY_OPERATION_KINDS`,
  `DELIVERY_OPERATION_STATES`, `DELIVERY_OPERATION_ERROR_CODES`, `canonicalDeliveryHash`,
  `normalizeDeliveryOperationRequest`, `deriveDeliveryOperationIdentity`,
  `validateDeliveryOperationTransition`, `validateDeliveryOperationRecord`.
- `factory/src/domain/delivery/delivery-operation-policy.ts`
  Port from `lib/delivery-operation-policy.mjs`. Keep `evaluateDeliveryOperationPolicy`,
  `resolveDeliveryVerificationRequest`.

Preserve exactly: all regexes (`UUID`, `SAFE`, `SHA`, `HASH`), the `canonical()` /
`canonicalDeliveryHash()` serialization ordering (persisted-hash stability), error code
strings, and returned object shapes.

### 2. Persistence adapters — `factory/src/adapters/persistence/`

- `factory/src/adapters/persistence/delivery-store.ts`
  Port `DeliveryStore` from `lib/delivery-store.mjs`. Uses `node:crypto`,
  `node:fs/promises`, `node:path`. Import the domain helpers
  (`deliveryScopeHash`, `deliverySemanticHash`, `evaluateDeliveryPromotion`,
  `applyDeliveryPromotion`) from `../../domain/delivery/delivery-policy.js` and
  (`normalizeDeliveryOperationRequest`, `deriveDeliveryOperationIdentity`,
  `validateDeliveryOperationRecord`, `validateDeliveryOperationTransition`) from
  `../../domain/delivery/delivery-operation-definition.js`. Preserve atomic-write
  semantics, journaling/recovery, rollback request handling, fault-injection hooks,
  path containment, and every error code exactly.
- `factory/src/adapters/persistence/delivery-evidence-store.ts`
  Port `validateDeliveryEvidence` and `DeliveryEvidenceStore` from
  `lib/delivery-evidence-store.mjs`. Uses `node:crypto`, `node:fs/promises`, `node:path`.

Do NOT add these to `factory/src/adapters/persistence/index.ts` unless the tranche-7
pattern did so for its store — it did NOT (work-unit-environment-store is exported
directly from the entrypoint, not via the persistence barrel). Follow that: export the
delivery stores from the entrypoint file directly (see step 4), not via `index.ts`,
to avoid changing the barrel's curated surface.

### 3. Delivery control-plane / adapters — `factory/src/adapters/delivery/`

- `factory/src/adapters/delivery/delivery-git-control-plane.ts`
  Port `DeliveryGitControlPlane` from `lib/delivery-git-control-plane.mjs`. Uses
  `node:crypto`, `node:fs/promises` (`realpath`), `node:path` (`isAbsolute`). The legacy
  file imports `createExecFileRunner` from `./git-worktree.mjs`, which is NOT migrated and
  cannot be bundled from `.mjs`. Replicate the runner inline in this TS file exactly as
  `git-worktree.mjs` defines it:
  ```ts
  import { execFile } from 'node:child_process'
  import { promisify } from 'node:util'
  const execute = promisify(execFile)
  export function createDeliveryExecFileRunner() {
    return async (file, args, o = {}) => {
      try {
        const r = await execute(file, args, { cwd: o.cwd, encoding: 'utf8' })
        return { exitCode: 0, stdout: r.stdout ?? '', stderr: r.stderr ?? '' }
      } catch (e) {
        return { exitCode: Number.isInteger(e.code) ? e.code : 1, stdout: e.stdout ?? '', stderr: e.stderr ?? '' }
      }
    }
  }
  ```
  Use it as the constructor default `runner`. This keeps the adapter self-contained and
  behavior-identical (the runner is a tiny wrapper). Keep the runner un-exported from the
  public surface unless a test imports it (it does not — tests inject their own runner).
- `factory/src/adapters/delivery/delivery-pr-adapter.ts`
  Port `DeliveryPullRequestAdapter` from `lib/delivery-pr-adapter.mjs`.
- `factory/src/adapters/delivery/delivery-deployment-adapter.ts`
  Port `DELIVERY_ADAPTER_OUTCOMES`, `normalizeDeliveryAdapterOutcome`,
  `DeliveryDeploymentAdapter`, `DeliveryVerificationAdapter`,
  `UnconfiguredDeliveryDeploymentAdapter`, `UnconfiguredDeliveryVerificationAdapter`
  from `lib/delivery-deployment-adapter.mjs`.
- `factory/src/adapters/delivery/delivery-target-registry.ts`
  Port `DeliveryTargetRegistry`, `unavailableDeliveryTargetRegistry` from
  `lib/delivery-target-registry.mjs`. Import `canonicalDeliveryHash` from
  `../../domain/delivery/delivery-operation-definition.js`.

### 4. Application controllers — `factory/src/application/delivery/`

- `factory/src/application/delivery/delivery-controller.ts`
  Port `DeliveryController` and `handleDeliveryRequest` from `lib/delivery-controller.mjs`.
  Import `hashDeliveryDefinition`, `validateDeliveryDefinition` from
  `../../domain/delivery/delivery-definition.js` and `validateDeliveryPromotionRequest`
  from `../../domain/delivery/delivery-policy.js`. Collaborators (`store`, `evidenceStore`,
  `environmentController`, `workflowStore`, `git`, `pullRequests`, `definition`,
  `trustedConfiguration`) are injected via the constructor — do not hard-wire them.
  Preserve exact HTTP path matching, status codes, error codes, and JSON response shapes.
- `factory/src/application/delivery/delivery-operation-controller.ts`
  Port `DeliveryOperationController` from `lib/delivery-operation-controller.mjs`.
  Import `canonicalDeliveryHash`, `normalizeDeliveryOperationRequest` from
  `../../domain/delivery/delivery-operation-definition.js` and
  `evaluateDeliveryOperationPolicy`, `resolveDeliveryVerificationRequest` from
  `../../domain/delivery/delivery-operation-policy.js`.

### 5. Entrypoint export — `factory/src/entrypoints/factory-operational.ts`

Append a new section (mirroring the tranche-7 block at the bottom of the file) exporting
every migrated module:
```ts
// --------------------------------------------------------------------------
// Delivery (tranche 8): pure domain, file-backed stores, control-plane adapters
// and trusted application controllers.
//
// Le domaine (`domain/delivery/*`) ne porte aucune dépendance node:fs/HTTP/Git ;
// les stores, adaptateurs et contrôleurs vivent dans adapters/ et application/.
// Les façades `factory/lib/delivery-*.mjs` réexportent la surface ci-dessous.
// --------------------------------------------------------------------------
export * from '../domain/delivery/delivery-definition.js'
export * from '../domain/delivery/delivery-policy.js'
export * from '../domain/delivery/delivery-operation-definition.js'
export * from '../domain/delivery/delivery-operation-policy.js'
export * from '../adapters/persistence/delivery-store.js'
export * from '../adapters/persistence/delivery-evidence-store.js'
export * from '../adapters/delivery/delivery-target-registry.js'
export * from '../adapters/delivery/delivery-git-control-plane.js'
export * from '../adapters/delivery/delivery-pr-adapter.js'
export * from '../adapters/delivery/delivery-deployment-adapter.js'
export * from '../application/delivery/delivery-controller.js'
export * from '../application/delivery/delivery-operation-controller.js'
```
**Watch for export-name collisions** with existing exports (e.g. `canonicalDeliveryHash`
is used by both operation-definition and target-registry — target-registry imports it,
does not re-export it, so `export *` is safe). If `tsc` reports an ambiguous re-export,
switch that module to a named `export { ... }` list. Verify with the typecheck step.

### 6. Rebuild the operational bundle

```
cd factory/toolchain && npm run build
```
This regenerates `factory/runtime/factory-operational.mjs` and
`factory/dist/factory-operational/factory-operational.meta.json`. Also run the typecheck:
```
cd factory/toolchain && npm run typecheck
```

### 7. Convert the 12 legacy `.mjs` files to stateless re-export shims

Replace the body of each `factory/lib/delivery-*.mjs` with a thin re-export from
`../runtime/factory-operational.mjs` (same style as `lib/work-unit-environment.mjs`).
Re-export ONLY the names that file currently exports (see the table above), preserving the
public surface each file offers to its importers. Example for `delivery-definition.mjs`:
```js
// Stateless compatibility facade. Delivery definition vocabulary, stages,
// evidence kinds and validations live only in the generated operational bundle,
// built from TypeScript source factory/src/domain/delivery/delivery-definition.ts.
export {
  DELIVERY_DEFINITION_SCHEMA_VERSION,
  DELIVERY_STAGES,
  DELIVERY_EVIDENCE_KINDS,
  validateDeliveryDefinition,
  hashDeliveryDefinition,
  defaultDeliveryDefinition,
} from '../runtime/factory-operational.mjs'
```
Do this for all 12 files, matching each file's exact export list.

**Important:** `factory/lib/git-worktree.mjs` currently defines `createExecFileRunner` used
by `delivery-git-control-plane.mjs`. After the shim conversion, `delivery-git-control-plane.mjs`
no longer imports it (the TS adapter has its own inline runner). Leave `git-worktree.mjs`
untouched — it is out of tranche-8 scope and still used elsewhere.

### 8. Extend the operational test suite

Add a tranche-8 delivery block to `factory/tests/typescript-factory-operational.mjs`,
mirroring the tranche-7 block (~lines 256–347). It must:
- Assert the operational bundle module exports every delivery name (functions, classes,
  constants) listed in the table above.
- Assert each `.mjs` facade delegates with **strict identity** (`assert.equal(facade[name], module[name], ...)`)
  for all 12 files.
- Assert the metafile `inputs` include each new TS source **exactly once**
  (`domain/delivery/*.ts`, `adapters/persistence/delivery-store.ts`,
  `adapters/persistence/delivery-evidence-store.ts`, `adapters/delivery/*.ts`,
  `application/delivery/*.ts`).
- Exercise a minimal real behavior slice: e.g. `validateDeliveryDefinition(defaultDeliveryDefinition())`
  returns ok; `DELIVERY_STAGES` equals the exact 5-stage array; construct a `DeliveryStore`
  against a temp dir and confirm `initialize()`/basic read works; `normalizeDeliveryOperationRequest`
  round-trips. Keep assertions aligned with existing legacy test expectations.

### 9. Run the full verification set

Every command's success is judged by exit status 0.
```
cd factory/toolchain && npm run typecheck
cd factory/toolchain && npm run build
cd factory/toolchain && npm run test:operational      # runs tests/typescript-factory-operational.mjs
node factory/tests/test-delivery-phase9.mjs
node factory/tests/test-delivery-api-phase9.mjs
node factory/tests/test-delivery-operation-contract-phase10e.mjs
node factory/tests/test-delivery-operation-policy-phase10e.mjs
node factory/tests/test-delivery-operation-recovery-phase10e.mjs
node factory/tests/test-delivery-operation-store-phase10e.mjs
node factory/tests/test-delivery-operation-api-lot2.mjs
node factory/tests/test-delivery-rollback-store-lot2.mjs
```
Also confirm nothing else regressed — the metrics/projection modules import delivery
symbols. Spot-run:
```
node factory/tests/test-factory-operational-metrics.mjs
node factory/tests/test-namespace-operational-metrics-phase10d.mjs
```

## Verification checklist

- [ ] All 4 domain files are pure (grep them: no `node:fs`, no `node:http`, no `child_process`,
      no `git-worktree`, no AgentOS imports). `node:crypto` allowed.
- [ ] `cd factory/toolchain && npm run typecheck` exits 0.
- [ ] `cd factory/toolchain && npm run build` exits 0 and regenerates
      `factory/runtime/factory-operational.mjs` + meta.json.
- [ ] All 12 `factory/lib/delivery-*.mjs` files are thin re-exports from
      `../runtime/factory-operational.mjs` with no local logic.
- [ ] `DELIVERY_STAGES` is exactly `['implementation-ready','artifact-ready','release-approved','deployed','production-verified']`.
- [ ] `npm run test:operational` passes with the new delivery assertions.
- [ ] All delivery test scripts (step 9) exit 0.
- [ ] Metrics/projection spot-tests exit 0.
- [ ] No files outside `factory/` were modified. No scratch files left in the repo tree.

## Commit

Conventional commit, title exactly:
```
refactor(factory): migrate delivery domain to TypeScript (tranche 8)
```

## Notes / risks

- **Hash stability is critical.** `hashDeliveryDefinition`, `deliveryScopeHash`,
  `deliverySemanticHash`, `canonicalDeliveryHash` produce persisted hashes that tests assert
  byte-for-byte. Port the `canonical()` key-sorting/undefined-filtering logic verbatim.
- **Error codes are asserted literally** by the phase9/phase10e tests. Do not rename or
  reformat any error-code string.
- **Store persistence formats** (journal entries, snapshot JSON, rollback records) must be
  emitted identically — port the write/serialize code unchanged in behavior.
- If `export *` produces a duplicate-export TS error, replace the offending `export *` with
  an explicit named `export { ... } from ...` list for that module only.
- Keep the git-control-plane inline exec runner private (not part of the public surface) so
  no new symbol leaks into the bundle's export contract.
