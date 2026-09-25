# Plan: Domain Tranches 1-4 Complete Migration & Verification

## Context & Overview
The implementation for domain tranches 1 to 4 has been created under `factory/src/domain/`:
1. `factory/src/domain/workflow/workflow-definition.ts` (Tranche 1)
2. `factory/src/domain/workflow/workflow-instance.ts` (Tranche 2)
3. `factory/src/domain/workflow/workflow-transition-policy.ts` (Tranche 3)
4. `factory/src/domain/evidence/workflow-evidence.ts` (Tranche 4)
5. `factory/src/domain/interaction/workflow-human-interaction.ts` (Tranche 4 / Human Interactions)

`factory/src/entrypoints/factory-operational.ts` re-exports all domain modules, and `factory/toolchain` builds `factory/runtime/factory-operational.mjs`.
The legacy facade modules in `factory/lib/*.mjs`:
- `factory/lib/workflow-definition.mjs`
- `factory/lib/workflow-instance.mjs`
- `factory/lib/workflow-transition-policy.mjs`
- `factory/lib/workflow-evidence.mjs`
- `factory/lib/workflow-human-interaction-store.mjs`

have been updated to re-export from `../runtime/factory-operational.mjs`.

The objective of this task is to verify code quality/purity, rebuild the bundle, confirm zero regressions across typecheck and factory domain/workflow tests, and cleanly commit the work.

---

## Detailed Step-by-Step Instructions for Builder

### Step 1: Verify Domain Purity Rules (Acceptance Criteria 2)
Ensure `factory/src/domain/` contains pure domain code:
- Check that files under `factory/src/domain/` do NOT import `fs`, `http`, `agentos`, or `git` (or node builtins/modules that violate domain isolation).
- Confirm files exist and are strict TypeScript:
  - `factory/src/domain/workflow/workflow-definition.ts`
  - `factory/src/domain/workflow/workflow-instance.ts`
  - `factory/src/domain/workflow/workflow-transition-policy.ts`
  - `factory/src/domain/evidence/workflow-evidence.ts`
  - `factory/src/domain/interaction/workflow-human-interaction.ts`

### Step 2: Rebuild Toolchain & Typecheck (Acceptance Criteria 4)
1. Run typecheck in toolchain:
   ```bash
   cd factory/toolchain && npm run typecheck
   ```
2. Rebuild operational bundle:
   ```bash
   cd factory/toolchain && npm run build
   ```

### Step 3: Run Factory Domain Tests & Validate Domain Integrity
Run domain tests that verify the tranches and legacy facade re-exports:
```bash
node --test factory/tests/test-workflow-definition.mjs
node --test factory/tests/test-workflow-definition-api.mjs
node --test factory/tests/test-workflow-instance.mjs
node --test factory/tests/test-workflow-start-api.mjs
node --test factory/tests/test-workflow-transition-policy.mjs
node --test factory/tests/test-workflow-transition-api.mjs
node --test factory/tests/test-workflow-code-transition.mjs
node --test factory/tests/test-workflow-code-transition-api.mjs
node --test factory/tests/test-workflow-evidence.mjs
node --test factory/tests/test-workflow-evidence-api.mjs
node --test factory/tests/test-workflow-human-interaction-source.mjs
node --test factory/tests/test-workflow-human-interaction-revision.mjs
node --test factory/tests/typescript-factory-operational.mjs
```

Ensure all above test files pass without errors.

### Step 4: Clean Git Stage & Commit (Acceptance Criteria 3)
1. Do NOT touch or delete files under `runs/` or outside `factory/` and `specs/`.
2. Stage modified files in `factory/lib/`, `factory/runtime/`, `factory/src/`, `factory/tests/`, and new files in `factory/src/domain/`:
   ```bash
   git add factory/lib/ factory/runtime/ factory/src/ factory/tests/ test-workflow-instance.mjs test-workflow-start-api.mjs
   git add factory/src/domain/
   ```
   (or explicit file paths)
3. Check `git status` to verify `runs/` and unrelated untracked files are untouched.
4. Commit the implementation with a clear conventional commit message:
   ```bash
   git commit -m "feat(factory): commit domain tranches 1-4 migration to TypeScript contexts"
   ```

---

## Verification Plan

### Automated Checks
- `cd factory/toolchain && npm run typecheck` (Passes with 0 errors)
- `cd factory/toolchain && npm run build` (Succeeds and updates `factory/runtime/factory-operational.mjs`)
- `node --test factory/tests/test-workflow-*.mjs factory/tests/typescript-factory-operational.mjs` (All domain/workflow tests pass)

### Manual Inspection
- Run `git status` before commit to verify no `runs/` files are staged or deleted.
- Inspect `factory/src/domain/` imports to verify no forbidden dependencies (`fs`, `http`, `agentos`, `git`).
