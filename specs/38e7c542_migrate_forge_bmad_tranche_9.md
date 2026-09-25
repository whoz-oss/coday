# Plan: Migrer la Tranche 9 (FORGE/BMAD) vers TypeScript

## Context & Objectives

Migrer l'ensemble des modules source legacy `factory/lib/forge-*.mjs` (13 fichiers) et `factory/lib/jira.mjs` (1 fichier) vers TypeScript sous `factory/src/` (dans `domain/forge-bmad/`, `adapters/`, et `application/forge-bmad/`), tout en préservant le comportement, la compatibilité 100%, les formats JSON/ledger, les hashs, les regex et toutes les signatures exportées.

Le livrable final doit inclure :
- Un commit unique : `refactor(factory): migrate forge-bmad domain to TypeScript (tranche 9)`
- Artefact `factory/runtime/factory-operational.mjs` (et son `.meta.json`) à jour via `node factory/toolchain/build.mjs`.
- Shims stateless dans `factory/lib/` réexportant depuis `../runtime/factory-operational.mjs`.
- Validation via les tests unitaires et `typescript-factory-operational.mjs`.

---

## Analysis of the 14 Source Files

### 1. Pure Domain Candidates (`factory/src/domain/forge-bmad/`)
AUCUNE dépendance I/O (`node:fs`, HTTP, `child_process`, AgentOS, Git CLI). `node:crypto` est autorisé pour les hashs purement déterministes.

- **`forge-roots.ts`**:
  - Functions: `resolveForgeRoots({ factoryRoot, repoRoot, cliStorePath, storePolicy, envStorePath })`, `defaultRunStoreRoot(factoryRoot, repoRoot)`.
  - Constants: `CLI_STORE_SUBPATH`, `FACTORY_RUN_STORE_SUBPATH`, `REPO_RUN_STORE_SUBPATH`, `REPO_RUN_STORE_POLICY`.
  - Note: Résolution pure de chemins d'accès (manipulation de chaînes et de `node:path`).
- **`forge-spec.ts`**:
  - Constants: `FORGE_SPEC_SCHEMA_VERSION`, `G2_POLICY_VERSION`, `ORACLE_CATALOG`.
  - Pure functions: `hashForgeSpec(content)`, `parseForgeSpecFrontmatter(raw)`, `validateForgeSpecSchema(spec)`.
  - Note I/O: La fonction legacy `loadForgeSpec({ specPath, roots, workItem })` lit le fichier via `node:fs`. Seule la logique pure va dans `domain/` ; la lecture disque va dans `adapters/forge/` (ex. `forge-spec-loader.ts` ou dans un adapter spec).
- **`forge-story-spec.ts`**:
  - Constants: `FORGE_STORY_SPEC_SCHEMA_VERSION`, `G2_US_POLICY_VERSION`.
  - Pure functions: `validateInheritance(storySpec, epicSpec)`, `parseStorySpecFrontmatter(raw)`.
  - Note I/O: `readStorySpec(specPath, roots)` et `hashStorySpec(specPath)` lisent des fichiers -> adapter.
- **`forge-bmad-reader.ts`**:
  - Pure functions: Minimalist YAML parsing (`parseYamlMinimal`), Frontmatter parsing, Sprint status parsing, strictly line-by-line / text-based parsing (`parseForgeRunYamlText`, `parseStoryFrontmatterText`, `parseSprintStatusText`).
  - Note I/O: `readForgeRunYaml`, `readForgeRunYamlStrict`, `readStoryFrontmatter`, `readSprintStatus` font des `readFileSync` / `existsSync` -> adapter `adapters/forge/forge-bmad-file-reader.ts`.
- **`forge-human-decision.ts`**:
  - Constants: `G1_POLICY_VERSION`.
  - Pure functions: `computeG1EvidenceSetHash(events, runId, attempt, policyVersion)`.
  - Note I/O: `recordHumanDecision` écrit dans le ledger (I/O) -> `application/` or `adapters/`.
- **`forge-ledger.ts`**:
  - Pure functions: `parseForgeLedger(rawLines)`, `projectForgeRun(events)`, `validateLedgerEntry(entry)`.
  - Note I/O: `createEpicRun`, `appendToLedger`, `ensureForgeRunStore`, `listForgeRunProjections` font du `node:fs` -> adapter / application.
- **`forge-workflow-adapter.ts`**:
  - Pure function: `adaptForgeRunToWorkflowProjection(forgeRunYaml, options)`. Pure transformation from YAML structure to generic workflow projection format.
- **`jira.ts`**:
  - Constants: `COMMENTS_CHAR_BUDGET`.
  - Pure functions: `extractTicketId(input)`, `extractAdfText(node)`, `applyCommentBudget(comments, budget)`.
  - Note I/O: `fetchJiraTicket` et `fetchJiraComments` font du `fetch` HTTP -> `adapters/jira/jira-client.ts`.

### 2. Adapters & Persistence (`factory/src/adapters/forge/` & `factory/src/adapters/jira/`)
- **`adapters/forge/forge-ledger-store.ts`**:
  - I/O persistence pour le forge-ledger (`createEpicRun`, `appendToLedger`, `readLedgerLines`, `listForgeRunProjections`, `ensureForgeRunStore`).
- **`adapters/forge/forge-spec-reader.ts`**:
  - Reading and hashing specs from disk (`loadForgeSpec`, `readStorySpec`, `hashStorySpec`).
- **`adapters/forge/forge-bmad-file-reader.ts`**:
  - File reading wrappers for BMAD YAML (`readForgeRunYaml`, `readForgeRunYamlStrict`, `readStoryFrontmatter`, `readSprintStatus`).
- **`adapters/jira/jira-client.ts`**:
  - `fetchJiraComments`, `fetchJiraTicket` (HTTP fetch & ADF processing).

### 3. Application Services (`factory/src/application/forge-bmad/`)
- **`forge-g2.ts`**: `evaluateG2`, `evaluateG2US` (orchestrates roots, spec reading, ledger parsing, evaluation).
- **`forge-human-decision-service.ts`**: `recordHumanDecision` (orchestrates ledger recording for G1 decision).
- **`forge-story-analysis-service.ts`**: `executeStoryAnalysis`, `writeStoryAnalysisArtifact`, constants `AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION`, `STORY_ANALYSIS_POLICY_VERSION`, `STORY_ANALYSIS_PLAN_SCHEMA_VERSION`.
- **`forge-story-edit-service.ts`**: `executeStoryEdit`, constants `STORY_EDIT_SCHEMA_VERSION`, `STORY_EDIT_POLICY_VERSION`.
- **`forge-story-oracles-service.ts`**: `executeStoryOracles`, `isAllowedStoryOracleRequestBody`, constant `STORY_ORACLE_POLICY_VERSION`.
- **`forge-workflow-sync-service.ts`**: `syncForgeWorkflowProjection`, `sanitizeForgeSyncAttribution`, constant `SAFE_FORGE_TICKET_ID`.
- **`forge-front-oracle-resolution-service.ts`**: `resolveFrontOraclePlan`, `resolveOwnerProjectConfigs`, `inspectNxProject`, `parseFrontBuildHostMap`, constant `FRONT_ORACLE_MAP_SCHEMA_VERSION`.

---

## Detailed Step-by-Step Execution Plan

### Step 1: Create pure Domain TypeScript files in `factory/src/domain/forge-bmad/`

1. **`factory/src/domain/forge-bmad/forge-roots.ts`**:
   - Move pure path calculation logic: `CLI_STORE_SUBPATH`, `FACTORY_RUN_STORE_SUBPATH`, `REPO_RUN_STORE_SUBPATH`, `REPO_RUN_STORE_POLICY`, `resolveForgeRoots`, `defaultRunStoreRoot`.
2. **`factory/src/domain/forge-bmad/forge-spec-domain.ts`**:
   - Move constants (`FORGE_SPEC_SCHEMA_VERSION`, `G2_POLICY_VERSION`, `ORACLE_CATALOG`), frontmatter parser, validation and hashing logic.
3. **`factory/src/domain/forge-bmad/forge-story-spec-domain.ts`**:
   - Move constants (`FORGE_STORY_SPEC_SCHEMA_VERSION`, `G2_US_POLICY_VERSION`), `validateInheritance`, frontmatter parsing.
4. **`factory/src/domain/forge-bmad/forge-bmad-parser.ts`**:
   - Move line-by-line custom YAML parser, ADF/frontmatter text parsers, sprint status parser.
5. **`factory/src/domain/forge-bmad/forge-human-decision-domain.ts`**:
   - Move constant `G1_POLICY_VERSION`, `computeG1EvidenceSetHash`.
6. **`factory/src/domain/forge-bmad/forge-ledger-domain.ts`**:
   - Move ledger entry parsing (`parseForgeLedger`), event projection (`projectForgeRun`), event constants & types.
7. **`factory/src/domain/forge-bmad/forge-workflow-adapter.ts`**:
   - Move `adaptForgeRunToWorkflowProjection`.
8. **`factory/src/domain/forge-bmad/jira-domain.ts`**:
   - Move `COMMENTS_CHAR_BUDGET`, `extractTicketId`, `extractAdfText`, `applyCommentBudget`.

### Step 2: Create Adapters in `factory/src/adapters/forge/` and `factory/src/adapters/jira/`

1. **`factory/src/adapters/forge/forge-ledger-store.ts`**:
   - Implement `ensureForgeRunStore`, `createEpicRun`, `appendToLedger`, `listForgeRunProjections`, etc., using `node:fs` / `node:fs/promises` and calling domain functions.
2. **`factory/src/adapters/forge/forge-spec-reader.ts`**:
   - Implement `loadForgeSpec`, `readStorySpec`, `hashStorySpec` using `node:fs` and domain parsers.
3. **`factory/src/adapters/forge/forge-bmad-file-reader.ts`**:
   - Implement `readForgeRunYaml`, `readForgeRunYamlStrict`, `readStoryFrontmatter`, `readSprintStatus` using `node:fs` and domain parsers.
4. **`factory/src/adapters/jira/jira-client.ts`**:
   - Implement `fetchJiraComments` and `fetchJiraTicket` using HTTP fetch and `jira-domain.ts`.

### Step 3: Create Application Services in `factory/src/application/forge-bmad/`

1. **`factory/src/application/forge-bmad/forge-g2.ts`**:
   - Implement `evaluateG2`, `evaluateG2US`.
2. **`factory/src/application/forge-bmad/forge-human-decision.ts`**:
   - Implement `recordHumanDecision`.
3. **`factory/src/application/forge-bmad/forge-story-analysis.ts`**:
   - Implement `executeStoryAnalysis`, `writeStoryAnalysisArtifact`, constants `AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION`, `STORY_ANALYSIS_POLICY_VERSION`, `STORY_ANALYSIS_PLAN_SCHEMA_VERSION`.
4. **`factory/src/application/forge-bmad/forge-story-edit.ts`**:
   - Implement `executeStoryEdit`, constants `STORY_EDIT_SCHEMA_VERSION`, `STORY_EDIT_POLICY_VERSION`.
5. **`factory/src/application/forge-bmad/forge-story-oracles.ts`**:
   - Implement `executeStoryOracles`, `isAllowedStoryOracleRequestBody`, constant `STORY_ORACLE_POLICY_VERSION`.
6. **`factory/src/application/forge-bmad/forge-workflow-sync.ts`**:
   - Implement `syncForgeWorkflowProjection`, `sanitizeForgeSyncAttribution`, constant `SAFE_FORGE_TICKET_ID`.
7. **`factory/src/application/forge-bmad/forge-front-oracle-resolution.ts`**:
   - Implement `resolveFrontOraclePlan`, `resolveOwnerProjectConfigs`, `inspectNxProject`, `parseFrontBuildHostMap`, constant `FRONT_ORACLE_MAP_SCHEMA_VERSION`.

### Step 4: Update Entrypoint `factory/src/entrypoints/factory-operational.ts`

Export all public members from the new domain, adapters, and application files:
- `forge-roots`
- `forge-spec`
- `forge-story-spec`
- `forge-bmad-reader`
- `forge-human-decision`
- `forge-ledger`
- `forge-workflow-adapter`
- `forge-workflow-sync`
- `forge-story-analysis`
- `forge-story-edit`
- `forge-story-oracles`
- `forge-front-oracle-resolution`
- `forge-g2`
- `jira`

### Step 5: Build Toolchain & Typecheck

Run build command to generate updated operational bundle:
```bash
node factory/toolchain/build.mjs
```
Verify generated `factory/runtime/factory-operational.mjs` and `factory/dist/factory-operational/factory-operational.meta.json`.

### Step 6: Create Stateless Shims in `factory/lib/`

Replace the implementation of the 14 JS/MJS files with thin shims re-exporting from `../runtime/factory-operational.mjs`:
- `factory/lib/forge-bmad-reader.mjs`
- `factory/lib/forge-spec.mjs`
- `factory/lib/forge-story-analysis.mjs`
- `factory/lib/forge-story-edit.mjs`
- `factory/lib/forge-story-oracles.mjs`
- `factory/lib/forge-story-spec.mjs`
- `factory/lib/forge-g2.mjs`
- `factory/lib/forge-human-decision.mjs`
- `factory/lib/forge-ledger.mjs`
- `factory/lib/forge-roots.mjs`
- `factory/lib/forge-workflow-adapter.mjs`
- `factory/lib/forge-workflow-sync.mjs`
- `factory/lib/forge-front-oracle-resolution.mjs`
- `factory/lib/jira.mjs`

### Step 7: Update and Run Test Suites

1. Update `factory/tests/typescript-factory-operational.mjs` to add assertions for Tranche 9 (Forge/BMAD & Jira):
   - Assert all exported symbols exist on the operational bundle.
   - Assert exact identity between lib shims and operational bundle exports.
   - Assert inputs in `.meta.json` contain the new TypeScript source files.
   - Execute lightweight smoke tests for Jira and Forge domain functions.

2. Run all relevant test suites:
   - `node factory/tests/typescript-factory-operational.mjs`
   - `node factory/tests/test-jira.mjs`
   - `node factory/tests/test-forge-front-oracle-resolution.mjs`
   - `node factory/tests/test-forge-g1.mjs`
   - `node factory/tests/test-forge-g2.mjs`
   - `node factory/tests/test-forge-g2-us.mjs`
   - `node factory/tests/test-forge-run.mjs`
   - `node factory/tests/test-forge-story-analysis.mjs`
   - `node factory/tests/test-forge-story-analysis-contract.mjs`
   - `node factory/tests/test-forge-story-analysis-edge-cases.mjs`
   - `node factory/tests/test-forge-story-edit.mjs`
   - `node factory/tests/test-forge-story-edit-contract.mjs`
   - `node factory/tests/test-forge-story-oracles.mjs`
   - `node factory/tests/test-forge-story-oracles-contract.mjs`
   - `node factory/tests/test-forge-story-oracles-no-test-target.mjs`
   - `node factory/tests/test-forge-workflow-adapter.mjs`
   - `node factory/tests/test-forge-workflow-projection-routes.mjs`

---

## Verification Criteria

1. **Compilation & Bundle**:
   - `node factory/toolchain/build.mjs` runs without error and outputs valid bundle & meta file.
2. **Operational Bundle Assertions**:
   - `node factory/tests/typescript-factory-operational.mjs` passes all checks for Tranche 9.
3. **Existing Tests**:
   - All 15+ `test-forge-*.mjs` and `test-jira.mjs` scripts pass without any failure.
4. **Clean Architecture**:
   - `factory/src/domain/forge-bmad/` files do NOT import `node:fs`, HTTP, or process runners.
5. **Git Commit**:
   - Commit title: `refactor(factory): migrate forge-bmad domain to TypeScript (tranche 9)`
