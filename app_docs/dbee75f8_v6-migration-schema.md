# Migration Flyway V6 : artefacts, oracle et agent-step

## Ce qui a changé

La migration `factory/infra/migrations/V6__artifacts_oracle_agentstep.sql` ajoute dix tables au schéma cumulatif V1–V6 :

- `artifacts`, avec trois dimensions de statut indépendantes (`availability_status`, `retention_status`, `legal_hold`), métadonnées de contenu, informations de rétention/purge et payload JSONB conservé. Une contrainte interdit de purger un artefact sous legal hold.
- `oracle_executions`, agrégat mutable d’exécution d’oracle, avec statut, révision optimiste et références optionnelles vers preuve/artefact.
- L’agrégat agent-step : `agent_step_attempts` (racine mutable), `agent_step_attempt_events`, `agent_step_results` et `result_capabilities` (tables append-only). Les sous-tables portent l’identité composite complète de l’attempt et sont liées par FK composites en cascade.
- Les squelettes de réservation worker/environment : `work_units`, `work_environments`, `workers` et `work_unit_leases`. Ils réservent les identifiants et relations tenant-scoped, sans scheduler, fencing, heartbeat ni mécanique active de bail.

Les racines mutables ont `revision >= 1`, `created_at`/`updated_at` et un trigger `BEFORE UPDATE` appelant `set_updated_at()`. Les tables append-only n’ont ni `updated_at` ni trigger. Les FK composites utilisent `ON DELETE CASCADE` et les PK/index suivent l’isolation par organisation/workstream.

## Documentation et validation

`factory/infra/README.md` documente la migration V6 à la suite des sections précédentes : tables, clés et contraintes, statuts orthogonaux des artefacts, règle anti-purge sous legal hold, agrégat attempt et limites des squelettes worker/environment. Il indique aussi la commande de validation hors ligne.

`factory/tests/test-v6-migration-schema.mjs` est un runner Node.js sans dépendance externe. Il charge et parse V1 à V6, puis vérifie la propreté du SQL, les dix tables et leurs colonnes/defaults, PK/FK composites et cascades, index de support, CHECK de statuts et anti-purge, distinction mutable/append-only, triggers et defaults tenant-scoped. Exécution :

```bash
node factory/tests/test-v6-migration-schema.mjs
```

Le runner est conçu pour échouer avec un code de sortie non nul si une assertion de schéma échoue.

## Fichiers concernés

- `factory/infra/migrations/V6__artifacts_oracle_agentstep.sql`
- `factory/tests/test-v6-migration-schema.mjs`
- `factory/infra/README.md`

Le diff contient également `specs/dbee75f8_v6_flyway_migration.md`, un document de spécification détaillant le périmètre, le schéma attendu, les blocs de test et la stratégie de vérification.

## Vérification recommandée

Lancer le nouveau runner V6, puis les runners de migration V2 à V5 pour confirmer la non-régression des migrations cumulées.
