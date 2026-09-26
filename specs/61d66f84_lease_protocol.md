# Plan Jalon C1-T1b: Lease Protocol Domain, Port, SQL Adapter, and Tests

## Architecture & Scope

This plan implements the lease protocol domain, port, SQL adapter, and test suite for Jalon C1-T1b in Coday Factory (`factory/`).

### Strict Boundaries & Constraints
- **Domain**: Pure functional/domain definitions in `factory/src/domain/lease/` (or `factory/src/domain/lease.ts`).
- **Port**: `factory/src/ports/persistence/lease-repository.ts`.
- **SQL Adapter**: `factory/src/adapters/persistence/sql/sql-lease-repository.ts`.
- **In-Memory SQL Client**: Support for sequences / fencing tokens, `SELECT ... FOR UPDATE SKIP LOCKED`, `ORDER BY`, etc. in `factory/tests/support/in-memory-sql-client.mjs` without breaking existing tests.
- **Tests**: `factory/tests/test-lease-protocol.mjs`.

### Strict Prohibitions
- DO NOT touch work-unit / worker adapters (`sql-work-unit-repository.ts`, etc. - reserved for parallel tasks).
- DO NOT modify `index.ts` barrels (reserved for W3).
- DO NOT modify `db.ts` or `unit-of-work.ts`.
- DO NOT touch files under `agentos/**`.
- DO NOT touch database migration files in `factory/infra/migrations/`.

---

## Detailed Component Specifications

### 1. Pure Domain (`factory/src/domain/lease/` or `factory/src/domain/lease.ts`)

Create `factory/src/domain/lease/lease.ts` (and export from `factory/src/domain/lease/index.ts` if creating directory, or single file `factory/src/domain/lease.ts`). Let's use `factory/src/domain/lease/lease.ts` (with `factory/src/domain/lease/index.ts` if needed, or `factory/src/domain/lease.ts` directly - following pattern like `factory/src/domain/agent-attempt/agent-step-attempt.ts`).

#### Types and Interfaces
- `WorkUnitLeaseStatus`: `'active' | 'released' | 'expired'`
- `WorkUnitLease`:
  ```ts
  export interface WorkUnitLease {
    organizationId: string
    workstreamId: string
    workUnitId: string
    leaseId: string
    workerId: string
    environmentId: string | null
    status: WorkUnitLeaseStatus
    fencingToken: number // BIGINT parsed to number (or bigint/string if safe, number is standard for integer seq tokens <= Number.MAX_SAFE_INTEGER)
    acquiredAt: string // ISO string
    leaseExpiresAt: string // ISO string
    heartbeatAt: string // ISO string
    releasedAt: string | null // ISO string
    expiryReason: string | null
    createdAt: string // ISO string
  }
  ```

#### Error Codes & Constants
```ts
export const LEASE_ERROR_CODES = Object.freeze({
  LEASE_FENCED: 'LEASE_FENCED',
  LEASE_NOT_FOUND: 'LEASE_NOT_FOUND',
  LEASE_EXPIRED: 'LEASE_EXPIRED',
  NO_ELIGIBLE_WORK_UNIT: 'NO_ELIGIBLE_WORK_UNIT',
  WORK_UNIT_NOT_FOUND: 'WORK_UNIT_NOT_FOUND',
  INVALID_LEASE_STATE: 'INVALID_LEASE_STATE',
} as const)

export type LeaseErrorCode = (typeof LEASE_ERROR_CODES)[keyof typeof LEASE_ERROR_CODES]
```

#### Custom Error Class
```ts
export class LeaseError extends Error {
  readonly code: LeaseErrorCode
  readonly details: Record<string, unknown>

  constructor(code: LeaseErrorCode, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'LeaseError'
    this.code = code
    this.details = details
  }
}
```

#### Pure Helper Functions
- `isLeaseActive(lease: WorkUnitLease, nowIso?: string): boolean`
- `validateFencingToken(currentFencingToken: number, incomingFencingToken: number): boolean` (throws `LeaseError(LEASE_FENCED)` if `incomingFencingToken < currentFencingToken` or mismatch when required).

---

### 2. Port Interface (`factory/src/ports/persistence/lease-repository.ts`)

Define `LeaseRepository` and request/response options:

```ts
import type { WorkUnitLease } from '../../domain/lease/lease.js'

export interface AcquireLeaseOptions {
  organizationId?: string
  workstreamId?: string
  workerId: string
  environmentId?: string | null
  ttlMs: number
  now?: Date
}

export interface RenewLeaseOptions {
  organizationId?: string
  workstreamId?: string
  workUnitId: string
  leaseId: string
  fencingToken: number
  ttlMs: number
  now?: Date
}

export interface ReleaseLeaseOptions {
  organizationId?: string
  workstreamId?: string
  workUnitId: string
  leaseId: string
  fencingToken?: number
  resultStatus?: 'completed' | 'failed' | 'created'
  now?: Date
}

export interface ExpireLeasesOptions {
  organizationId?: string
  workstreamId?: string
  expiryReason?: string
  now?: Date
}

export interface LeaseRepository {
  acquire(options: AcquireLeaseOptions): Promise<{ lease: WorkUnitLease; workUnitId: string } | null>
  renew(options: RenewLeaseOptions): Promise<WorkUnitLease>
  release(options: ReleaseLeaseOptions): Promise<WorkUnitLease>
  expire(options: ExpireLeasesOptions): Promise<WorkUnitLease[]>
  findByLeaseId(organizationId: string, workstreamId: string, workUnitId: string, leaseId: string): Promise<WorkUnitLease | null>
  findActiveLeaseByWorkUnit(organizationId: string, workstreamId: string, workUnitId: string): Promise<WorkUnitLease | null>
}
```

---

### 3. SQL Adapter (`factory/src/adapters/persistence/sql/sql-lease-repository.ts`)

Implement `SqlLeaseRepository` implementing `LeaseRepository`.

#### Key Design Rules:
- Uses `withTransaction` for ALL protocol mutation methods (`acquire`, `renew`, `release`, `expire`).
- Tenant Scoping: Default `organizationId = 'default'`, `workstreamId = 'default'`.
- Row Mapping: Maps `work_unit_leases` SQL table columns to `WorkUnitLease` JS objects:
  - `fencing_token`: cast `Number(row.fencing_token)`.
  - Date fields (`acquired_at`, `lease_expires_at`, `heartbeat_at`, `released_at`, `created_at`): formatted as ISO string (or via `new Date(row.x).toISOString()`).

#### Method Details:

##### `acquire(options)`:
Inside transaction:
1. `SELECT work_unit_id, revision, attempt_count FROM work_units WHERE organization_id = $1 AND workstream_id = $2 AND status IN ('created', 'failed') AND (not_before IS NULL OR not_before <= $3) ORDER BY priority DESC, created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED`
   *(If no row returned, return `null`)*
2. Read next sequence value: `SELECT nextval('work_unit_lease_fencing_seq') AS fencing_token`
3. Generate `leaseId` (e.g. `lease_${crypto.randomUUID()}` or timestamp-based ID).
4. `INSERT INTO work_unit_leases (organization_id, workstream_id, work_unit_id, lease_id, worker_id, environment_id, status, fencing_token, acquired_at, lease_expires_at, heartbeat_at, created_at) VALUES ($1, $2, $3, $4, $5, $6, 'active', $7, $8, $9, $8, $8)`
5. Update `work_units`: `UPDATE work_units SET status = 'running', attempt_count = attempt_count + 1, revision = revision + 1, updated_at = $8 WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3`
6. Return `{ lease: WorkUnitLease, workUnitId }`.

##### `renew(options)`:
Inside transaction:
1. `SELECT * FROM work_unit_leases WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3 AND lease_id = $4 FOR UPDATE`
2. If not found: throw `LeaseError(LEASE_NOT_FOUND)`.
3. If `status !== 'active'`: throw `LeaseError(INVALID_LEASE_STATE)`.
4. If `Number(row.fencing_token) !== options.fencingToken`: throw `LeaseError(LEASE_FENCED)`.
5. Update: `UPDATE work_unit_leases SET lease_expires_at = $5, heartbeat_at = $6 WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3 AND lease_id = $4`
6. Return updated `WorkUnitLease`.

##### `release(options)`:
Inside transaction:
1. `SELECT * FROM work_unit_leases WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3 AND lease_id = $4 FOR UPDATE`
2. If not found: throw `LeaseError(LEASE_NOT_FOUND)`.
3. If options.fencingToken provided and `options.fencingToken < Number(row.fencing_token)`: throw `LeaseError(LEASE_FENCED)`.
4. If `row.status !== 'active'`: (If already released/expired, return existing lease or handle appropriately, throw `INVALID_LEASE_STATE` if invalid transition).
5. Update lease: `UPDATE work_unit_leases SET status = 'released', released_at = $now WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3 AND lease_id = $4`
6. Update work unit status (e.g., to `resultStatus` || `'completed'`):
   `UPDATE work_units SET status = $status, revision = revision + 1, updated_at = $now WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3`
7. Return updated `WorkUnitLease`.

##### `expire(options)`:
Inside transaction:
1. `SELECT * FROM work_unit_leases WHERE organization_id = $1 AND workstream_id = $2 AND status = 'active' AND lease_expires_at < $now FOR UPDATE`
2. For each expired lease:
   - `UPDATE work_unit_leases SET status = 'expired', released_at = $now, expiry_reason = $reason WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3 AND lease_id = $4`
   - Reset work unit to `'created'` (or retryable state):
     `UPDATE work_units SET status = 'created', revision = revision + 1, updated_at = $now WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3`
3. Return list of expired `WorkUnitLease` objects.

##### `findByLeaseId` and `findActiveLeaseByWorkUnit`:
Read-only helpers to query leases.

---

### 4. Support In-Memory SQL Client (`factory/tests/support/in-memory-sql-client.mjs`)

Enhance `createInMemorySqlClient` in `factory/tests/support/in-memory-sql-client.mjs` to support:
- `SELECT nextval('work_unit_lease_fencing_seq')` -> sequence counter starting at 1, incrementing by 1.
- `FOR UPDATE` / `SKIP LOCKED` stripping in regex parsers.
- `ORDER BY priority DESC, created_at ASC` or similar basic sorting in `select` logic if needed for `acquire`.
- `LIMIT x` clause parsing in `select`.
- `status IN ('created', 'failed')` and `(not_before IS NULL OR not_before <= $x)` expression handling in `matches()`.
- Ensure all existing tests (`test-sql-unit-of-work.mjs`, `test-v7-migration-schema.mjs`, `test-repository-ports-adapters.mjs`, etc.) continue to pass 100%.

---

### 5. Protocol Tests (`factory/tests/test-lease-protocol.mjs`)

Create test file `factory/tests/test-lease-protocol.mjs`:
1. **Header Documentation**:
   - Explanation of how to run against real PostgreSQL (`docker-compose up -d` in `factory/infra` -> sets `DATABASE_URL` or `PGHOST`/`PGPORT`).
   - Explanation of in-memory execution fallback when PG is not present.
2. **Test Cases**:
   - `acquire`: acquires highest priority eligible `work_unit`, sets status to `running`, increments `attempt_count`, generates `fencing_token`.
   - `acquire` exclusion / no eligible: returns `null` when no work units are available or all are locked/running.
   - `renew` (heartbeat): extends `lease_expires_at` and updates `heartbeat_at`.
   - `renew` fencing check: rejects stale or mismatched `fencing_token` with `LEASE_FENCED`.
   - `release`: sets lease status to `'released'`, updates `work_unit` to `'completed'` (or specified status).
   - `release` fencing check: rejects stale token with `LEASE_FENCED`.
   - `expire` (sweep): finds active leases past `lease_expires_at`, marks them `'expired'`, sets `expiry_reason`, resets associated `work_units` to `'created'`.
   - **PostgreSQL / Concurrency Integration Test**:
     - Check if `process.env.DATABASE_URL` or `process.env.PGHOST` is set. If present, run strict concurrency / `SKIP LOCKED` tests with parallel `acquire` calls on multiple workers ensuring no worker gets the same `work_unit` or `fencing_token`. If absent, gracefully skip or run simulated in-memory parallel test.

---

## Plan Execution Steps

1. Create Domain files: `factory/src/domain/lease/lease.ts`.
2. Create Port: `factory/src/ports/persistence/lease-repository.ts`.
3. Update `factory/tests/support/in-memory-sql-client.mjs` with sequence, ORDER BY, LIMIT, IN clause, FOR UPDATE SKIP LOCKED support.
4. Create SQL Adapter: `factory/src/adapters/persistence/sql/sql-lease-repository.ts`.
5. Create Test suite: `factory/tests/test-lease-protocol.mjs`.
6. Run tests:
   - `node factory/tests/test-lease-protocol.mjs`
   - `node factory/tests/test-sql-unit-of-work.mjs`
   - `node factory/tests/test-v7-migration-schema.mjs`
   - `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
