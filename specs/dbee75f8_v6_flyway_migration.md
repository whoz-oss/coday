# Plan d'implémentation Migration Flyway V6

## Périmètre d'action STRICT
1. `factory/infra/migrations/V6__artifacts_oracle_agentstep.sql`
2. `factory/tests/test-v6-migration-schema.mjs`
3. `factory/infra/README.md` (section V6 ajoutée à la suite de V5)

AUCUN autre fichier ne doit être créé ou modifié dans le cadre de cette tâche.

---

## 1. Description Détaillée des Tables V6 (`factory/infra/migrations/V6__artifacts_oracle_agentstep.sql`)

### 1.1 `artifacts` (Mutable / Rétention & Purge & Legal Hold)
- **Primary Key** : `(organization_id, workstream_id, namespace_id, workflow_id, artifact_id)`
- **Composite FK** : `(organization_id, workstream_id, namespace_id, workflow_id)` -> `workflow_instances (organization_id, workstream_id, namespace_id, workflow_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `namespace_id VARCHAR(255) NOT NULL`
  - `workflow_id VARCHAR(255) NOT NULL`
  - `artifact_id VARCHAR(255) NOT NULL`
  - `availability_status VARCHAR(64) NOT NULL DEFAULT 'pending'` `CHECK (availability_status IN ('pending', 'uploading', 'available', 'unavailable', 'purged'))`
  - `retention_status VARCHAR(64) NOT NULL DEFAULT 'active'` `CHECK (retention_status IN ('active', 'expired'))`
  - `legal_hold BOOLEAN NOT NULL DEFAULT FALSE`
  - `retention_until TIMESTAMPTZ` (nullable)
  - `purged_at TIMESTAMPTZ` (nullable)
  - `purge_reason TEXT` (nullable)
  - `legal_hold_reason TEXT` (nullable)
  - `legal_hold_set_at TIMESTAMPTZ` (nullable)
  - `content_hash VARCHAR(255) NOT NULL`
  - `size BIGINT NOT NULL`
  - `content_type VARCHAR(255) NOT NULL`
  - `storage_key VARCHAR(1024) NOT NULL`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Contraintes CHECK additionnelles** :
  - `CONSTRAINT artifacts_legal_hold_purge_check CHECK (NOT (legal_hold = TRUE AND availability_status = 'purged'))` (ou `CHECK (legal_hold = FALSE OR availability_status <> 'purged')`)
- **Trigger** :
  - `DROP TRIGGER IF EXISTS trg_artifacts_updated_at ON artifacts;`
  - `CREATE TRIGGER trg_artifacts_updated_at BEFORE UPDATE ON artifacts FOR EACH ROW EXECUTE FUNCTION set_updated_at();`
- **Index de support** :
  - `CREATE INDEX IF NOT EXISTS idx_artifacts_instance ON artifacts (organization_id, workstream_id, namespace_id, workflow_id, availability_status);`

### 1.2 `oracle_executions` (Mutable Aggregate Oracle)
- **Primary Key** : `(organization_id, workstream_id, namespace_id, workflow_id, execution_id)`
- **Composite FK** : `(organization_id, workstream_id, namespace_id, workflow_id)` -> `workflow_instances (organization_id, workstream_id, namespace_id, workflow_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `namespace_id VARCHAR(255) NOT NULL`
  - `workflow_id VARCHAR(255) NOT NULL`
  - `execution_id VARCHAR(255) NOT NULL`
  - `oracle_id VARCHAR(255) NOT NULL`
  - `status VARCHAR(64) NOT NULL DEFAULT 'running'` `CHECK (status IN ('running', 'succeeded', 'failed', 'cancelled'))`
  - `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
  - `evidence_id VARCHAR(255)`
  - `artifact_id VARCHAR(255)`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Trigger** :
  - `DROP TRIGGER IF EXISTS trg_oracle_executions_updated_at ON oracle_executions;`
  - `CREATE TRIGGER trg_oracle_executions_updated_at BEFORE UPDATE ON oracle_executions FOR EACH ROW EXECUTE FUNCTION set_updated_at();`
- **Index de support** :
  - `CREATE INDEX IF NOT EXISTS idx_oracle_executions_instance ON oracle_executions (organization_id, workstream_id, namespace_id, workflow_id, status);`

### 1.3 `agent_step_attempts` & sous-tables (Agrégat Attempt Agent Step)

#### 1.3.1 `agent_step_attempts` (Racine mutuelle d'attempt)
- **Primary Key** : `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id)`
- **Composite FK** : `(organization_id, workstream_id, namespace_id, workflow_id)` -> `workflow_instances (organization_id, workstream_id, namespace_id, workflow_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `namespace_id VARCHAR(255) NOT NULL`
  - `workflow_id VARCHAR(255) NOT NULL`
  - `step_id VARCHAR(255) NOT NULL`
  - `attempt_id VARCHAR(255) NOT NULL`
  - `agent_id VARCHAR(255) NOT NULL`
  - `status VARCHAR(64) NOT NULL DEFAULT 'running'` `CHECK (status IN ('running', 'completed', 'failed', 'timed_out', 'cancelled'))`
  - `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
  - `idempotency_key VARCHAR(255)`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Trigger** :
  - `DROP TRIGGER IF EXISTS trg_agent_step_attempts_updated_at ON agent_step_attempts;`
  - `CREATE TRIGGER trg_agent_step_attempts_updated_at BEFORE UPDATE ON agent_step_attempts FOR EACH ROW EXECUTE FUNCTION set_updated_at();`
- **Index de support** :
  - `CREATE INDEX IF NOT EXISTS idx_agent_step_attempts_step ON agent_step_attempts (organization_id, workstream_id, namespace_id, workflow_id, step_id, status);`

#### 1.3.2 `agent_step_attempt_events` (Append-only Event Log)
- **Primary Key** : `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, event_id)`
- **Composite FK** : `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id)` -> `agent_step_attempts (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `namespace_id VARCHAR(255) NOT NULL`
  - `workflow_id VARCHAR(255) NOT NULL`
  - `step_id VARCHAR(255) NOT NULL`
  - `attempt_id VARCHAR(255) NOT NULL`
  - `event_id VARCHAR(255) NOT NULL`
  - `event_type VARCHAR(255) NOT NULL`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Note** : Append-only (PAS de `updated_at`, PAS de trigger).

#### 1.3.3 `agent_step_results` (Append-only Resultats d'attempt)
- **Primary Key** : `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id)`
- **Composite FK** : `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id)` -> `agent_step_attempts (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `namespace_id VARCHAR(255) NOT NULL`
  - `workflow_id VARCHAR(255) NOT NULL`
  - `step_id VARCHAR(255) NOT NULL`
  - `attempt_id VARCHAR(255) NOT NULL`
  - `result_id VARCHAR(255) NOT NULL`
  - `result_status VARCHAR(64) NOT NULL` `CHECK (result_status IN ('success', 'failure', 'collision_detected'))`
  - `semantic_signature VARCHAR(255)`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Note** : Append-only (PAS de `updated_at`, PAS de trigger).

#### 1.3.4 `result_capabilities` (Append-only Capacités/Effets déclarés)
- **Primary Key** : `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id, capability_id)`
- **Composite FK** : `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id)` -> `agent_step_results (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `namespace_id VARCHAR(255) NOT NULL`
  - `workflow_id VARCHAR(255) NOT NULL`
  - `step_id VARCHAR(255) NOT NULL`
  - `attempt_id VARCHAR(255) NOT NULL`
  - `result_id VARCHAR(255) NOT NULL`
  - `capability_id VARCHAR(255) NOT NULL`
  - `capability_type VARCHAR(255) NOT NULL`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Note** : Append-only (PAS de `updated_at`, PAS de trigger).

### 1.4 Réservation Worker / Environment (Squelettes uniquement)

#### 1.4.1 `work_units`
- **Primary Key** : `(organization_id, workstream_id, work_unit_id)`
- **Composite FK** : `(organization_id, workstream_id)` -> `workstreams (organization_id, workstream_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `work_unit_id VARCHAR(255) NOT NULL`
  - `unit_type VARCHAR(255) NOT NULL`
  - `status VARCHAR(64) NOT NULL DEFAULT 'created'` `CHECK (status IN ('created', 'assigned', 'running', 'completed', 'failed', 'cancelled'))`
  - `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Trigger** : `trg_work_units_updated_at` (sur `updated_at` avec `set_updated_at()`).

#### 1.4.2 `work_environments`
- **Primary Key** : `(organization_id, workstream_id, environment_id)`
- **Composite FK** : `(organization_id, workstream_id)` -> `workstreams (organization_id, workstream_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `environment_id VARCHAR(255) NOT NULL`
  - `env_type VARCHAR(255) NOT NULL`
  - `status VARCHAR(64) NOT NULL DEFAULT 'provisioning'` `CHECK (status IN ('provisioning', 'ready', 'busy', 'decommissioned'))`
  - `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Trigger** : `trg_work_environments_updated_at` (sur `updated_at` avec `set_updated_at()`).

#### 1.4.3 `workers`
- **Primary Key** : `(organization_id, worker_id)`
- **Composite FK** : `(organization_id)` -> `organizations (organization_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `worker_id VARCHAR(255) NOT NULL`
  - `worker_type VARCHAR(255) NOT NULL`
  - `status VARCHAR(64) NOT NULL DEFAULT 'offline'` `CHECK (status IN ('offline', 'idle', 'busy', 'maintenance'))`
  - `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Trigger** : `trg_workers_updated_at` (sur `updated_at` avec `set_updated_at()`).

#### 1.4.4 `work_unit_leases`
- **Primary Key** : `(organization_id, workstream_id, work_unit_id, lease_id)`
- **Composite FK** : `(organization_id, workstream_id, work_unit_id)` -> `work_units (organization_id, workstream_id, work_unit_id)` `ON DELETE CASCADE`
- **Colonnes** :
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `work_unit_id VARCHAR(255) NOT NULL`
  - `lease_id VARCHAR(255) NOT NULL`
  - `worker_id VARCHAR(255) NOT NULL`
  - `environment_id VARCHAR(255)`
  - `status VARCHAR(64) NOT NULL DEFAULT 'active'` `CHECK (status IN ('active', 'released', 'expired'))`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- **Note** : Append-only / Log d'affectation sans mécanique active de bail (pas de `updated_at`, pas de trigger).

---

## 2. Runner de Test (`factory/tests/test-v6-migration-schema.mjs`)

Adaptation de la structure du test `test-v5-migration-schema.mjs` pour inclure V1 à V6.

### Constantes & Configuration
- `SQL_FILES = ['V1__init_workflow_pilot_schema.sql', ..., 'V6__artifacts_oracle_agentstep.sql']`
- `V6_FILE = 'V6__artifacts_oracle_agentstep.sql'`

### Blocs de Test à Implémenter
1. **Bloc A — Propreté syntaxique du SQL**
   - V1..V6 existent, sont non vides et se terminent par un `;`.
   - Aucune instruction vide.
   - Parenthèses équilibrées.

2. **Bloc B — Présence des tables V6 et schéma des colonnes**
   - Vérification de l'existence de la table, des colonnes obligatoires/optionnelles et des types/defaults pour les 9 tables V6.

3. **Bloc C — Clés Primaires et Clés Étrangères Composites**
   - Validation que chaque PK composite tenant-scoped est exacte.
   - Verification que les FK composites ciblent les bonnes PK (`workflow_instances`, `agent_step_attempts`, `agent_step_results`, `workstreams`, `organizations`, `work_units`) avec `ON DELETE CASCADE`.

4. **Bloc D — Index de support V6**
   - `idx_artifacts_instance` sur `artifacts` (`organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `availability_status`)
   - `idx_oracle_executions_instance` sur `oracle_executions` (`organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `status`)
   - `idx_agent_step_attempts_step` sur `agent_step_attempts` (`organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `step_id`, `status`)

5. **Bloc E — Contraintes CHECK**
   - `artifacts` : status orthogonal (`availability_status`, `retention_status`), contrainte anti-purge `CHECK (NOT (legal_hold = TRUE AND availability_status = 'purged'))`.
   - `oracle_executions` : `status IN ('running', 'succeeded', 'failed', 'cancelled')`, `revision >= 1`.
   - `agent_step_attempts` : `status IN ('running', 'completed', 'failed', 'timed_out', 'cancelled')`, `revision >= 1`.
   - `agent_step_results` : `result_status IN ('success', 'failure', 'collision_detected')`.
   - Squelettes workers/env : CHECK sur `status` et `revision >= 1` (si mutable).

6. **Bloc F — Tables Append-only vs Mutables**
   - Verification absence de `updated_at` et triggers sur `agent_step_attempt_events`, `agent_step_results`, `result_capabilities`, `work_unit_leases`.
   - Verification présence de `updated_at` et triggers (`trg_artifacts_updated_at`, `trg_oracle_executions_updated_at`, `trg_agent_step_attempts_updated_at`, `trg_work_units_updated_at`, `trg_work_environments_updated_at`, `trg_workers_updated_at`) appelant `set_updated_at()`.

7. **Bloc G — Tenant Isolation**
   - `organization_id NOT NULL DEFAULT 'default'` sur toutes les tables V6.

---

## 3. Mise à jour de la documentation (`factory/infra/README.md`)

Ajouter à la suite du fichier (sans modifier les sections V1..V5) :
- Ajout de `V6__artifacts_oracle_agentstep.sql` dans le tableau/arborescence des migrations.
- Section dédiée : `## Schema overview — V6 artifacts, oracle, agent-step & worker/environment`.
- Explication synthétique et détaillée des tables, des 3 dimensions d'artefacts, des règles d'orthogonalité, du legal hold anti-purge, de l'agrégat agent-step attempts et des squelettes worker/environment.
- Section de validation hors-ligne : `node factory/tests/test-v6-migration-schema.mjs`.

---

## 4. Stratégie de Vérification & Exécution

1. Exécuter `node factory/tests/test-v6-migration-schema.mjs` et vérifier un passage avec 0 échec.
2. Re-exécuter `node factory/tests/test-v5-migration-schema.mjs` (ainsi que v4/v3/v2) pour garanties de non-régression.
