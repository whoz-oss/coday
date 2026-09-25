# Plan d'implémentation Jalon C1-T0 : Migration Flyway V7 & Test de Validation de Schéma

## 1. Description du besoin

Dans le cadre du Jalon C1 (Protocole de Bail & Fencing), nous devons faire évoluer le schéma de base de données PostgreSQL via une migration Flyway **V7** (`factory/infra/migrations/V7__lease_protocol.sql`).

Cette migration complète les squelettes créés en V6 (`work_units`, `workers`, `work_unit_leases`) pour introduire le contrôle d'accès concurrentiel, le cycle de vie des baux avec heartbeat/expiration, et la stratégie d'attribution ordonnée de tâches.

Les évolutions requises sont :
1. `work_unit_leases` :
   - Ajout des colonnes : `fencing_token BIGINT`, `acquired_at TIMESTAMPTZ`, `lease_expires_at TIMESTAMPTZ`, `heartbeat_at TIMESTAMPTZ`, `released_at TIMESTAMPTZ`, `expiry_reason VARCHAR`.
   - Ajout d'index :
     - Index d'acquisition : `idx_work_unit_leases_acquisition` sur `(organization_id, workstream_id, work_unit_id, status)`
     - Index d'expiration : `idx_work_unit_leases_expiry` sur `(organization_id, lease_expires_at)` (ou `(lease_expires_at)`)
2. `workers` :
   - Ajout des colonnes : `last_heartbeat_at TIMESTAMPTZ`, `protocol_version VARCHAR`, `capabilities JSONB` (DEFAULT `'[]'::jsonb` ou `'{}'::jsonb`).
3. `work_units` :
   - Ajout des colonnes : `priority INTEGER NOT NULL DEFAULT 0`, `not_before TIMESTAMPTZ` (nullable), `attempt_count INTEGER NOT NULL DEFAULT 0`.
   - Ajout d'index d'éligibilité pour `SELECT ... FOR UPDATE SKIP LOCKED` : `idx_work_units_eligibility` sur `(organization_id, workstream_id, status, priority DESC, not_before)`.
4. Mécanisme de FENCING TOKEN MONOTONE garanti EN BASE :
   - Créer une séquence PostgreSQL dédiée : `CREATE SEQUENCE IF NOT EXISTS work_unit_lease_fencing_seq START WITH 1 INCREMENT BY 1;`
   - Documenter clairement le choix par des commentaires SQL dans V7.

5. Test de validation hors-ligne de schéma `factory/tests/test-v7-migration-schema.mjs` :
   - Test Node.js autonome (sans dépendances `pg`/`docker`/etc.).
   - Charge la chaîne de migrations `V1` -> `V7`.
   - Vérifie la syntaxe, la présence des colonnes, des index, des séquences, la propreté du SQL V7.
   - Valide la logique/monotonie de la séquence de fencing token (par exemple parsing/simulation ou vérification statique de la définition de séquence et de sa constante d'incrément positive).

6. Documentation `factory/infra/README.md` :
   - Ajouter la section V7 à la suite des sections V1 à V6 sans altérer le contenu existant.
   - Expliquer les ajouts de V7, la stratégie de fencing token monotone en base, et mentionner la commande pour exécuter le test de validation.

---

## 2. Périmètre STRICT et Interdictions

### Interdictions STRICTES
- NE PAS modifier `V1__*.sql` à `V6__*.sql`.
- NE PAS modifier la couche TypeScript sous `factory/` ou `src/`.
- NE PAS modifier `factory/runtime/factory-operational.mjs`.
- NE PAS toucher à `agentos/**`.
- S'assurer que `node factory/tests/test-v7-migration-schema.mjs` sorte avec code 0 (`exit 0`).

---

## 3. Analyse détaillée des fichiers à modifier / créer

### A. `factory/infra/migrations/V7__lease_protocol.sql` (Nouveau)

Fichier de migration DDL pour PostgreSQL.

```sql
-- V7__lease_protocol.sql
--
-- Jalon C1-T0: Lease protocol, heartbeat, worker capabilities and monotone fencing token mechanism.
--
-- This migration extends the B2 skeleton tables (`work_units`, `workers`, `work_unit_leases`) to support:
--   * Monotone fencing tokens via PostgreSQL sequence `work_unit_lease_fencing_seq`.
--   * Lease acquisition, heartbeat, expiry and release tracking on `work_unit_leases`.
--   * Worker liveness, protocol versioning and declared capabilities on `workers`.
--   * Work unit priority scheduling, deferred execution (`not_before`) and attempt tracking on `work_units`.

-- --------------------------------------------------------------------------
-- Monotone Fencing Token Sequence
-- --------------------------------------------------------------------------
-- PostgreSQL sequence used to generate strictly increasing, monotone fencing tokens
-- across all lease acquisitions in the database. Using a sequence guarantees monotonic
-- ordering even across concurrent transactions and worker nodes.
CREATE SEQUENCE IF NOT EXISTS work_unit_lease_fencing_seq
  START WITH 1
  INCREMENT BY 1;

-- --------------------------------------------------------------------------
-- ALTER TABLE work_unit_leases
-- --------------------------------------------------------------------------
ALTER TABLE work_unit_leases
  ADD COLUMN IF NOT EXISTS fencing_token BIGINT,
  ADD COLUMN IF NOT EXISTS acquired_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS released_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS expiry_reason VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_work_unit_leases_acquisition
  ON work_unit_leases (organization_id, workstream_id, work_unit_id, status);

CREATE INDEX IF NOT EXISTS idx_work_unit_leases_expiry
  ON work_unit_leases (organization_id, lease_expires_at);

-- --------------------------------------------------------------------------
-- ALTER TABLE workers
-- --------------------------------------------------------------------------
ALTER TABLE workers
  ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS protocol_version VARCHAR(64),
  ADD COLUMN IF NOT EXISTS capabilities JSONB NOT NULL DEFAULT '[]'::jsonb;

-- --------------------------------------------------------------------------
-- ALTER TABLE work_units
-- --------------------------------------------------------------------------
ALTER TABLE work_units
  ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS not_before TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_work_units_eligibility
  ON work_units (organization_id, workstream_id, status, priority DESC, not_before);
```

### B. `factory/tests/test-v7-migration-schema.mjs` (Nouveau)

Ce test adapte le pattern de `test-v6-migration-schema.mjs` en lisant la chaîne complète **V1 -> V7**.

Il contiendra des blocs de vérification clairs :
1. **Bloc A — Fichiers et propreté SQL V1..V7**
   - V1..V7 existent, non vides, se terminent par un point-virgule `;`.
   - Parenthèses équilibrées.
2. **Bloc B — Présence des nouvelles colonnes dans V7**
   - `work_unit_leases`: `fencing_token` (BIGINT), `acquired_at` (TIMESTAMPTZ), `lease_expires_at` (TIMESTAMPTZ), `heartbeat_at` (TIMESTAMPTZ), `released_at` (TIMESTAMPTZ), `expiry_reason` (VARCHAR).
   - `workers`: `last_heartbeat_at` (TIMESTAMPTZ), `protocol_version` (VARCHAR), `capabilities` (JSONB).
   - `work_units`: `priority` (INTEGER DEFAULT 0 NOT NULL), `not_before` (TIMESTAMPTZ nullable), `attempt_count` (INTEGER DEFAULT 0 NOT NULL).
3. **Bloc C — Séquence PostgreSQL Fencing Token**
   - Vérifier l'instruction `CREATE SEQUENCE IF NOT EXISTS work_unit_lease_fencing_seq START WITH 1 INCREMENT BY 1`.
   - Vérifier la présence du mot clé `INCREMENT BY 1` ou une valeur positive (garantie de monotonie strictly croissante).
   - Simuler/valider le comportement monotone des tokens dans la logique du test (par exemple une fonction utilitaire de test vérifiant la monotonie `nextval` simulée `t2 > t1`).
4. **Bloc D — Index V7**
   - Vérifier la présence de `idx_work_unit_leases_acquisition`, `idx_work_unit_leases_expiry`, `idx_work_units_eligibility`.
   - Vérifier que `idx_work_units_eligibility` contient `priority DESC` ou `priority` et `not_before`.
5. **Bloc E — Respect de l'isolation tenant et intégrité du schéma cumulé**
   - Vérifier que l'état cumulé V1..V7 conserve l'isolation tenant (`organization_id`).

### C. `factory/infra/README.md` (Modification)

Ajouter la section décrivant la migration V7 à la fin du document :
- Section `### Lease Protocol & Fencing (Jalon C1 - V7)`
- Description des extensions apportées à `work_unit_leases`, `workers`, `work_units`.
- Stratégie du Fencing Token Monotone via séquence PostgreSQL `work_unit_lease_fencing_seq`.
- Commande d'exécution du test : `node factory/tests/test-v7-migration-schema.mjs`.

---

## 4. Étapes d'exécution pas-à-pas

### Étape 1 : Créer `factory/infra/migrations/V7__lease_protocol.sql`
Écrire les instructions DDL PostgreSQL pour `CREATE SEQUENCE`, `ALTER TABLE work_unit_leases`, `ALTER TABLE workers`, `ALTER TABLE work_units` et les `CREATE INDEX`.

### Étape 2 : Créer `factory/tests/test-v7-migration-schema.mjs`
Implémenter le script ES module autonome chargeant V1 à V7, parsant les déclarations ALTER, INDEX et CREATE SEQUENCE, et exécutant les assertions.

### Étape 3 : Mettre à jour `factory/infra/README.md`
Ajouter la documentation de V7 en fin de fichier.

### Étape 4 : Exécuter et valider
Lancer `node factory/tests/test-v7-migration-schema.mjs` et vérifier que le code de sortie est 0 et que tous les tests passent.

Lancer la suite globale d'impact si nécessaire :
`pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2` (ou vérifier via `node factory/tests/test-v7-migration-schema.mjs`).

---

## 5. Fichiers touchés et création des plans

1. `factory/infra/migrations/V7__lease_protocol.sql` (Création)
2. `factory/tests/test-v7-migration-schema.mjs` (Création)
3. `factory/infra/README.md` (Modification)
