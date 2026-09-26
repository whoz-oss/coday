-- V2__tenant_and_membership.sql
--
-- Authoritative tenant, structure, identity, role and membership schema for
-- Jalon B2 (Vague B2). This migration introduces the durable, tenant-scoped
-- relational surface that the Factory persistence adapters migrate onto next
-- (definitions/instances were piloted in V1).
--
-- Conventions (see `factory/infra/README.md`):
--   * Versioned migrations live in `factory/infra/migrations` and are applied by
--     Flyway in lexical version order (`V<version>__<description>.sql`).
--   * Every row is tenant-scoped by a non-null `organization_id`. The structural
--     children additionally carry `workstream_id` / `squad_id` so a composite
--     foreign key can never bridge two tenants: tenant isolation is enforced by
--     the database, not only by application code.
--   * Mutable aggregates carry a `revision` column used for optimistic locking.
--     `CHECK (revision >= 1)` keeps the counter meaningful.
--   * The domain payload is stored verbatim as JSONB; the relational columns are
--     the query/identity surface only.

-- --------------------------------------------------------------------------
-- updated_at trigger helper
--
-- Reusable `BEFORE UPDATE` hook wired on every table that carries `updated_at`.
-- `CREATE OR REPLACE` keeps the migration re-runnable without touching the data.
-- --------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- --------------------------------------------------------------------------
-- organizations (tenant root)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS organizations (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  name            VARCHAR(255) NOT NULL,
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT organizations_pkey PRIMARY KEY (organization_id)
);

-- --------------------------------------------------------------------------
-- workstreams (organization-scoped structural unit)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workstreams (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL,
  name            VARCHAR(255) NOT NULL,
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workstreams_pkey PRIMARY KEY (organization_id, workstream_id),
  -- Explicit unique key so the composite FK target below is a real uniqueness
  -- guarantee even though the primary key already implies one.
  CONSTRAINT uq_workstreams UNIQUE (organization_id, workstream_id),
  CONSTRAINT workstreams_organization_fk FOREIGN KEY (organization_id)
    REFERENCES organizations(organization_id) ON DELETE CASCADE
);

-- --------------------------------------------------------------------------
-- squads (workstream-scoped structural unit)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS squads (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL,
  squad_id        VARCHAR(255) NOT NULL,
  name            VARCHAR(255) NOT NULL,
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT squads_pkey PRIMARY KEY (organization_id, workstream_id, squad_id),
  CONSTRAINT uq_squads UNIQUE (organization_id, workstream_id, squad_id),
  CONSTRAINT squads_workstream_fk FOREIGN KEY (organization_id, workstream_id)
    REFERENCES workstreams(organization_id, workstream_id) ON DELETE CASCADE
);

-- --------------------------------------------------------------------------
-- principals (human identities, organization-scoped)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS principals (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  principal_id    VARCHAR(255) NOT NULL,
  email           VARCHAR(255),
  name            VARCHAR(255),
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT principals_pkey PRIMARY KEY (organization_id, principal_id),
  CONSTRAINT uq_principals UNIQUE (organization_id, principal_id),
  CONSTRAINT principals_organization_fk FOREIGN KEY (organization_id)
    REFERENCES organizations(organization_id) ON DELETE CASCADE
);

-- --------------------------------------------------------------------------
-- service_identities (machine identities, organization-scoped)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_identities (
  organization_id     VARCHAR(255) NOT NULL DEFAULT 'default',
  service_identity_id VARCHAR(255) NOT NULL,
  name                VARCHAR(255) NOT NULL,
  revision            INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload             JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT service_identities_pkey PRIMARY KEY (organization_id, service_identity_id),
  CONSTRAINT uq_service_identities UNIQUE (organization_id, service_identity_id),
  CONSTRAINT service_identities_organization_fk FOREIGN KEY (organization_id)
    REFERENCES organizations(organization_id) ON DELETE CASCADE
);

-- --------------------------------------------------------------------------
-- roles (versioned permission bundles, organization-scoped)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS roles (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  role_id         VARCHAR(255) NOT NULL,
  version         VARCHAR(64)  NOT NULL DEFAULT 'v1',
  name            VARCHAR(255) NOT NULL,
  permissions     JSONB        NOT NULL DEFAULT '[]'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT roles_pkey PRIMARY KEY (organization_id, role_id, version),
  CONSTRAINT roles_organization_fk FOREIGN KEY (organization_id)
    REFERENCES organizations(organization_id) ON DELETE CASCADE
);

-- --------------------------------------------------------------------------
-- organization_memberships (subject * role at organization scope)
--
-- The `(organization_id, role_id, role_version)` FK pins the role to the same
-- tenant as the membership: a cross-organization role can never be attached.
-- `subject_id` is polymorphic (principal or service_identity); the subject_type
-- check constrains the discriminator and the matching identity table is the
-- application-level referential integrity point.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS organization_memberships (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  subject_type    VARCHAR(32)  NOT NULL CHECK (subject_type IN ('principal', 'service_identity')),
  subject_id      VARCHAR(255) NOT NULL,
  role_id         VARCHAR(255) NOT NULL,
  role_version    VARCHAR(64)  NOT NULL DEFAULT 'v1',
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT organization_memberships_pkey PRIMARY KEY (organization_id, subject_type, subject_id, role_id),
  CONSTRAINT organization_memberships_organization_fk FOREIGN KEY (organization_id)
    REFERENCES organizations(organization_id) ON DELETE CASCADE,
  CONSTRAINT organization_memberships_role_fk FOREIGN KEY (organization_id, role_id, role_version)
    REFERENCES roles(organization_id, role_id, version)
);

-- --------------------------------------------------------------------------
-- workstream_memberships (subject * role at workstream scope)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workstream_memberships (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL,
  subject_type    VARCHAR(32)  NOT NULL CHECK (subject_type IN ('principal', 'service_identity')),
  subject_id      VARCHAR(255) NOT NULL,
  role_id         VARCHAR(255) NOT NULL,
  role_version    VARCHAR(64)  NOT NULL DEFAULT 'v1',
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workstream_memberships_pkey PRIMARY KEY (organization_id, workstream_id, subject_type, subject_id, role_id),
  CONSTRAINT workstream_memberships_workstream_fk FOREIGN KEY (organization_id, workstream_id)
    REFERENCES workstreams(organization_id, workstream_id) ON DELETE CASCADE,
  CONSTRAINT workstream_memberships_role_fk FOREIGN KEY (organization_id, role_id, role_version)
    REFERENCES roles(organization_id, role_id, version)
);

-- --------------------------------------------------------------------------
-- squad_memberships (subject * role at squad scope)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS squad_memberships (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL,
  squad_id        VARCHAR(255) NOT NULL,
  subject_type    VARCHAR(32)  NOT NULL CHECK (subject_type IN ('principal', 'service_identity')),
  subject_id      VARCHAR(255) NOT NULL,
  role_id         VARCHAR(255) NOT NULL,
  role_version    VARCHAR(64)  NOT NULL DEFAULT 'v1',
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT squad_memberships_pkey PRIMARY KEY (organization_id, workstream_id, squad_id, subject_type, subject_id, role_id),
  CONSTRAINT squad_memberships_squad_fk FOREIGN KEY (organization_id, workstream_id, squad_id)
    REFERENCES squads(organization_id, workstream_id, squad_id) ON DELETE CASCADE,
  CONSTRAINT squad_memberships_role_fk FOREIGN KEY (organization_id, role_id, role_version)
    REFERENCES roles(organization_id, role_id, version)
);

-- --------------------------------------------------------------------------
-- repositories (organization-scoped source repositories)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS repositories (
  organization_id VARCHAR(255)  NOT NULL DEFAULT 'default',
  repository_id   VARCHAR(255)  NOT NULL,
  name            VARCHAR(255)  NOT NULL,
  url             VARCHAR(1024),
  revision        INTEGER       NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload         JSONB         NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT repositories_pkey PRIMARY KEY (organization_id, repository_id),
  CONSTRAINT uq_repositories UNIQUE (organization_id, repository_id),
  CONSTRAINT repositories_organization_fk FOREIGN KEY (organization_id)
    REFERENCES organizations(organization_id) ON DELETE CASCADE
);

-- --------------------------------------------------------------------------
-- workstream_repositories (workstream * repository association)
--
-- Both composite FKs carry `organization_id`, so the two sides must live in the
-- same tenant as the association itself.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workstream_repositories (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL,
  repository_id   VARCHAR(255) NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workstream_repositories_pkey PRIMARY KEY (organization_id, workstream_id, repository_id),
  CONSTRAINT workstream_repositories_workstream_fk FOREIGN KEY (organization_id, workstream_id)
    REFERENCES workstreams(organization_id, workstream_id) ON DELETE CASCADE,
  CONSTRAINT workstream_repositories_repository_fk FOREIGN KEY (organization_id, repository_id)
    REFERENCES repositories(organization_id, repository_id) ON DELETE CASCADE
);

-- --------------------------------------------------------------------------
-- updated_at triggers (one per table carrying `updated_at`)
-- --------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_organizations_updated_at ON organizations;
CREATE TRIGGER trg_organizations_updated_at
  BEFORE UPDATE ON organizations
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_workstreams_updated_at ON workstreams;
CREATE TRIGGER trg_workstreams_updated_at
  BEFORE UPDATE ON workstreams
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_squads_updated_at ON squads;
CREATE TRIGGER trg_squads_updated_at
  BEFORE UPDATE ON squads
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_principals_updated_at ON principals;
CREATE TRIGGER trg_principals_updated_at
  BEFORE UPDATE ON principals
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_service_identities_updated_at ON service_identities;
CREATE TRIGGER trg_service_identities_updated_at
  BEFORE UPDATE ON service_identities
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_roles_updated_at ON roles;
CREATE TRIGGER trg_roles_updated_at
  BEFORE UPDATE ON roles
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_repositories_updated_at ON repositories;
CREATE TRIGGER trg_repositories_updated_at
  BEFORE UPDATE ON repositories
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();
