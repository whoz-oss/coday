# Factory persistence — switch to PostgreSQL and rollback

Milestone **B4-T2** makes PostgreSQL a progressively and reversibly authoritative
store for the Factory dashboard persistence, through the composition root
(`factory/dashboard/composition-root.mjs`) and the policy module
(`factory/dashboard/persistence-authority.mjs`).

Nothing here changes the default runtime behaviour: with no configuration the
dashboard keeps the historical **filesystem authority** and writes exactly as it
did before.

---

## 1. Operational overview

| `FACTORY_PERSISTENCE` | `FACTORY_PERSISTENCE_SHADOW` | Authority | Filesystem | PostgreSQL |
|---|---|---|---|---|
| unset / `fs` | unset / `false` | Filesystem | read + write | unused |
| `fs` | `true` | Filesystem | read + write | **read-only**, on every shadowed read |
| `sql` | ignored | PostgreSQL | **never written** | read + write |

* **Filesystem authority (`fs`, default).** Stores are the concrete `.mjs`
  filesystem stores. Zero regression.
* **Shadow read (`fs` + `FACTORY_PERSISTENCE_SHADOW=true`).** Every read on a
  shadowed store is still served *from the filesystem*; the same read is also
  performed against PostgreSQL and the two results are compared with the
  canonical hash (`computeCanonicalHash`). A mismatch is logged as
  `[SHADOW_READ_DISCREPANCY]`; a PostgreSQL/compare failure is logged as
  `[SHADOW_READ_ERROR]`. Neither ever changes the served value or fails the
  request.
* **Writer unique (`sql`).** Store methods that have a SQL repository are served
  by it; the filesystem receives **zero writes**. Operations without a SQL
  adapter fail closed with `PERSISTENCE_OPERATION_NOT_MIGRATED` instead of
  silently falling back to disk.

Tenant scope is fixed at wiring time from `FACTORY_ORGANIZATION_ID` /
`FACTORY_WORKSTREAM_ID` (default `default` / `default`).

Connection settings use the standard `PG*` variables (defaults:
`localhost:5432/coday_factory`, user `factory`, password `factory_dev_pass`).

---

## 2. Prerequisites

```sh
# 1. Start PostgreSQL and apply the Flyway migrations.
docker compose -f factory/infra/docker-compose.yml up -d
docker compose -f factory/infra/docker-compose.yml logs -f flyway   # wait for "Successfully applied"

# 2. The runtime never bundles the driver: install `pg` in the operator
#    environment used to run the dashboard / import.
pnpm add -w pg        # or: npm install pg
```

Sanity check the database:

```sh
docker compose -f factory/infra/docker-compose.yml exec coday-postgres \
  psql -U factory -d coday_factory -c '\dt'
```

---

## 3. Transition procedure

### Step 1 — Seed PostgreSQL from the filesystem (B4-T1 one-shot import)

Run the one-shot import against the same `FACTORY_DATA_ROOT` the dashboard uses.
It is idempotent and verifies count + canonical-hash fidelity:

```sh
FACTORY_DATA_ROOT="$HOME/.coday/factory" \
PGHOST=localhost PGPORT=5432 PGDATABASE=coday_factory \
PGUSER=factory PGPASSWORD=factory_dev_pass \
node --experimental-strip-types factory/src/entrypoints/import-one-shot.ts
```

Exit code `0` means every context is `ok`. Any discrepancy is printed as one
JSON line per context; fix or re-run before switching.

### Step 2 — Enable shadow reads (filesystem still authoritative)

```sh
FACTORY_PERSISTENCE=fs FACTORY_PERSISTENCE_SHADOW=true \
node factory/dashboard/server.mjs
```

Watch the logs for drift:

```sh
# In the dashboard output / wherever the server logs are collected:
#   [SHADOW_READ_DISCREPANCY] { store, method, key, filesystemHash, sqlHash }
#   [SHADOW_READ_ERROR]       { store, method, message }
```

A quiet run (no `[SHADOW_READ_*]` lines) means PostgreSQL agrees with the
filesystem for the traffic observed. Discrepancies are safe to investigate at
leisure: the filesystem result is still the one served.

### Step 3 — Switch to the writer-unique PostgreSQL authority

```sh
FACTORY_PERSISTENCE=sql \
node factory/dashboard/server.mjs
```

The filesystem is now never written. Confirm it with the manual validation in
section 5.

### Step 4 — Observe

* Reads/writes for the migrated contexts are served by PostgreSQL.
* Unmigrated operations answer with `PERSISTENCE_OPERATION_NOT_MIGRATED`
  (see the coverage matrix below) rather than writing to disk.

---

## 4. Rollback

Rollback is a single environment change; PostgreSQL keeps the data written while
it was authoritative and the filesystem is restored as the authority exactly as
it was before the switch.

```sh
# Emergency revert: drop FACTORY_PERSISTENCE (or set it to fs) and restart.
FACTORY_PERSISTENCE=fs node factory/dashboard/server.mjs
```

Because `fs` is the default, simply unsetting the variable is enough:

```sh
unset FACTORY_PERSISTENCE FACTORY_PERSISTENCE_SHADOW
node factory/dashboard/server.mjs
```

No data is rewritten by a rollback: records already produced remain historical
data on both sides. If the rollback must also re-converge the two stores later,
re-run the one-shot import against the reserved filesystem root.

---

## 5. Manual validation against the containerized PostgreSQL

### 5.1 Prove the filesystem receives no writes under `sql`

```sh
DATA_ROOT="$HOME/.coday/factory"

# Snapshot the tree (path + mtime + size).
find "$DATA_ROOT" -type f -printf '%p %T@ %s\n' | sort > /tmp/fs-before.txt

FACTORY_PERSISTENCE=sql node factory/dashboard/server.mjs &
SERVER_PID=$!
# Exercise the dashboard (start a workflow, record evidence, ...), then:
sleep 5
find "$DATA_ROOT" -type f -printf '%p %T@ %s\n' | sort > /tmp/fs-after.txt
diff /tmp/fs-before.txt /tmp/fs-after.txt && echo "OK: filesystem untouched"
kill "$SERVER_PID"
```

### 5.2 Prove the rows landed in PostgreSQL

```sh
docker compose -f factory/infra/docker-compose.yml exec coday-postgres \
  psql -U factory -d coday_factory -c \
  'SELECT namespace_id, workflow_id, revision FROM workflow_instances ORDER BY workflow_id;'

docker compose -f factory/infra/docker-compose.yml exec coday-postgres \
  psql -U factory -d coday_factory -c \
  'SELECT organization_id, workstream_id, environment_id FROM work_environments;'
```

### 5.3 Re-run the offline proof (no Docker)

The switch/rollback behaviour is covered offline end to end:

```sh
node factory/tests/test-persistence-shadow-and-switch.mjs
node factory/tests/test-persistence-import.mjs            # B4-T1 fidelity
node factory/tests/test-composition-root-source.mjs       # wiring guard
```

---

## 6. Coverage matrix

| Context | Shadow probe (read comparison) | SQL authority (`sql`) |
|---|---|---|
| `workflowProjectionStore` | `read`, `list` | `read`/`get`/`lookup`/`list`/`start`/`transition`/`openHumanCheckpoint`/`resolveHumanCheckpoint`/`remove`/`restore`/`purge` |
| `workUnitEnvironmentStore` | `read`, `list` | `paths`/`read`/`list`/`reserve`/`transition` |
| `deliveryStore` | `read`, `readWithOperations`, `inspectDeliveryOperations` | `read`/`create`/`promote`/`readWithOperations`/`inspectDeliveryOperations`/rollback + operation lifecycles/`updateSnapshot`/`hasIndeterminateOperation` |
| `workflowEvidenceStore` | — | `list`/`record` |
| `workflowHumanInteractionStore` | — | `list`/`events`/`reconcileOpen`/`recordOpen`/`recordTransition` |
| `agentStepResultStore` | — | `issue`/`submit`/`getByAttempt`/`list` |
| `deliveryEvidenceStore` | — | not migrated (no SQL adapter) — fails closed |
| `workflowResumeDispatchStore` | — | not migrated (no SQL adapter) — fails closed |

Notes and known gaps:

* Shadow probes intentionally skip `evidence`/`human-interaction`/`agent-step-result`
  reads: their filesystem journals are keyed by a storage digest while the SQL
  adapters expose a scope-keyed ledger, so a naive comparison would report
  systematic false positives. Their SQL repositories remain wired for `sql`
  authority mode.
* `workflowProjectionStore.publish`/`timing`/`facts`/`listRemoved` and the
  delivery-operation journal helpers are not yet mapped to the instance
  repository and fail closed under `sql`.
* The two contexts without any SQL adapter fail closed rather than writing to
  the filesystem under a PostgreSQL authority.

---

## 7. Log markers

| Marker | Meaning |
|---|---|
| `[SHADOW_READ_DISCREPANCY]` | Filesystem and PostgreSQL canonical hashes differ; the filesystem value was served. |
| `[SHADOW_READ_ERROR]` | The shadow PostgreSQL read or the comparison failed; the filesystem value was served. |
| `PERSISTENCE_OPERATION_NOT_MIGRATED` | `FACTORY_PERSISTENCE=sql` was asked for an operation with no SQL adapter yet. |
