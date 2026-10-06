# Execution Plan: PostgreSQL Work-Environment and Delivery Persistence Adapters & Conformance Suite

## Objective
Finalize, review, validate, and commit the newly created PostgreSQL persistence adapters for `work-environment` and `delivery`, as well as their cross-adapter conformance test suite.

## Perimeter Files
Only the following files are part of this change perimeter:
- `factory/src/adapters/persistence/sql/sql-work-environment-repository.ts`
- `factory/src/adapters/persistence/sql/sql-delivery-repository.ts`
- `factory/tests/test-conformance-work-environment-delivery.mjs`

## Verification Strategy & Test Commands
1. Run target conformance suite:
   ```bash
   node factory/tests/test-conformance-work-environment-delivery.mjs
   ```
   Expect: Exit code 0 with all tests passing (6 passed, 0 failed).

2. Run existing SQL adapter tests to ensure zero regressions:
   ```bash
   node factory/tests/test-sql-repository-ports-adapters.mjs
   ```
   Expect: Exit code 0 with all tests passing (21 passed, 0 failed).

3. Run Nx affected test suite relative to baseline:
   ```bash
   pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
   ```

## Detailed Implementation & Review Plan

### Step 1: Quality & Code Review
- Inspect `factory/src/adapters/persistence/sql/sql-work-environment-repository.ts`:
  - Verify compliance with TypeScript strict mode, explicit return types, imports formatting, and standard error code mapping (`INVALID_NAMESPACE`, `INVALID_ENVIRONMENT`, `NOT_FOUND`, `REVISION_CONFLICT`, `INVALID_TRANSITION`, `CORRUPT_STORAGE`).
  - Verify that optimistic concurrency locking (revision increments) and transactional operations (`withTransaction`) match the repository port interface contract.
- Inspect `factory/src/adapters/persistence/sql/sql-delivery-repository.ts`:
  - Verify idempotency hashing, delivery policy evaluation, promotion, operation lifecycle transitions, and rollback request handling.
  - Verify transactional isolation for multi-row writes (`deliveries` snapshot + `delivery_journal`).
- Inspect `factory/tests/test-conformance-work-environment-delivery.mjs`:
  - Verify scenario coverage for both filesystem and SQL implementations.
  - Verify exact parity assertion across filesystem and SQL backends for work-environment and delivery domain operations.

### Step 2: Verification Execution
- Execute `node factory/tests/test-conformance-work-environment-delivery.mjs` and check output.
- Execute `node factory/tests/test-sql-repository-ports-adapters.mjs` and check output.
- Check git status (`git status --porcelain`) to confirm that only the 3 specified files are modified/untracked.

### Step 3: Git Commit
- Stage and commit the 3 target files following conventional commit standards:
  `feat(factory): add postgresql work-environment and delivery persistence adapters with conformance suite`

## Notes for Next Agent
- All 3 files (`sql-work-environment-repository.ts`, `sql-delivery-repository.ts`, and `test-conformance-work-environment-delivery.mjs`) have already been authored and verified to pass tests cleanly.
- Perform code review checks on error handling, typing, and single-responsibility principles as requested by prompt instructions before committing.
- Keep strict perimeter boundaries: do not stage or modify any files outside the specified three.
