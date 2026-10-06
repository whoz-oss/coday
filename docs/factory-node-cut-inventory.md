# Factory — Inventaire de coupe du control plane Node (`docs/factory-node-cut-inventory.md`)

> Rapport d'inventaire produit par un agent **scout read-only** (aucune modification de code).
> Livrable prévu pour W6b : à placer tel quel dans `docs/factory-node-cut-inventory.md`.
> Méthode : lecture, `grep`, `diff`, analyse d'imports/réverse-imports sur tout le dépôt (hors `node_modules`).
> Les chemins sont relatifs à la racine du dépôt `/work/app`. Les numéros de ligne sont indicatifs.

---

## §1 Résumé exécutif

Le dossier `factory/` contient bien **deux choses empaquetées**, et l'analyse d'imports le confirme :

- **A — le control plane Node** (à supprimer) : le serveur dashboard HTTP et **toute sa chaîne**
  (`factory/dashboard/server.mjs`, `composition-root.mjs`, `*-routes.mjs`, `agentos-proxy.mjs`,
  `persistence-authority.mjs`, `openapi.json`) **plus les modules `src/` et `lib/` portés en Kotlin**
  (artifacts, oracles/définitions, work-units/workers/leases, delivery, workflow, agent-step,
  forge/BMAD, runs) et leurs tests.
- **B — l'instrument de validation** (à garder impérativement) : `factory/run.mjs`, `factory/workflows/*`,
  `factory/diagnostics/*`, `factory/oracles/*`, les modules `factory/lib/*` historiques (client AgentOS,
  registry, oracle, plan, domains, review, shutdown), `factory/provision*.mjs`, et l'entrypoint worker
  `factory/src/entrypoints/worker-runtime.ts`. Invariant structurel cité par `factory/README.md:13-17` :
  « *L'instrument ne doit pas dépendre de la santé de ce qu'il mesure.* »

**Fait central de l'architecture** : factory-service (Kotlin) **orchestre B en lançant le process Node**
`node factory/run.mjs <workflow>` (`factory-service/src/main/kotlin/io/whozoss/factory/runs/service/LegacyRunService.kt:219-226`,
entrée par défaut `factory/run.mjs` dans `factory-service/.../forge/config/ForgeProperties.kt:18`), puis lit
le registre JSONL depuis `factory/runs/`. Le contrat entre les deux est **le process + le JSONL**, pas un import.

**Découverte majeure** : l'instrument B **dépend du bundle TypeScript généré**
`factory/runtime/factory-operational.mjs`, produit par `factory/toolchain/build.mjs` depuis
`factory/src/entrypoints/factory-operational.ts`. Ce bundle réexporte **à la fois** les modules B
(registry/active-case/shutdown/agentos/oracle) **et** les modules A portés (artifact/delivery/forge/
environment/persistence SQL). **On ne peut donc pas supprimer la chaîne `src/` A sans éditer l'entrypoint
et reconstruire le bundle** : c'est le principal point de bascule de W6b.

**Taille de la coupe (approximative, hors `node_modules`) :**

| Zone | Fichiers | LOC | Sort |
|---|---|---|---|
| `factory/dashboard/**` | 44 | ~19 600 | Serveur A supprimé ; UI cockpit conservée et rebasculée (§5) |
| `factory/src/**` (TS) | 136 | ~27 600 | ~60 % A (suppr.) / ~30 % B (garder) / ~10 % gris |
| `factory/lib/*.mjs` | 72 | ~7 600 | ~15 B, ~50 A, ~7 partagés (gris) |
| `factory/tests/*.mjs` | 124 | ~33 600 | ~34 tests A (suppr.), le reste B (garder) |
| `factory/runtime/factory-operational.mjs` | 1 | ~15 200 | Généré, B — **à reconstruire après trim** |
| `factory/infra/migrations/` | 7 | — | Orphelin (copies V1→V7 identiques dans factory-service) |

**Zone grise = ~7 modules `lib/` + ~8 sous-arbres `src/` + le bundle + `factory/infra/**` + la
documentation `factory/*.md`.** Elle est détaillée et arbitrable en §4 ; aucun élément gris n'est
« peut-être inutile », chacun a un couplage précis identifié.

---

## §2 Tableau À SUPPRIMER (control plane Node porté en Kotlin)

Justification générique : ces fichiers ne sont plus importés que par le dashboard/serveur
(ou par les tests qui le testent), et leurs responsabilités sont portées par `factory-service/src/main/kotlin/`.

### 2.1 Serveur dashboard (cœur du control plane)

| Chemin | Justification | Qui le référençait |
|---|---|---|
| `factory/dashboard/server.mjs` | Bootstrap HTTP, port 3141 | Entrée `node factory/dashboard/server.mjs`; réexporté par 6 tests A |
| `factory/dashboard/composition-root.mjs` | Composition root : stores, adapters, controllers, serveur, routes | `server.mjs:14`, 12 tests A |
| `factory/dashboard/persistence-authority.mjs` | Sélection FS/SQL + shadow reads | `composition-root.mjs:66-73` |
| `factory/dashboard/http-utils.mjs` | Enveloppe HTTP (TrustContext, CORS, body) | `composition-root.mjs:88`, tests |
| `factory/dashboard/agentos-proxy.mjs` | Proxy AgentOS côté dashboard | `composition-root.mjs:90` |
| `factory/dashboard/openapi.json` | OpenAPI du serveur Node (server `http://localhost:3141`, 68 paths) | Docs/tests |
| `factory/dashboard/run-routes.mjs` | Routes runs JSONL + review-gate | `composition-root.mjs:106` |
| `factory/dashboard/active-run-routes.mjs` + `active-run-service.mjs` | Runs actifs / SSE | `composition-root.mjs:102` |
| `factory/dashboard/workflow-definition-routes.mjs` | Definitions REST | `composition-root.mjs:92` |
| `factory/dashboard/workflow-projection-routes.mjs` + `workflow-projection-sse.mjs` | Projection + SSE | `composition-root.mjs:91,93` |
| `factory/dashboard/workflow-transition-routes.mjs` | Transitions | `composition-root.mjs:96` |
| `factory/dashboard/workflow-code-transition-routes.mjs` | Code transitions | `composition-root.mjs:97` |
| `factory/dashboard/workflow-oracle-routes.mjs` | Oracles REST | `composition-root.mjs:95` |
| `factory/dashboard/workflow-evidence-routes.mjs` | Evidence | `composition-root.mjs:94` |
| `factory/dashboard/workflow-human-interaction-routes.mjs` | Interactions humaines | `composition-root.mjs:98` |
| `factory/dashboard/workflow-operational-metrics-routes.mjs` | Métriques | `composition-root.mjs:100` |
| `factory/dashboard/forge-routes.mjs` + `forge-workflow-projection-routes.mjs` | Forge runs/gates | `composition-root.mjs:104,92` |
| `factory/dashboard/delivery-operation-routes.mjs` | Delivery operations | `composition-root.mjs:99` |
| `factory/dashboard/factory-frontend-run-routes.mjs` | Runs frontend factory | `composition-root.mjs:101` |
| `factory/dashboard/agent-step-result-routes.mjs` | Agent-step results + outbox | `composition-root.mjs:103` |
| `factory/dashboard/artifact-admin-routes.mjs` | Admin artefacts (purge/hold/gc) | `composition-root.mjs:105` |
| `factory/dashboard/workstream-routes.mjs` + `workstream-service.mjs` | Workstreams | `composition-root.mjs:106` |

> ⚠️ **Ne PAS supprimer les assets du cockpit** (`cockpit.html`, `css/dockyard.css`, `js/**`) :
> ce sont l'UI vanilla à rebasculer (§5). Seul le *serveur* qui les sert disparaît.

### 2.2 Modules `factory/lib/*.mjs` purement control plane

Importés **uniquement** par `dashboard/**` (et par les tests A) — aucun consommateur B :

`artifact-admin-use-cases.mjs`, `coday-config.mjs`, `agent-step-attempt-store.mjs`,
`agent-step-result-store.mjs`, `delivery-controller.mjs`, `delivery-definition.mjs`,
`delivery-deployment-adapter.mjs`, `delivery-evidence-store.mjs`, `delivery-git-control-plane.mjs`,
`delivery-operation-controller.mjs`, `delivery-operation-definition.mjs`,
`delivery-operation-policy.mjs`, `delivery-policy.mjs`, `delivery-pr-adapter.mjs`,
`delivery-store.mjs`, `delivery-target-registry.mjs`, `factory-agent-step-executor.mjs`,
`factory-frontend-composition.mjs`, `factory-frontend-runner.mjs`,
`factory-operational-metrics-namespace-projector.mjs`, `factory-operational-metrics-projector.mjs`,
`factory-operational-metrics-service.mjs`, `factory-review-package.mjs`,
`forge-bmad-reader.mjs`, `forge-spec.mjs`, `forge-story-spec.mjs`,
`forge-workflow-adapter.mjs`, `forge-workflow-sync.mjs`,
`git-worktree.mjs`, `interval-aggregation.mjs`, `oracle-definition.mjs`, `oracle-executor.mjs`,
`work-unit-environment-controller.mjs`, `work-unit-environment-service.mjs`,
`work-unit-environment-store.mjs`, `work-unit-environment.mjs`,
`workflow-definition-registry.mjs`, `workflow-definition.mjs`, `workflow-evidence-store.mjs`,
`workflow-evidence.mjs`, `workflow-human-interaction-store.mjs`, `workflow-instance.mjs`,
`workflow-projection-store.mjs`, `workflow-projection.mjs`, `workflow-relations.mjs`,
`workflow-resume-dispatch-store.mjs`, `workflow-timing-projector.mjs`, `workflow-transition-policy.mjs`,
et les facades forge/BMAD restantes (`forge-g2.mjs`, `forge-human-decision.mjs`,
`forge-story-analysis.mjs`, `forge-story-edit.mjs`, `forge-story-oracles.mjs`) **sauf** l'usage B de
`forge-epic` (voir §4.2).

> Note : `forge-front-oracle-resolution.mjs` est importé par `oracles/run-frontend-verification.mjs`
> (un oracle de B) → **gray**, voir §4.2.

### 2.3 Modules `factory/src/**` purement control plane

Réexportés par le bundle mais **aucun consommateur B** (cf. §4.1 pour la découpe de l'entrypoint) :

| Sous-arbre `src/` | Rôle porté en Kotlin | Référencé uniquement par |
|---|---|---|
| `src/domain/identity/**` | TrustContext, Fake IdP, memberships | `dashboard/composition-root.mjs:89`, `dashboard/http-utils.mjs:28`, tests |
| `src/adapters/artifact/**` + `src/ports/artifact/**` + `src/application/artifact/**` | ArtifactStore mémoire/S3/Postgres + admin (purge/hold/gc) | dashboard routes admin, tests |
| `src/domain/delivery/**` + `src/application/delivery/**` + `src/adapters/delivery/**` | Delivery (définitions, opérations, git/PR) | dashboard, tests |
| `src/domain/environment/**` + `src/application/environment/**` + `src/adapters/persistence/work-unit-environment-store.ts` | Work-unit environment | dashboard, tests |
| `src/application/forge-bmad/**` + `src/adapters/forge/**` + `src/adapters/jira/jira-client.ts` (sauf forge-roots/forge-ledger, §4.2) | Forge/BMAD, Jira | dashboard routes, tests |
| `src/domain/workflow/**`, `src/domain/evidence/**`, `src/domain/interaction/**`, `src/domain/agent-attempt/**` | Workflow, evidence, interactions, agent-step | facades `lib/workflow-*.mjs`, `lib/agent-step-*.mjs` |
| `src/adapters/persistence/filesystem-*-repository.ts`, `*-store.ts` (agent-step, oracle, work-env, delivery, workflow-*) | Repositories filesystem | facades `lib/*-store.mjs` |
| `src/adapters/persistence/sql/sql-{workflow-*,agent-step-*,oracle-execution,work-environment,delivery,artifact-metadata}-repository.ts` | Repositories SQL portés | dashboard + facades |
| `src/adapters/persistence/migration/one-shot-import.ts`, `src/entrypoints/import-one-shot.ts` | Import FS→PG one-shot | script manuel |
| `src/infrastructure/storage/storage-kernel.ts` | Kernel de stockage FS | repositories filesystem A |
| `src/domain/worker.ts`, `src/domain/work-unit.ts` *(work-unit conservé pour le worker B, §4.5)* | Vocabulaire work-unit/worker | dashboard/facades |

### 2.4 Tests A

~34 fichiers `factory/tests/*.mjs` importent `dashboard/` : `test-agent-step-result-route-source.mjs`,
`test-artifact-admin-commands.mjs`, `test-artifact-global-wiring.mjs`, `test-boundary-hardening.mjs`,
`test-cockpit-*.mjs` (5), `test-factory-api.mjs`, `test-factory-bind-policy.mjs`,
`test-factory-frontend-*-source.mjs`, `test-forge-workflow-projection-routes.mjs`,
`test-identity-trust-context.mjs`, `test-persistence-shadow-and-switch.mjs`,
`test-projection-governance.mjs`, `test-tenant-isolation.mjs`, `test-workflow-*-api.mjs` (10),
etc. À supprimer avec le serveur (ce sont des tests de A, pas de B).

---

## §3 Tableau À GARDER (instrument de validation B)

### 3.1 Entrées et orchestration B

| Chemin | Rôle |
|---|---|
| `factory/run.mjs` | Point d'entrée : `node factory/run.mjs <catégorie> <nom>` ; dépend du bundle (`run.mjs:44-51`) + `lib/review-gate.mjs:52` |
| `factory/workflows/fix-loop.mjs` | Boucle acteur/oracle (imports `lib/registry|oracle|agentos|domains|oracle-command`) |
| `factory/workflows/us-loop.mjs` | Analyste+éditeur (idem + `plan|jira|review-gate|review-agentos-adapter|diagnostic-synthesis|oracle-baseline|adversarial-review`) |
| `factory/workflows/forge-epic.mjs` | Workflow forge (dépend de `lib/forge-roots|forge-ledger`) — **gray**, §4.2 |
| `factory/diagnostics/agentos-smoke.mjs`, `backend-oracle-check.mjs` | Diagnostics de plomberie |
| `factory/provision.mjs` | Provisionne `FACTORY_FILES`/`FACTORY_FILES_RO` + agents éditeur/analyste (AgentOS REST) |
| `factory/provision-reviewers.mjs` | Provisionne 4 agents AdversarialReviewer |
| `factory/provision-frontend-workers.mjs` | Provisionne agents frontend (AgentOS REST) |
| `factory/provision-projection-smoke.mjs` | Provisionne l'agent de smoke projection |
| `factory/oracles/**` (`forge-frontend-verification@1.0.0.json`, `run-frontend-verification.mjs`) | Oracles déterministes |
| `factory/src/entrypoints/worker-runtime.ts` | Entrypoint worker local (B explicite) — **dépend des adapters SQL**, §4.5 |

### 3.2 Modules `factory/lib/*.mjs` de B

`agentos.mjs`, `domains.mjs`, `jira.mjs`, `oracle.mjs`, `oracle-command.mjs`, `oracle-baseline.mjs`,
`oracle-definition.mjs` *(facade, conso. B via us-loop? non — gray §4.7)*, `plan.mjs`, `registry.mjs`,
`review.mjs`, `review-engine.mjs`, `review-agentos-adapter.mjs`, `diagnostic-synthesis.mjs`,
`review-gate.mjs`, `shutdown.mjs`, `adversarial-review.mjs`, `worker-runtime.mjs`,
`forge-roots.mjs`, `forge-ledger.mjs` *(via forge-epic — gray §4.2)*.
Ces modules sont soit « pure `.mjs` » (plan, domains, review, review-agentos-adapter, review-gate),
soit des **facades stateless** réexportant `../runtime/factory-operational.mjs`
(agentos, registry, shutdown, oracle, oracle-command, oracle-baseline, jira, review-engine,
adversarial-review, diagnostic-synthesis, worker-runtime).

### 3.3 Modules `factory/src/**` de B (le bundle peut être réduit à ceux-ci)

| Chemin | Export(s) consommé(s) par B |
|---|---|
| `src/lib/active-case.ts` | `registerActiveCase`, `unregisterActiveCase`, `getActiveCaseIds` (review-engine, diagnostic-synthesis, adversarial-review, run.mjs) |
| `src/lib/registry.ts` | `createRun/startPhase/passPhase/failPhase/endRun/endCurrentRunOnce/getCurrentRun` (registry.mjs) |
| `src/application/shutdown.ts` | `createShutdownController` (shutdown.mjs, run.mjs) |
| `src/adapters/agentos/**` | client HTTP, runtime adapter, case terminator, event translator, DTOs |
| `src/adapters/process-shutdown.ts` | `installSigtermHandler`, `processExit` |
| `src/ports/agent-runtime-gateway.ts`, `src/ports/case-terminator.ts` | types du gateway/case terminator |
| `src/application/agentos-operations.ts` | `createCase/runAgentTurn/preflight*/killCase/list*` (agentos.mjs) |
| `src/domain/oracle/oracle.ts` | `countTaskOutcomes`, `diffSnapshots` (oracle.mjs) |
| `src/application/oracle/oracle-{command,executor,baseline}.ts` | `buildOracleCommand`, `runCommand/snapshotDiff/diffSince`, baseline |
| `src/adapters/jira/jira-client.ts` | `fetchJiraTicket` (jira.mjs) |
| `src/domain/forge-bmad/forge-roots.ts`, `forge-ledger.ts` + `src/adapters/forge/forge-roots-resolver.ts`, `forge-ledger-store.ts` | forge-roots/forge-ledger (forge-epic) — gray §4.2 |
| `src/domain/worker-runtime/**` + `src/entrypoints/worker-runtime.ts` | `WorkerRuntime`, demo executor, launcher |
| `src/domain/work-unit.ts`, `src/domain/lease/**` | vocabulaire du worker-runtime |
| `src/adapters/persistence/sql/db.ts` | `createPgPoolClient`, `resolveSqlDatabaseConfig` |
| `src/adapters/persistence/sql/sql-{lease,worker,work-unit}-repository.ts` + `sql/index.ts` (barrel **à élaguer**) | repos B du worker-runtime |
| `src/ports/persistence/{lease,worker,work-unit}-repository.ts` | types B (barrel `ports/persistence/index.ts` à élaguer) |
| `src/entrypoints/factory-operational.ts` | **B**, mais **à tronquer** aux réexports ci-dessus (§4.1) |
| `factory/toolchain/**` | Build du bundle (esbuild isolé) |
| `factory/runtime/factory-operational.mjs` | Artefact généré B (régénéré après trim) |

### 3.4 Tests B

Tous les tests `factory/tests/*.mjs` qui **n'importent pas** `dashboard/` : typiquement
`test-agentos-smoke*.mjs`, `test-backend-oracle*.mjs`, `test-us-loop.mjs`, `test-fix-loop*.mjs`,
`test-oracle.mjs`, `test-jira.mjs`, `test-review*.mjs`, `test-diagnostic-synthesis.mjs`,
`test-oracle-baseline.mjs`, `test-build-oracle.mjs`, `test-baseline-scope.mjs`,
`test-shutdown*.mjs`, `test-run-dispatch.mjs`, `test-f7.mjs`, `test-worker-runtime-core.mjs`,
`test-worker-runtime-entrypoint.mjs`, `test-lease-protocol.mjs`, `test-typescript-factory-operational.mjs`,
`test-conformance-*`, etc. (≈90 fichiers). Ils ne sont **pas** exécutés par `nx` (voir §6.pièges).

---

## §4 ZONE GRISE (couplage précis → décision requise)

### 4.1 Le bundle `src/entrypoints/factory-operational.ts` + `runtime/factory-operational.mjs` — **couplage maximal**

- **Couplage** : le bundle est **construit depuis** `src/entrypoints/factory-operational.ts`
  (`toolchain/build.mjs:12-34`) et **consommé par B** (`run.mjs:44`, toutes les facades `lib/*.mjs`).
  Mais l'entrypoint réexporte aussi la totalité des modules A (§2.3) : `domain/workflow|evidence|
  interaction|agent-attempt|environment`, `infrastructure/storage`, `ports/persistence`,
  `adapters/persistence`, `application/{artifact,delivery,environment,forge-bmad}`,
  `adapters/{artifact,delivery,forge,jira}`, `application/agent-attempt/factory-agent-step-executor`.
- **Option recommandée** : **tronquer** `factory-operational.ts` à la liste §3.3, supprimer les
  sous-arbres A §2.3, élaguer les barrels `adapters/persistence/index.ts`, `ports/persistence/index.ts`,
  `adapters/persistence/sql/index.ts`, puis **`node factory/toolchain/build.mjs`** et vérifier le metafile.
  Ne pas supprimer le fichier entrypoint (B en a besoin).

### 4.2 Forge/BMAD : `workflows/forge-epic.mjs` + `lib/forge-*.mjs` + `src/{domain,adapters}/forge*`

- **Couplage double** :
  - Côté B : `workflows/forge-epic.mjs:3-4` importe `lib/forge-roots.mjs` et `lib/forge-ledger.mjs`
    (facades vers `src/domain/forge-bmad/forge-roots.ts` et `forge-ledger.ts` via le bundle).
  - Côté A : `dashboard/composition-root.mjs:36-41` importe `lib/forge-{ledger,human-decision,
    roots,g2,story-analysis,story-edit,story-oracles}.mjs` et 15+ `dashboard/forge-*-routes.mjs`.
- **Question de décision** : `forge-epic` est-il un workflow de l'instrument B (auquel cas la
  sous-chaîne `forge-roots`/`forge-ledger` doit rester dans le bundle) ou un pur artefact du
  control plane (auquel cas il part avec A) ? `docs` et les specs (`specs/453164ec_forge_bmad_runs_kotlin_port.md`)
  indiquent que **ForgeRunService Kotlin a déjà porté** la création/projection de runs forge.
- **Options** :
  1. *(préférée si confirmé)* garder B = `forge-epic.mjs` + `forge-roots`/`forge-ledger` uniquement,
     supprimer le reste du forge ;
  2. sinon extraire `forge-{roots,ledger}` dans un module B dédié et supprimer tout `forge-*`.
  Dans les deux cas, **vérifier `oracles/run-frontend-verification.mjs`** qui importe
  `lib/forge-front-oracle-resolution.mjs` (facade vers `src/application/forge-bmad/forge-front-oracle-resolution.ts`).

### 4.3 `lib/review-gate.mjs` — protocole de gate humain **partagé**

- **Couplage** : importé par B (`run.mjs:52`, `workflows/us-loop.mjs:106`, `lib/shutdown.mjs:17`)
  **et** par A (`dashboard/run-routes.mjs`, `dashboard/composition-root.mjs:35`).
  Le module maintient un **registre in-process** des gates et un protocole **fichier/IPC**
  (`factory/runs/<runId>.<gateInstanceId>.gate-reply`, secret `FACTORY_GATE_IPC_SECRET`).
- **Point dur** : quand `us-loop` tourne (process Node lancé par factory-service), la réponse humaine
  est aujourd'hui écrite par le dashboard via `writeGateReply`. Si le serveur Node disparaît, **qui
  écrit le fichier de réponse ?** factory-service a déjà les routes `GET/POST /api/factory/runs/{id}/review-gate[/reply]`
  (`LegacyRunController.kt:128-138`) et génère un `gateIpcSecret` par process enfant
  (`LegacyRunService.kt:230`) — mais il **ne semble pas encore passer `FACTORY_GATE_IPC_SECRET` à
  l'environnement du child** ni écrire `*.gate-reply` (à vérifier en W6b).
- **Option recommandée** : **garder** `lib/review-gate.mjs` (B), et **porter l'écriture des replies**
  dans `LegacyRunService`/`LegacyRunController` (côté Kotlin) avant de couper le serveur Node.

### 4.4 `lib/registry.mjs` (et `src/lib/registry.ts`) — B mais référencé par A

- **Couplage** : `dashboard/composition-root.mjs` importe `registry.mjs` (facade stateless du bundle).
  Le registre JSONL (`factory/runs/*.jsonl`) est **écrit par B** et **lu par factory-service**
  (`LegacyRunService.kt:134-176`). La partie A ne fait que le consulter.
- **Option** : garder tel quel ; supprimer l'import côté dashboard avec le serveur. Aucun risque.

### 4.5 Entrypoint worker `src/entrypoints/worker-runtime.ts` + ses adapters SQL

- **Couplage** : B explicite, mais `worker-runtime.ts:17-29` importe
  `adapters/persistence/sql/db.ts` + `sql/index.ts` (barrel qui réexporte **tous** les repos SQL,
  y compris A). Le worker-runtime n'est aujourd'hui consommé que par des tests et des docs —
  aucun process runtime de production ne l'importe encore (recherche exhaustive).
- **Option** : garder le module et ses 3 repos SQL (`lease`, `worker`, `work-unit`) + `db.ts` + `unit-of-work.ts`,
  **élaguer `sql/index.ts`** pour ne plus réexporter les repos A, et **retirer** `ports/persistence` A.
  À confirmer : le worker local est-il encore le mode d'exécution retenu, ou factory-service prend-il
  aussi les leases ? (Le portage Kotlin `specs/5f5e940e_leases_workers_kotlin_port.md` existe déjà.)

### 4.6 `src/application/agent-attempt/factory-agent-step-executor.ts`

- **Couplage** : réexporté par le bundle ; consommé par `lib/factory-agent-step-executor.mjs`
  → `lib/factory-frontend-runner.mjs` → `lib/factory-frontend-composition.mjs` (**A**).
  Mais `lib/agentos.mjs` expose `bindFactoryStepResult` (via `agentos-operations.ts`).
- **Option** : traiter comme **A** (le runner frontend est du control plane) ; vérifier que B
  (`agentos.mjs`) n'utilise pas ce fichier. Si `bindFactoryStepResult` vit dans `agentos-operations.ts`,
  il peut rester ; `factory-agent-step-executor.ts` part avec A.

### 4.7 `lib/oracle-definition.mjs` / `oracle-executor.mjs` et `src/application/oracle/oracle-definition-registry.ts`

- **Couplage** : `oracle.mjs` (B) et `oracle-executor.mjs` (facade) se chevauchent : `oracle.mjs` est
  importé par us-loop/fix-loop **et** par `dashboard/workflow-oracle-routes.mjs` ; `oracle-definition.mjs`
  est importé par `dashboard/composition-root.mjs` + `lib/factory-frontend-composition.mjs` (A).
- **Option** : garder `oracle.mjs` + `oracle-command.mjs` + `oracle-baseline.mjs` + `oracle-executor.ts`/`oracle.ts`
  (B) ; le **registre de définitions** d'oraques (`oracle-definition.mjs`, `oracle-definition-registry.ts`,
  `domain/oracle/oracle-definition.ts`) est du control plane porté par l'agrégat Oracle Kotlin — à
  supprimer, sauf si un workflow B résout des définitions d'oraques dynamiquement (à vérifier).

### 4.8 `factory/infra/**` (migrations + compose + backup/restore)

- **Couplage** : `factory/infra/migrations/V1..V7` sont **strictement identiques** (diff vérifié) à
  `factory-service/src/main/resources/db/migration/V1..V7` ; `factory/infra/docker-compose.yml:47`
  monte `./migrations`, `factory/infra/README.md:112` documente `-locations=filesystem:factory/infra/migrations`.
  `V8__delivery.sql` et `V9__workflow.sql` **n'existent que côté factory-service**.
- **Option** : **après** suppression du serveur Node, `factory/infra/migrations/` devient orphelin →
  supprimer le dossier de migrations (les copies factory-service font foi) et remplacer les docs par
  un renvoi vers `factory-service`. Garder, si utile, un compose PG local sans Flyway-filesystem, ou
  le rattacher à `factory-service`.

### 4.9 Documentation `factory/*.md`

`ARCHITECTURE.md`, `AUTHORITY_SOURCES.md`, `DEPENDENCY_MATRIX.md`, `PERSISTENCE_SWITCH_ROLLBACK.md`,
`WORKFLOW_PROJECTION.md`, `WORK_UNIT_ENVIRONMENT.md`, `FORGE_WORKFLOW_ADAPTER.md`, `lib/README.md`,
`README.md` décrivent massivement A (`composition-root.mjs`, `server.mjs`, stores SQL/FS, SQL switch).
- **Option** : soit les **réécrire** pour ne décrire que B, soit les **supprimer** si le control plane
  est entièrement documenté côté factory-service. `ADR_TYPESCRIPT_MIGRATION.md` reste pertinent pour B.
  `docs/coday-auth-usergroups-recon.md` référence `factory/dashboard/http-utils.mjs` et
  `factory/dashboard/artifact-admin-routes.mjs` → à mettre à jour.

### 4.10 Tests de conformance partagés

`factory/tests/test-conformance-{agent-step-oracle,evidence-interaction,work-environment-delivery,workunit-worker}.mjs`,
`test-sql-repository-ports-adapters.mjs`, `test-repository-ports-adapters.mjs`,
`test-persistence-import.mjs`, `test-storage-kernel.mjs`, `test-v2..v7-migration-schema.mjs`,
`test-sql-unit-of-work.mjs`, `test-tenant-isolation.mjs` testent les adapters A **et** des invariants
que factory-service revendique désormais. **Option** : supprimer les tests dont la responsabilité est
désormais couverte par les tests Kotlin (`factory-service/src/test/kotlin/...`), garder ceux qui
couvrent une logique encore partagée (ex. `test-lease-protocol.mjs` si le worker B reste local).

---

## §5 Bascule du cockpit (points à modifier — NE RIEN CHANGER ici)

Le cockpit vanilla (`factory/dashboard/cockpit.html` + `css/` + `js/`) est aujourd'hui **servi par le
serveur Node** et **appelle ses API en relatif (same-origin)**. Points de couplage exacts :

### 5.1 Origine / base URL

| Point | Aujourd'hui | Cible factory-service |
|---|---|---|
| `factory/dashboard/js/app.mjs:233` | `new ApiClient({ baseUrl: '' })` (same-origin) | garder `''` **si** les assets sont servis par factory-service ; sinon → `http://localhost:8141` |
| `factory/dashboard/js/services/api-client.mjs:72` | défaut `baseUrl = ''` | idem |
| `factory/dashboard/js/services/sse-client.mjs:98` / `js/views/*` | `EventSource(url)` relatif (ex. `/api/factory/workflows/stream`, `/api/cases/:id/events`) | idem + **CORS** si origine différente (EventSource n'envoie pas d'en-têtes custom) |
| `factory/dashboard/cockpit.html:10-14` | `<link href="/css/dockyard.css">` (chemin absolu racine) | doit rester servi à la racine (`/css`, `/js`) |
| `factory/dashboard/openapi.json:8295-8299` | `servers: http://localhost:3141` | à supprimer ; autorité = `factory-service/openapi/factory-openapi.yaml` (`servers: http://localhost:18141`) |
| `factory/dashboard/server.mjs:7` / `composition-root.mjs:222` | `PORT` défaut **3141** | factory-service `server.port: 8141` (`application.yml:2`) |

### 5.2 Hébergement statique (manquant côté factory-service)

- `factory/dashboard/composition-root.mjs:781-793, 960-986` sert `/cockpit`, `/`, `/css/*`, `/js/*` avec
  `Content-Type` stricts (`text/css`, `application/javascript`) et redirige `/` → `/cockpit`.
- **factory-service n'a AUCUN handler statique** : `config/WebConfig.kt` ne fait qu'enregistrer
  `TrustContextArgumentResolver` ; pas de `ResourceHandler`. **Aucune config CORS** non plus.
- **Décision W6b (au choix)** :
  1. *Same-origin (recommandé)* : exposer `cockpit.html`, `css/`, `js/` en ressources statiques Spring
     (`classpath:/static/cockpit/...` ou `ResourceHandler` vers `factory/dashboard`) + route `/cockpit` ;
     aucun changement JS (les chemins relatifs fonctionnent). Le `Content-Type` des `.mjs` doit être
     configuré explicitement (`application/javascript`) car Spring peut mal typer les `.mjs`.
  2. *Origines séparées* : ajouter CORS dans `WebConfig` (`/api/**`) et fixer `ApiClient.baseUrl` +
     base SSE vers l'origine factory-service. Attention : `EventSource` ne permet pas d'en-têtes custom,
     donc CORS simple origine requise.
  3. *Reverse-proxy* : servir le statique et proxifier `/api/**` vers factory-service.

### 5.3 Contrat d'API : ce qui correspond déjà / ce qui manque

- **Attribution** : `ApiClient` envoie `X-Factory-Namespace-Id`, `X-Factory-Case-Id`,
  `X-Factory-Actor-Id`, `X-Correlation-Id` (`api-client.mjs:36-44`). factory-service lit exactement
  ces en-têtes (`web/TrustContextExtractor.kt:83-88`) et enforced `X-Correlation-Id`
  (`web/CorrelationIdFilter.kt:37`). ✅ compatible.
- **Endpoints cockpit** (chemins relatifs) vs OpenAPI factory-service :
  - présents : `/api/factory/workflow-definitions`, `/api/factory/workflows*`, `/api/factory/runs*`,
    `/api/factory/admin/artifacts/*`, `/api/factory/agent-step-results`, `/api/forge/runs*`,
    `/api/factory/workstreams`, `/api/cases/{caseId}/events`, `/api/agents`, `/api/factory/jira/*`.
  - **absent du spec factory-service : `GET /api/config`** — utilisé par
    `js/views/projection.mjs:179` et `js/components/case-link.mjs` pour résoudre `agentosUrl`
    (liens profonds AgentOS). ➜ **point de coupe à traiter** (ajouter l'endpoint ou hardcoder une config).
  - vérifier `GET /api/factory/workflows/stream` (SSE projection) : présent côté Kotlin
    (`workflow/sse/WorkflowSseController.kt:39`) mais **non listé dans l'OpenAPI généré** → à confirmer.
- **Cockpit n'utilise pas** les routes review-gate (aucun `grep review-gate` dans `js/`), le lancement
  passe par `POST /api/factory/workflows/:id/run` (`js/views/run-launch.mjs`), jamais par le legacy
  JSONL `POST /api/factory/runs`.

### 5.4 Identité

- Dashboard : `LocalDevMembershipResolver` + Fake IdP (`composition-root.mjs:89,135-151`), opt-in
  loopback (`FACTORY_ALLOW_LOOPBACK_DEV`). factory-service : `web/FakeIdp.kt`, `TrustContextFilter.kt`,
  `factory.security.allow-loopback-dev` (`application.yml:32`). Les deux partagent la même sémantique
  → la bascule doit fournir le même `FACTORY_FAKE_IDP_SECRET` / mode loopback.

### 5.5 Fichiers JS à ajuster (uniquement si option 2 « origine séparée »)

`js/app.mjs:233` (baseUrl), `js/services/sse-client.mjs` + chaque `js/views/*` construisant un
`EventSource`/URL (`run-detail.mjs`, `projection.mjs`, `forge-cockpit.mjs`, `phase-panel.mjs`,
`forge-activity.mjs`). En option 1 (same-origin), **aucun changement JS**.

### 5.6 Consommateurs produit/agents de l'API Factory (`:3141`) — à repointer (hors cockpit)

La bascule ne concerne pas que le cockpit : **plusieurs composants du produit et d'AgentOS appellent
l'API du dashboard Node sur `http://localhost:3141`**. Ils doivent pointer vers factory-service (`:8141`).

| Point | Aujourd'hui | À faire |
|---|---|---|
| `apps/client/proxy.conf.json:11-15` | proxy dev Angular `"/api/factory" → http://localhost:3141` | cibler factory-service (`:8141`) ; `"/api/agentos"` reste `:8123` |
| `libs/model/src/lib/project-description.ts:8-17` | `FactoryProjectConfig.baseUrl` documenté « Trusted Factory dashboard origin, ex. http://127.0.0.1:3141 » | mettre à jour la doc + les valeurs de config (le défaut effectif vient de la config projet) |
| `libs/integration/src/lib/factory.tools.ts` (+ `factory.validation.ts`, `factory.schemas.ts`, `factory.types.ts`) | outils produit « transitional Express-to-Factory adapter » appelant `${baseUrl}/api/factory/workflows/...` (start/evidence/interactions/transitions/projection) | repointer `baseUrl` vers factory-service ; mêmes chemins `/api/factory/*` déjà servis par Kotlin (sauf `/api/config`) |
| `libs/integration/src/lib/factory.tools.test.ts`, `factory.tools.node-test.ts` | tests figés sur `http://127.0.0.1:3141` | mettre à jour les URLs attendues |
| `agentos/agentos-factory-bridge-plugin/.../FactoryBridgeConfig.kt:31,39-43` | `DEFAULT_BASE_URL = "http://localhost:3141"`, env `AGENTOS_FACTORY_BASE_URL` | repointer vers factory-service ; **critique** : c'est le canal utilisé par les agents pour publier projection/evidence/human-decision pendant les runs (`FactoryCheckpointClient`, `tools/Factory*Tool.kt`) |
| `forge_bmad/coday/scripts/{forge-factory-launch,forge-gate2-record,forge-gate-run,forge-workflow-sync}.ts` | `FACTORY_URL`/`FACTORY_SERVER_URL` défaut `http://localhost:3141` + hint « node factory/dashboard/server.mjs » | repointer + corriger les hints |
| `forge_bmad/coday/integrations/PROJECT_SCRIPTS.yaml:45,68,71,78` | `FACTORY_SERVER_URL` défaut `:3141`, « node factory/dashboard/server.mjs on Coday » | repointer + corriger le texte |
| `forge_bmad/coday/skills/core/factory-workflow-projection/SKILL.md` | référence `/api/factory` + `:3141` | repointer |

> Conséquence : même après suppression du serveur Node, la CI Nx (`apps/client`, `libs/integration`)
> compilera, mais les outils et le plugin AgentOS **ne parleront plus à personne** s'ils ne sont pas
> repointés. À traiter dans la même vague que §5.1-5.3.

---

## §6 Pièges & ordre de coupe recommandé

### 6.1 Pièges identifiés

1. **Bundle généré = dépendance runtime de B.** `run.mjs:44` et toutes les facades `lib/*.mjs`
   importent `runtime/factory-operational.mjs`. Supprimer `src/` sans régénérer le bundle **casse B**.
   Le bundle est **versionné** (`factory/.gitignore:12` commente qu'il ne doit pas être ignoré) : le
   rebuild doit être committé dans la même coupe.
2. **Barrels couplés** : `src/adapters/persistence/index.ts`, `ports/persistence/index.ts`,
   `adapters/persistence/sql/index.ts` réexportent des modules A **et** B → ils doivent être **élagués**,
   pas supprimés. Un `export *` cassé fait échouer le build esbuild.
3. **Références textuelles résiduelles** au serveur Node qui deviendront des messages trompeurs :
   `factory-service/.../LegacyRunService.kt` (message « node factory/dashboard/server.mjs »),
   `factory/dashboard/run-routes.mjs:250`, `factory/dashboard/composition-root.mjs:1035`,
   `factory/README.md`, `factory/PERSISTENCE_SWITCH_ROLLBACK.md:86,105,127,134,153`,
   `factory/WORKFLOW_PROJECTION.md:292`.
4. **factory-service lance B** : `ForgeProperties.kt:18` (`run-entry = factory/run.mjs`) et
   `LegacyRunService.kt:219-226` spawn `node factory/run.mjs <workflow>` avec cwd = dossier de
   `run.mjs`. **Ne jamais déplacer/supprimer `factory/run.mjs`, `factory/workflows/`,
   `factory/diagnostics/`, `factory/lib/`, `factory/runs/`** : ce sont les points d'entrée du service.
5. **Gate IPC** : `LegacyRunService.kt:230` génère un `gateIpcSecret` par child, mais la propagation
   `FACTORY_GATE_IPC_SECRET` vers l'environnement du child et l'écriture des `*.gate-reply` sont à
   vérifier/compléter **avant** de supprimer `dashboard/run-routes.mjs`.
6. **CI** : `.github/workflows/validate.yml` n'exécute que `nx affected` (lignes 38-39, 76, 82).
   `factory/` **n'est pas un projet Nx** (aucun `factory/project.json`) → supprimer A ne casse pas la CI,
   mais **ne protège pas B** non plus. `factory-service/project.json` **est** un projet Nx
   (`platform:jvm`) → ses tests tournent via `pnpm nx test factory-service`.
7. **Tests B non exécutés automatiquement** : `factory/tests/*.mjs` se lancent à la main
   (`node factory/tests/xxx.mjs`, convention exit 0/1, cf. `factory/tests/README.md`). Aucun agrégateur.
   W6b doit donc les lancer explicitement.
8. **Pas de justfile / Makefile / scripts racine** référençant `factory/dashboard` (recherche exhaustive :
   `scripts/`, `dev_tools/`, `tools/`, `setup.sh`, `package.json`, `pnpm-workspace.yaml`, `nx.json` → rien).
   Le seul couplage CI/outillage est le spawn factory-service + la doc.
9. **`factory/dashboard/js/**` importe `../src/domain/identity/index.ts` en runtime** via
   `composition-root.mjs:89` et `http-utils.mjs:28` (extension `.ts` importée directement, Node ≥22.18).
   La suppression du serveur supprime cet import racine ; l'UI n'en dépend pas.
10. **`factory/oracles/run-frontend-verification.mjs`** importe `lib/forge-front-oracle-resolution.mjs`
    (chaîne forge) → ne pas couper forge sans traiter cet oracle (§4.2).
11. **`factory/infra/migrations` n'est référencé nulle part au runtime** (seulement compose + README) ;
    sans risque technique, mais à nettoyer pour éviter une seconde source de vérité des migrations.
12. **Consommateurs produit/agents sur `:3141`** (§5.6) : `apps/client/proxy.conf.json:11-15`,
    `libs/integration/src/lib/factory.tools.ts`, `agentos/.../factorybridge/FactoryBridgeConfig.kt:31`,
    `forge_bmad/coday/**`. Ils ne cassent pas la CI mais perdent leur backend après la coupe.
13. **Bundle runtime versionné + tests** : `factory/runtime/factory-operational.mjs` est **git-tracké**
    (`git ls-files factory/runtime/` → présent) tandis que `factory/dist/` est ignoré. Le rebuild doit
    être committé. `factory/infra/verify-restore.sh:56,239` référence aussi le bundle.
14. **`factory/toolchain/package.json:9,11`** : `test:operational` / `verify:generated` lancent
    `../tests/typescript-factory-operational.mjs` qui lit `src/` → à conserver après trim.
15. **`docs/coday-auth-usergroups-recon.md`** documente l'identité du dashboard (`http-utils.mjs`,
    `artifact-admin-routes.mjs`) → à réviser.

### 6.2 Ordre de coupe recommandé pour W6b (séquence sûre)

**Principe : brancher le cockpit d'abord, vérifier, puis supprimer le serveur, puis réduire `src/`/`lib/`.**

1. **Préparation (aucune suppression)**
   - Confirmer le périmètre forge (§4.2) et le devenir du worker-runtime (§4.5).
   - Ajouter à factory-service : hébergement statique du cockpit (option 1 §5.2) **ou** CORS + baseUrl,
     et l'endpoint manquant `GET /api/config` (ou équivalent).
2. **Bascule cockpit**
   - Servir `cockpit.html`/`css/`/`js/` via factory-service, repointer `/api/**` vers factory-service.
   - **Vérifications** : `curl` des routes principales + SSE projection ; smoke manuel de chaque vue
     (`/runs`, `/launch`, `/detail`, `/projection`, `/forge`, `/admin`) ; lancer un workflow via
     `POST /api/factory/workflows/:id/run` et vérifier que factory-service spawn bien `factory/run.mjs`.
3. **Gate & runs legacy côté Kotlin**
   - Compléter `LegacyRunService`/`LegacyRunController` : propagation `FACTORY_GATE_IPC_SECRET`,
     écriture `*.gate-reply`, lecture JSONL. Vérifier un gate humain de bout en bout sur `us-loop`.
4. **Suppression du serveur Node**
   - Supprimer §2.1 (serveur + routes + composition-root + openapi.json), **en conservant** les assets
     cockpit déplacés/servis.
   - Supprimer les tests A §2.4.
   - **Vérifications** : `node factory/run.mjs diagnostic backend-oracle-check` (aucun agent) puis
     `node factory/run.mjs diagnostic agentos-smoke` ; démarrage de factory-service ; smoke cockpit.
5. **Réduction du bundle `src/`**
   - Tronquer `src/entrypoints/factory-operational.ts` (§3.3), élaguer les barrels, supprimer les
     sous-arbres A §2.3.
   - `node factory/toolchain/build.mjs`, inspecter `factory/dist/factory-operational.meta.json`
     (inclusion unique de l'entrypoint) et **committer le bundle régénéré**.
   - **Vérifications** : `node factory/tests/test-typescript-factory-operational.mjs`,
     `test-worker-runtime-{core,entrypoint}.mjs`, `test-lease-protocol.mjs`, `test-shutdown*.mjs`,
     `test-us-loop.mjs`, `test-fix-loop*.mjs`, `test-diagnostic-synthesis.mjs`, `test-oracle*.mjs`.
6. **Suppression des modules `lib/` A**
   - Supprimer §2.2 (facades control-plane). **Vérifier d'abord** avec
     `grep -rn "lib/<module>" factory/{run.mjs,workflows,diagnostics,oracles}` = vide.
7. **Nettoyage**
   - Supprimer `factory/infra/migrations` (§4.8) et mettre à jour `infra/README.md`/compose.
   - Réécrire/supprimer la doc A §4.9 ; retirer les références textuelles §6.1.3.
   - **Vérification finale** : `pnpm nx affected -t test lint build` (n'inclut pas factory/ mais valide
     factory-service + le produit) **et** la campagne `node factory/tests/*.mjs` de B.

### 6.3 Ce qu'il ne faut surtout pas casser

- `factory/run.mjs`, `factory/lib/{agentos,registry,oracle,plan,domains,oracle-command,oracle-baseline,review*,diagnostic-synthesis,shutdown,adversarial-review}.mjs`,
  `factory/workflows/*`, `factory/diagnostics/*`, `factory/oracles/*`, `factory/runs/`.
- `src/lib/{active-case,registry}.ts`, `src/adapters/agentos/**`, `src/application/{shutdown,agentos-operations}.ts`,
  `src/domain/oracle/**`, `src/application/oracle/{oracle-command,oracle-executor,oracle-baseline}.ts`,
  `src/adapters/process-shutdown.ts`, `src/adapters/jira/jira-client.ts`,
  `src/entrypoints/worker-runtime.ts` (+ ses deps SQL lease/worker/work-unit), l'entrypoint bundle (tronqué),
  `factory/toolchain/**`, `factory/runtime/factory-operational.mjs` (régénéré).

### 6.4 Vérification des migrations (demande §5 du brief)

- `diff` exécuté : **V1→V7 identiques** entre `factory/infra/migrations/` et
  `factory-service/src/main/resources/db/migration/`.
- `factory-service` possède en plus `V8__delivery.sql` et `V9__workflow.sql` ; **`factory/infra/migrations/`
  s'arrête à V7** → après suppression du Node, ce dossier devient **orphelin** (aucune référence runtime ;
  seulement `factory/infra/docker-compose.yml:47` et `factory/infra/README.md:112`).

---

## Annexe — Méthode & sources

- Répertoires analysés : `factory/{dashboard,src,lib,workflows,diagnostics,oracles,tests,infra,toolchain,runtime}`,
  `factory-service/src/**`, `.github/workflows`, `specs/`, `docs/`.
- Fichiers-clés lus : `factory/README.md`, `factory/{ARCHITECTURE,AUTHORITY_SOURCES,DEPENDENCY_MATRIX,ADR_TYPESCRIPT_MIGRATION}.md`,
  `factory/lib/README.md`, `factory/run.mjs`, `factory/toolchain/build.mjs`,
  `factory/src/entrypoints/{factory-operational,worker-runtime}.ts`, `factory/dashboard/{server,composition-root}.mjs`,
  `factory/dashboard/js/services/{api-client,sse-client}.mjs`, `factory-service/**/LegacyRunService.kt`,
  `factory-service/src/main/resources/application.yml`, `factory-service/src/main/kotlin/.../config/WebConfig.kt`.
- Réverse-imports calculés par `grep -rl` sur les basenames de `lib/*.mjs` et `src/**`.
- Aucune écriture hors de ce fichier de handoff.
