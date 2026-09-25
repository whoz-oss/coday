# Implementation Plan - Flyway Migration V2__tenant_and_membership.sql and Schema Validation Test

## User Request Summary
Create `factory/infra/migrations/V2__tenant_and_membership.sql` defining the 11 tenant, structure, identity, role, membership, and repository tables for Jalon B2, along with tests in `factory/tests/test-v2-migration-schema.mjs` to validate schema execution, foreign key constraints, composite unique keys, revision constraints, and triggers.

## Scope & Constraints
- Allowed to create/modify:
  - `factory/infra/migrations/V2__tenant_and_membership.sql`
  - `factory/tests/test-v2-migration-schema.mjs`
  - `factory/infra/README.md` (optional, for documentation updates)
- STRICTLY forbidden to modify:
  - `factory/infra/migrations/V1__init_workflow_pilot_schema.sql`
  - `factory/runtime/factory-operational.mjs`
  - `agentos/**`
  - Any existing core JS/TS code under `factory/src/`, `factory/lib/`, or `factory/workflows/`

## Proposed Changes

### 1. File: `factory/infra/migrations/V2__tenant_and_membership.sql`
Create the Migration V2 SQL file containing definitions for all 11 required tables and trigger infrastructure:

#### Trigger Infrastructure
Define a reusable `set_updated_at` trigger function if not exists, or reuse/adapt `set_workflow_instance_updated_at` pattern.
Standard function: `set_updated_at()` returning `TRIGGER`.
Attach `BEFORE UPDATE` triggers to all tables with an `updated_at` column (`organizations`, `workstreams`, `squads`, `principals`, `service_identities`, `roles`, `repositories`).

#### Table 1: `organizations`
- `organization_id VARCHAR(255) NOT NULL` (PRIMARY KEY)
- `name VARCHAR(255) NOT NULL`
- `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
- `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`

#### Table 2: `workstreams`
- `organization_id VARCHAR(255) NOT NULL`
- `workstream_id VARCHAR(255) NOT NULL`
- `name VARCHAR(255) NOT NULL`
- `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
- `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `workstream_id`)
- `FOREIGN KEY (organization_id) REFERENCES organizations(organization_id) ON DELETE CASCADE`
- Explicit `CONSTRAINT uq_workstreams UNIQUE (organization_id, workstream_id)` (or explicit UNIQUE constraint/index)

#### Table 3: `squads`
- `organization_id VARCHAR(255) NOT NULL`
- `workstream_id VARCHAR(255) NOT NULL`
- `squad_id VARCHAR(255) NOT NULL`
- `name VARCHAR(255) NOT NULL`
- `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
- `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `workstream_id`, `squad_id`)
- Composite FK `FOREIGN KEY (organization_id, workstream_id) REFERENCES workstreams(organization_id, workstream_id) ON DELETE CASCADE`
- Explicit `CONSTRAINT uq_squads UNIQUE (organization_id, workstream_id, squad_id)`

#### Table 4: `principals`
- `organization_id VARCHAR(255) NOT NULL`
- `principal_id VARCHAR(255) NOT NULL`
- `email VARCHAR(255)`
- `name VARCHAR(255)`
- `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
- `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `principal_id`)
- Composite FK `FOREIGN KEY (organization_id) REFERENCES organizations(organization_id) ON DELETE CASCADE`
- Explicit `CONSTRAINT uq_principals UNIQUE (organization_id, principal_id)`

#### Table 5: `service_identities`
- `organization_id VARCHAR(255) NOT NULL`
- `service_identity_id VARCHAR(255) NOT NULL`
- `name VARCHAR(255) NOT NULL`
- `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
- `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `service_identity_id`)
- Composite FK `FOREIGN KEY (organization_id) REFERENCES organizations(organization_id) ON DELETE CASCADE`
- Explicit `CONSTRAINT uq_service_identities UNIQUE (organization_id, service_identity_id)`

#### Table 6: `roles`
- `organization_id VARCHAR(255) NOT NULL`
- `role_id VARCHAR(255) NOT NULL`
- `version VARCHAR(64) NOT NULL DEFAULT 'v1'`
- `name VARCHAR(255) NOT NULL`
- `permissions JSONB NOT NULL DEFAULT '[]'::jsonb`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `role_id`, `version`)
- Composite FK `FOREIGN KEY (organization_id) REFERENCES organizations(organization_id) ON DELETE CASCADE`

#### Table 7: `organization_memberships`
- `organization_id VARCHAR(255) NOT NULL`
- `subject_type VARCHAR(32) NOT NULL CHECK (subject_type IN ('principal', 'service_identity'))`
- `subject_id VARCHAR(255) NOT NULL`
- `role_id VARCHAR(255) NOT NULL`
- `role_version VARCHAR(64) NOT NULL DEFAULT 'v1'`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `subject_type`, `subject_id`, `role_id`)
- Composite FK to roles: `FOREIGN KEY (organization_id, role_id, role_version) REFERENCES roles(organization_id, role_id, version)`
- Composite FK to organizations: `FOREIGN KEY (organization_id) REFERENCES organizations(organization_id) ON DELETE CASCADE`

#### Table 8: `workstream_memberships`
- `organization_id VARCHAR(255) NOT NULL`
- `workstream_id VARCHAR(255) NOT NULL`
- `subject_type VARCHAR(32) NOT NULL CHECK (subject_type IN ('principal', 'service_identity'))`
- `subject_id VARCHAR(255) NOT NULL`
- `role_id VARCHAR(255) NOT NULL`
- `role_version VARCHAR(64) NOT NULL DEFAULT 'v1'`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `workstream_id`, `subject_type`, `subject_id`, `role_id`)
- Composite FK to workstreams: `FOREIGN KEY (organization_id, workstream_id) REFERENCES workstreams(organization_id, workstream_id) ON DELETE CASCADE`
- Composite FK to roles: `FOREIGN KEY (organization_id, role_id, role_version) REFERENCES roles(organization_id, role_id, version)`

#### Table 9: `squad_memberships`
- `organization_id VARCHAR(255) NOT NULL`
- `workstream_id VARCHAR(255) NOT NULL`
- `squad_id VARCHAR(255) NOT NULL`
- `subject_type VARCHAR(32) NOT NULL CHECK (subject_type IN ('principal', 'service_identity'))`
- `subject_id VARCHAR(255) NOT NULL`
- `role_id VARCHAR(255) NOT NULL`
- `role_version VARCHAR(64) NOT NULL DEFAULT 'v1'`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `workstream_id`, `squad_id`, `subject_type`, `subject_id`, `role_id`)
- Composite FK to squads: `FOREIGN KEY (organization_id, workstream_id, squad_id) REFERENCES squads(organization_id, workstream_id, squad_id) ON DELETE CASCADE`
- Composite FK to roles: `FOREIGN KEY (organization_id, role_id, role_version) REFERENCES roles(organization_id, role_id, version)`

#### Table 10: `repositories`
- `organization_id VARCHAR(255) NOT NULL`
- `repository_id VARCHAR(255) NOT NULL`
- `name VARCHAR(255) NOT NULL`
- `url VARCHAR(1024)`
- `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
- `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `repository_id`)
- Composite FK `FOREIGN KEY (organization_id) REFERENCES organizations(organization_id) ON DELETE CASCADE`
- Explicit `CONSTRAINT uq_repositories UNIQUE (organization_id, repository_id)`

#### Table 11: `workstream_repositories`
- `organization_id VARCHAR(255) NOT NULL`
- `workstream_id VARCHAR(255) NOT NULL`
- `repository_id VARCHAR(255) NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- PRIMARY KEY (`organization_id`, `workstream_id`, `repository_id`)
- Composite FK to workstreams: `FOREIGN KEY (organization_id, workstream_id) REFERENCES workstreams(organization_id, workstream_id) ON DELETE CASCADE`
- Composite FK to repositories: `FOREIGN KEY (organization_id, repository_id) REFERENCES repositories(organization_id, repository_id) ON DELETE CASCADE`

---

### 2. File: `factory/tests/test-v2-migration-schema.mjs`
Create automated test runner in ESM format (`.mjs`) verifying SQL syntax and structure offline / against an in-memory SQL mock engine or SQL parser.

#### Test Strategy:
1. Parse SQL statements from `factory/infra/migrations/V2__tenant_and_membership.sql`.
2. Verify all 11 required tables (`organizations`, `workstreams`, `squads`, `principals`, `service_identities`, `roles`, `organization_memberships`, `workstream_memberships`, `squad_memberships`, `repositories`, `workstream_repositories`) are created with `CREATE TABLE`.
3. Validate columns, defaults, primary keys, `CHECK` constraints (e.g. `revision >= 1`, `subject_type IN ('principal', 'service_identity')`), `FOREIGN KEY` references, and `UNIQUE` constraints in the SQL text.
4. Execute test using `node factory/tests/test-v2-migration-schema.mjs`.

---

### 3. File: `factory/infra/README.md` (Optional update)
Document V2 schema additions in `factory/infra/README.md` under the Migrations section.

---

## Verification Plan
1. Execute `node factory/tests/test-v2-migration-schema.mjs` and check exit status is 0.
2. Run `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2` to ensure overall test suite health.
