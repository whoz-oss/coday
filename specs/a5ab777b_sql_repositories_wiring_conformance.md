# Plan Jalon B (B3-T4) : Câblage des repositories SQL et conformité d'ensemble

## Resume
Câbler l'ensemble des adapters et factories SQL de persistance dans les barrels `factory/src/adapters/persistence/sql/index.ts` et `factory/src/adapters/persistence/index.ts`, étendre le test global `factory/tests/test-sql-repository-ports-adapters.mjs` pour valider la parité de surface et de contrat entre les adapters Filesystem et SQL ainsi que leur présence dans le bundle runtime, puis générer `factory/runtime/factory-operational.mjs` via le script de build exact `node factory/toolchain/build.mjs`.

## Files to touch
1. `factory/src/adapters/persistence/sql/index.ts`
2. `factory/src/adapters/persistence/index.ts`
3. `factory/tests/test-sql-repository-ports-adapters.mjs`
4. `factory/runtime/factory-operational.mjs` (généré automatiquement via `node factory/toolchain/build.mjs`)

## Detailed Steps

### Step 1: Compléter `factory/src/adapters/persistence/sql/index.ts`
Exporter l'intégralité des 9 adapters SQL et leurs factories/options associées :
- `SqlWorkflowDefinitionRepository`, `createSqlWorkflowDefinitionRepository`, `SqlWorkflowDefinitionRepositoryOptions` (déjà présent)
- `SqlWorkflowInstanceRepository`, `createSqlWorkflowInstanceRepository`, `SqlWorkflowInstanceRepositoryOptions` (déjà présent)
- `SqlWorkflowEvidenceRepository`, `createSqlWorkflowEvidenceRepository`, `SqlWorkflowEvidenceRepositoryOptions`
- `SqlWorkflowHumanInteractionRepository`, `createSqlWorkflowHumanInteractionRepository`, `SqlWorkflowHumanInteractionRepositoryOptions`
- `SqlAgentStepAttemptRepository`, `createSqlAgentStepAttemptRepository`, `SqlAgentStepAttemptRepositoryOptions`
- `SqlAgentStepResultRepository`, `createSqlAgentStepResultRepository`, `SqlAgentStepResultRepositoryOptions`
- `SqlOracleExecutionRepository`, `createSqlOracleExecutionRepository`, `SqlOracleExecutionRepositoryOptions`
- `SqlWorkEnvironmentRepository`, `createSqlWorkEnvironmentRepository`, `SqlWorkEnvironmentRepositoryOptions`
- `SqlDeliveryRepository`, `createSqlDeliveryRepository`, `SqlDeliveryRepositoryOptions`

Garder également les exports existants de `db.js` et `unit-of-work.js`.

### Step 2: Compléter `factory/src/adapters/persistence/index.ts`
Re-exporter depuis `./sql/index.js` les nouveaux adapters SQL, leurs classes, factories et types d'options afin d'offrir une parité de surface complète au niveau persistance entre les adapters filesystem et les adapters SQL :
- Re-exporter :
  - `SqlWorkflowEvidenceRepository`, `createSqlWorkflowEvidenceRepository`, `type SqlWorkflowEvidenceRepositoryOptions`
  - `SqlWorkflowHumanInteractionRepository`, `createSqlWorkflowHumanInteractionRepository`, `type SqlWorkflowHumanInteractionRepositoryOptions`
  - `SqlAgentStepAttemptRepository`, `createSqlAgentStepAttemptRepository`, `type SqlAgentStepAttemptRepositoryOptions`
  - `SqlAgentStepResultRepository`, `createSqlAgentStepResultRepository`, `type SqlAgentStepResultRepositoryOptions`
  - `SqlOracleExecutionRepository`, `createSqlOracleExecutionRepository`, `type SqlOracleExecutionRepositoryOptions`
  - `SqlWorkEnvironmentRepository`, `createSqlWorkEnvironmentRepository`, `type SqlWorkEnvironmentRepositoryOptions`
  - `SqlDeliveryRepository`, `createSqlDeliveryRepository`, `type SqlDeliveryRepositoryOptions`

Note : Ne re-exporter que la surface publique exportée par `./sql/index.js` pour éviter tout conflit ou ambiguïté.

### Step 3: Régénérer le bundle runtime (Étape intermédiaire)
Exécuter la commande :
```bash
node factory/toolchain/build.mjs
```
Afin que `factory/runtime/factory-operational.mjs` contienne tous les nouveaux exports pour que `test-sql-repository-ports-adapters.mjs` puisse les importer depuis l'entrée runtime.

### Step 4: Étendre `factory/tests/test-sql-repository-ports-adapters.mjs`
Compléter le test pour couvrir TOUS les ports de persistance en parité Filesystem <-> SQL :
1. Importer les 9 classes d'adapters SQL et leurs 9 factories `createSql*Repository` depuis `../runtime/factory-operational.mjs` (ou `factory/src/adapters/persistence/index.ts` selon le style d'import du fichier, tout en vérifiant l'accès depuis `factory-operational.mjs`).
2. Importer les 9 classes/factories filesystem correspondantes (`FilesystemWorkflowDefinitionRepository`, `FilesystemWorkflowInstanceRepository`, `FilesystemWorkflowEvidenceRepository`, `FilesystemWorkflowHumanInteractionRepository`, `FilesystemAgentStepAttemptRepository`, `FilesystemAgentStepResultRepository`, `FilesystemOracleExecutionRepository`, `FilesystemWorkEnvironmentRepository`, `FilesystemDeliveryRepository`).
3. Ajouter des assertions de parité de surface et d'instanciation pour chacun des 9 ports :
   - Vérifier l'existence et le type des constructeurs / classes (`Sql*Repository`).
   - Vérifier la présence des factories (`createSql*Repository`) et s'assurer qu'elles renvoient une instance valide de la classe `Sql*Repository` lorsqu'un `SqlClient` (p. ex. `createInMemorySqlClient()`) leur est transmis.
   - Vérifier la parité de surface de méthodes entre chaque adapter Filesystem et son pendant SQL (ex: s'assurer que toutes les méthodes publiques du prototype de l'adapter Filesystem sont également présentes sur le prototype de l'adapter SQL).
4. Conserver les suites de contrat existantes (`definition/filesystem`, `definition/sql`, `instance/filesystem`, `instance/sql` et l'optimistic lock test) sans modifier la logique des suites by-group déjà autonomes (`test-conformance-*.mjs`).

### Step 5: Exécution du Build & Vérification
1. Exécuter `node factory/toolchain/build.mjs` une dernière fois pour s'assurer que le bundle est parfaitement synchronisé.
2. Lancer la suite globale étendue et les suites by-group W2 pour valider que tous les tests de conformité sous `factory/tests/` passent :
   ```bash
   node factory/tests/test-sql-repository-ports-adapters.mjs
   node factory/tests/test-conformance-agent-step-oracle.mjs
   node factory/tests/test-conformance-evidence-interaction.mjs
   node factory/tests/test-conformance-work-environment-delivery.mjs
   node factory/tests/test-repository-ports-adapters.mjs
   ```

## Anti-rules / Directives Respect
- NE PAS modifier la logique interne des 9 adapters SQL ou filesystem (logique figée).
- NE PAS modifier `db.ts`, `unit-of-work.ts`, `in-memory-sql-client.mjs`.
- NE PAS modifier les migrations SQL (`factory/infra/flyway/sql/`).
- NE PAS toucher à `agentos/**`.
- NE PAS éditer `factory/runtime/factory-operational.mjs` à la main : seul `node factory/toolchain/build.mjs` doit la régénérer.
