# Conception — Porter l'instrument de validation Node en Kotlin (W8)

> Statut : document de conception (recherche en lecture seule). Aucun code n'est modifié par ce
> document.
> Source primaire : analyse scout W8 (`scout_findings.md`, session `185d4ff7`), matérialisée ici.
> Portée du portage : l'**instrument de validation** actuellement implémenté en Node sous `factory/`
> (`factory/run.mjs` + workflows + `lib/` + diagnostics + oracles + worker-runtime + bundle
> opérationnel), à porter en Kotlin.

---

## §1 Résumé exécutif

### 1.1 Faisabilité, ampleur, risque principal

- **Faisabilité** : élevée, mais non triviale. L'essentiel des primitives est mécanique en Kotlin
  (`ProcessBuilder`, `Files`, `MessageDigest`, client HTTP `RestClient`), et plusieurs briques sont
  **déjà portées** côté control plane (lecture JSONL, définition d'oracles déclaratifs, proxy AgentOS
  read-only, plugin forge PF4J avec ports injectables).
- **Ampleur** : importante. Le cœur à porter comprend `factory/run.mjs`, deux workflows majeurs
  (`fix-loop.mjs` 591 lignes, `us-loop.mjs` 2472 lignes), une dizaine de modules `lib/` autoritaires, un
  client AgentOS complet (mutations + quiescence), le registre JSONL en **écriture**, le protocole de
  gates humains, le shutdown SIGTERM, les diagnostics, les oracles et le worker-runtime.
- **Risque principal** : perdre l'**invariant « artefact autonome »** — c'est-à-dire que l'instrument se
  mette à dépendre de la santé de ce qu'il mesure (produit Coday/AgentOS, `node_modules`, `pnpm`/Nx,
  DB PostgreSQL du control plane). Le second risque majeur est le **transport du protocole de gates
  humains** (§3.5, §4.3). Autres risques : fidélité de `runAgentTurn` (quiescence multi-tours, F7),
  parité du format JSONL déjà consommé par `LegacyRunService`, et gestion des timeouts/groupes de
  process pour les oracles longs (Gradle/Nx).

### 1.2 Invariant central : « artefact autonome »

Invariant fondateur, verbatim :

- `factory/README.md:13` — **« L'instrument ne doit pas dépendre de la santé de ce qu'il mesure. »**
  Justification : si `pnpm install` / `node_modules` / `package.json` racine est cassé, `factory/` doit
  néanmoins fonctionner ; uniquement modules natifs Node (`fs`, `path`, `child_process`, `crypto`) +
  `fetch`.
- `factory/ARCHITECTURE.md:5` — « Sa crédibilité dépend de sa capacité à fonctionner lorsque le produit
  qu'elle mesure est partiellement ou totalement en panne. »
- `factory/ARCHITECTURE.md:36` — « Elle ne peut pas utiliser les dépendances installées du produit pour
  se construire ou démarrer. […] Leur panne est un résultat mesuré, pas une panne de bootstrap de
  l'instrument. »
- `factory/DEPENDENCY_MATRIX.md:28` — `Runtime Factory → Code interne AgentOS / classes Kotlin` :
  **Interdit**, « La frontière est le contrat réseau. »
- `factory/DEPENDENCY_MATRIX.md:29-30` — `node_modules` interdit ; `pnpm`/`Nx` pour démarrer la Factory
  interdit.
- `factory/DEPENDENCY_MATRIX.md:38-39` — le produit mesuré ne doit pas importer l'instrument ;
  AgentOS non plus.
- `factory/DEPENDENCY_MATRIX.md:48-60` (section « Autonomie runtime » / « Build isolé ») — artefact
  copiable, lançable sans dépôt source ni installation JS ; build avec graphe de dépendances isolé.

### 1.3 Les 5 invariants de comportement de l'instrument

1. **Fail par défaut** — `factory/README.md:148` : « Chaque phase est écrite immédiatement dans le
   registre avec `status: 'fail'`. Elle ne devient `pass` que si `passPhase()` est appelée
   explicitement. » (`docs/study/software-factory.md:173`.)
2. **L'agent ne connaît pas son oracle** — `factory/README.md:155` ; verdict = `exitCode === 0`, jamais
   une chaîne dans la sortie (`docs/study/software-factory.md:203-205`, `factory/lib/README.md`
   oracle.mjs).
3. **Aucune sortie LLM dans le registre** — `factory/README.md:164` ;
   `docs/study/software-factory.md:71,179`. Faits seulement : noms, statuts, durées, fichiers, codes de
   sortie, compteurs.
4. **Colocalisation obligatoire** — `factory/README.md:122-136` ;
   `docs/study/software-factory.md:189-193`. `preflightWorkspace` : `rootPath` de chaque intégration
   `FILE_ACCESS` de l'éditeur doit être **exactement** `FACTORY_ROOT` (égalité stricte), fail-closed sur
   l'invérifiable.
5. **L'orchestrateur ne pose jamais de question** — `factory/README.md:138`. Il échoue au lieu de
   demander (blocage silencieux sinon). Le rapport d'échec porte l'inventaire des agents.

Autres invariants opérationnels :

- `subAgents === []` pour tout rôle de phase (parallélisme inconditionnel de `DelegationTool`) —
  `factory/README.md` (tableau capacités), `software-factory.md:4.5`.
- Fin de tour = `CaseStatusEvent` `IDLE|KILLED|ERROR`, **pas** `AgentFinishedEvent` ; deux transitions
  successives RUNNING puis quiescence (`factory/lib/README.md` agentos.mjs ; `software-factory.md:5.2`,
  F7).
- Case neuf par tentative (pas de réutilisation : le récit du tour précédent concurrence les faits) —
  `factory/workflows/fix-loop.mjs` (en-tête), `software-factory.md:3.5`.
- Le plan voyage en mémoire, jamais sur disque (sinon `snapshotDiff` le compte comme écriture) —
  `factory/README.md` « Le plan voyage en mémoire », `software-factory.md:3.4`.
- Garde A8 « succès vide » : `exitCode === 0` mais `tasks.executed === 0` = échec de l'instrument, pas
  verdict.

### 1.4 Sources — source manquante et ambiguïté de l'appellation « B »

- **Source manquante** : `docs/factory-node-cut-inventory.md` **n'existe pas** dans le dépôt (ni ailleurs
  sous `/work`). Recherches effectuées : `find . -iname "*node-cut*" -o -iname "*cut-inventory*"` →
  vide ; `grep -rln "À GARDER\|instrument B\|node-cut" app_docs docs factory --include=*.md` → vide ;
  `git log --all -- docs/factory-node-cut-inventory.md` → vide. Le prompt cité comme source (§3 À GARDER
  = instrument B, §6 pièges) est donc introuvable. Le présent design s'appuie à la place sur
  `factory/README.md`, `factory/ARCHITECTURE.md`, `factory/lib/README.md`, `factory/DEPENDENCY_MATRIX.md`,
  `factory/AUTHORITY_SOURCES.md` et `docs/study/software-factory.md`, qui portent l'essentiel des
  invariants. À mentionner explicitement comme source manquante.
- **Reste Node hors périmètre B** : il subsiste un **dashboard Node** (`factory/dashboard/`, 44 fichiers,
  dont `run-routes.mjs`, `agentos-proxy.mjs`, `composition-root.mjs`,
  `factory-frontend-run-routes.mjs`) : hors périmètre B, mais encore du Node à terme. La partie
  « runs + SSE + proxy + review-gate » a déjà été portée (`LegacyRunService`, `AgentOsProxyController`).
- **Ambiguïté du label « B »** : le prompt parle de « l'instrument B (le "B" des docs) ». **Dans le
  dépôt, aucun document ne nomme l'instrument Node « B »** : la lettre « B » y désigne le **jalon
  Factory B / vagues B1–B6** (persistance PostgreSQL + control plane `factory-service`), pas le runtime
  Node. Aucune occurrence de « instrument B », « instrument de validation », « Node cut », « §3 À
  GARDER », « §6 pièges » dans `docs/`, `app_docs/`, `factory/`, `specs/`, `product/` ni dans
  l'historique git. → Le livrable définit explicitement son périmètre (l'instrument = `factory/run.mjs` +
  workflows + lib + diagnostics + oracles + worker-runtime + bundle) et signale que l'appellation « B »
  vient de l'opérateur, pas du code.
- **Équivalent de l'inventaire manquant** : le répertoire `specs/` (78 fichiers) contient les specs de
  migration, qui tiennent lieu d'inventaire de découpe Node→Kotlin. Les plus pertinentes pour W8 :
  - `specs/46f5884c_migrate_domain_modules_tranches_1_4.md`
  - `specs/8d9014cd_migrate_workflow_domain_tranche_1.md`
  - `specs/a261df6d_migrate_oracle_model_tranche_6.md`
  - `specs/8635a59f_migrate_work_environment_tranche_7.md`
  - `specs/652577d8_migrate_delivery_domain_tranche_8.md`
  - `specs/38e7c542_migrate_forge_bmad_tranche_9.md`
  - `specs/56405cbd_factory_sdk_pf4j_infrastructure.md` (contient le principe « Control Plane
    Isolation »)
  - plus les specs d'agrégats Kotlin (`9c104d1e`, `453164ec`, `5de96b0e`, `5f5e940e`, `48dd9ddf`,
    `cfa7e583`).

---

## §2 Cartographie de l'instrument Node B

### 2.1 Point d'entrée — `factory/run.mjs` (245 lignes)

- **Table de dispatch** `DISPATCH` (lignes ~57-80) : clés `workflow:us-loop`, `workflow:fix-loop`,
  `workflow:forge-epic`, `diagnostic:agentos-smoke`, `diagnostic:backend-oracle-check`, plus alias
  hérités (`alias:smoke`, `alias:verify-back`, `alias:us-loop`, `alias:fix-loop`). Forme canonique
  `node factory/run.mjs <catégorie> <nom>`.
- Charge le module par `await import(modulePath)` puis appelle `module.run(log)` ; exige une fonction
  `run` exportée.
- Compose le shutdown : `createShutdownController({ activeCaseIds, caseTerminator: createAgentOsHttpCaseTerminator(...),
  endCurrentRunOnce, rejectPendingGates: rejectAllPendingGates, warn, exit })` puis
  `installSigtermHandler(...)` (lignes ~200-225). Importe `rejectAllPendingGates` depuis
  `./lib/review-gate.mjs` (legacy autorité) et `createShutdownController`/`getActiveCaseIds`/
  `endCurrentRunOnce`/`processExit` depuis `./runtime/factory-operational.mjs` (bundle généré).
- Sortie : `process.exit(allPass ? 0 : 1)`, imprime `Registre : <filePath>` et `Résultat : PASS|FAIL`.
- `log` (lignes ~90-110) : `phaseStart/phaseEnd/info/error` → à porter en logger Kotlin.
- Handler SIGTERM : tue le(s) case(s) actif(s) via `POST /api/cases/{id}/kill`, écrit `run_end`
  `status: fail` + `checkoutMayBeIntermediate: true`, `exit(1)` (`factory/lib/shutdown.mjs`,
  `software-factory.md:4.8`).

**Contrat de retour / logger** : tout workflow exporte `export async function run(log)` et retourne
`{ allPass: boolean, filePath: string }` ; `run.mjs` charge dynamiquement le module et exige cette
fonction (l.196-204), appelle `module.run(log)` (l.227), puis `markCompleted()` (l.234) et
`process.exit(allPass ? 0 : 1)` (l.242). `log.phaseStart/phaseEnd/info/error`, `kind ∈ {'code','agent'}`.
`startPhase` retourne `{name, _startedAt, run}`. Le logger n'écrit pas le JSONL (c'est le registre). Ces
contrats sont les points d'interface à reproduire à l'identique dans le moteur Kotlin.

### 2.2 Workflows

#### `factory/workflows/fix-loop.mjs` (591 lignes) — boucle acteur/oracle, sans analyste

Imports : `registry`, `oracle` (`runCommand`, `snapshotDiff`, `diffSince`, `countTaskOutcomes`),
`agentos` (`createCase`, `runAgentTurn`, `preflightAgent`, `preflightWorkspace`), `domains`,
`oracle-command` (`buildOracleCommand`).

Constantes : `MAX_ATTEMPTS = 3` (~l.80), `ERROR_LINES_FOR_AGENT = 60`, `TAIL_LINES = 40`,
`START_TIMEOUT_MS = 30s`, `WORK_TIMEOUT_MS = 15min`, `ORACLE_TIMEOUT_MS = 20min`.

Séquence des phases :

```
preflight                 code   preflightAgent + preflightWorkspace(colocalisation)
┌ tentative N (MAX_ATTEMPTS=3) :
│  edit-N                 agent  case NEUF + brief (initial ou fix avec erreurs brutes)
│  verify-<oracle>-N      code   par oracle du domaine ; arrêt au 1er échec
└ échec → N+1
```

Gardes : `wrongAgent` (agentsSelected ne contient pas le nom attendu → fail immédiat),
`turn.status !== 'finished'` → fail, `wroteNothing` (0 modifié + 0 non-tracké) → fail avec fait
`wroteNothing`. Timeout oracle → fail immédiat (pas de tentative suivante). Succès vide
(`passed && tasks.executed===0`) → `failPhase` + `emptySuccess: true` → fail immédiat.

Briefs **rendus par le code** (jamais un modèle), 4 sections : Task, Scope, Done when (dit de NE PAS
compiler/tester), « If this is not the right place » (sortie honorable). La commande d'oracle n'apparaît
jamais dans le brief.

#### `factory/workflows/us-loop.mjs` (2472 lignes) — analyste + éditeur + revue

Imports (l.98-124) : `registry`, `oracle`, `agentos`, `plan` (`parsePlan`, `checkPlanFiles`,
`compareClaims`), `domains`, `oracle-command` (`buildOracleCommand`, `resolveOwnerProjects`), `jira`
(`extractTicketId`/`extractAdfText`/`fetchJiraTicket`), `adversarial-review` (`runAdversarialReview`),
`review-gate` (`emitGateOpen`, `emitOracleGateOpen`, `waitForHumanDecision`), `review-agentos-adapter`
(`makeReviewAgentOps`), `diagnostic-synthesis` (`shouldSynthesizeDiagnostics`, `runDiagnosticSynthesis`,
`routeDiagnosticSynthesis`, `writeDiagnosticSynthesisArtifact`), `oracle-baseline` (`runBaselineOracle`,
`classifyOracleResult`, `buildQuarantineRecord`, `extractOracleDiagnostics`) ; `node:child_process`
(`execSync`) et `node:fs` (`existsSync`).

Constantes : `MAX_FIX_LOOPS = 3` (l.140), `MAX_REVISION_LOOPS = 2` (l.146), `JSON_FIX_ATTEMPTS = 2`
(l.153), `ERROR_LINES_FOR_AGENT = 60` (l.156), `TAIL_LINES = 40` (l.159), `START_TIMEOUT_MS = 30s`
(l.162), `WORK_TIMEOUT_MS = 15min` (l.165), `ORACLE_TIMEOUT_MS = 20min` (l.168).

Phases (littéraux, avec n° de ligne de `startPhase`) :

```
fetch-ticket                     code  Jira si FACTORY_TICKET, sinon skip           (l.785)
preflight                        code  2 rôles + colocalisation éditeur            (l.860)
┌ révision R (MAX_REVISION_LOOPS=2) :
│  analyse-R                     agent analyste read-only → plan JSON en mémoire    (l.954)
│    └ budget JSON_FIX_ATTEMPTS=2 reformulations si JSON invalide                  (l.1029-1051)
│  plan-gate-R                   code  chaque plan.files existe (existsSync)        (l.1104)
│  baseline-<oracle>-R           code  oracle baseline avant édition                (l.1162)
│  ┌ tentative T (MAX_FIX_LOOPS=3) :
│  │  edit-R-T                  agent éditeur reçoit le plan + erreurs             (l.1288)
│  │  verify-<oracle>-R-T       code  oracles ; arrêt 1er échec                    (l.1464)
│  │  diagnostic-synthesis-...  agent read-only si échec non exploitable           (l.1762)
│  └ échec → T+1 ; épuisement → R+1
│  claims-gate-R                 code  diff réel vs fichiers annoncés (fait)        (l.1911)
└
adversarial-review               agent revue(s) (revue de sortie)                  (l.1982)
edit-review-retry-N              agent retry post-revue (borné MAX_FIX_LOOPS)       (l.2118)
verify-<oracle>-review-retry-N   code                                              (l.2213)
adversarial-review-retry         agent                                             (l.2343)
```

Notes : la boucle de revue est branchée dans `us-loop.mjs` (contrairement à ce qu'écrivait
`software-factory.md:7.1`, à corriger mentalement). Deux points d'arrêt humains : `pending_question`
(`queryUser` non répondu) et `wroteNothing` (`software-factory.md:3.2`). Garde `wroteNothing` à l.1380.

#### `factory/workflows/forge-epic.mjs` (33 lignes) — quasi orphelin

Une seule phase `create-epic-run` : lit `FACTORY_FORGE_RUN_REQUEST`/`FACTORY_FORGE_RUN_FIXTURE`, appelle
`createEpicRun` de `lib/forge-ledger.mjs`, crée un EpicRun, G1 `waiting_human`, retourne
`allPass: true`. **Aucun agent, aucun oracle.** La logique Forge/BMAD est déjà portée en Kotlin
(`factory-forge-plugin/`) : `ForgeRunService`, `ForgeGateService`, `StoryOperationService`,
`WorkflowSync`, `ForgeFrontOracleResolution`.

### 2.3 `factory/lib/*.mjs` — surfaces et dépendances

Deux familles :

- **Façades stateless de réexport** vers le bundle généré `factory/runtime/factory-operational.mjs`
  (construit depuis `factory/src/entrypoints/factory-operational.ts` par `factory/toolchain/build.mjs`) :
  `registry.mjs`, `oracle.mjs`, `oracle-command.mjs`, `oracle-executor.mjs`, `agentos.mjs`,
  `shutdown.mjs` (composition), `worker-runtime.mjs`, et la plupart des `workflow-*`, `delivery-*`,
  `work-unit-environment*`, `forge-*.mjs`.
- **Modules à état legacy conservés** : `review-gate.mjs` (registre de gates + IPC), `plan.mjs`,
  `domains.mjs`, `jira.mjs`, `review*.mjs`, `adversarial-review.mjs`, `diagnostic-synthesis.mjs`,
  `oracle-baseline.mjs`, `git-worktree.mjs`, `coday-config.mjs`, `interval-aggregation.mjs`,
  `workflow-relations.mjs`, `workflow-timing-projector.mjs`, `forge-roots.mjs`, `forge-ledger.mjs`,
  `forge-spec.mjs`, `forge-g2.mjs`, `forge-workflow-adapter.mjs`, `factory-frontend-runner.mjs`,
  `factory-frontend-composition.mjs`, `factory-review-package.mjs`, `factory-agent-step-executor.mjs`.

Rôle / invariants par module (détail exhaustif dans `factory/lib/README.md`, 464 lignes — à relire) :

- `registry.mjs` → `createRun` / `startPhase` (écrit `fail` immédiatement) / `passPhase` / `failPhase` /
  `endRun` / `endCurrentRunOnce` / `getCurrentRun`. Append-only JSONL `factory/runs/<runId>.jsonl`,
  `appendFileSync`, jamais de réécriture, faits sous clé `facts` (non réinscriptibles) — état dans
  `factory/src/lib/registry.ts` inclus dans le bundle.
- `oracle.mjs` → façades `runCommand`, `countTaskOutcomes`, `snapshotDiff`, `diffSince`. Exécution réelle
  dans `factory/src/application/oracle/oracle-executor.ts` (voir §3.1).
- `oracle-command.mjs` → `resolveOwnerProjects`, `resolveBuildHosts`, `buildOracleCommand` (résolution des
  projets Nx propriétaires, `--skip-nx-cache`, injection des hôtes buildables). Logique dans
  `factory/src/application/oracle/oracle-command.ts`.
- `domains.mjs` → oracles par domaine en dur : `back` (1 oracle Gradle
  `./gradlew :agentos-service:build --rerun-tasks --console=plain`, cwd `agentos`), `front` (oracle
  `build` Angular + oracle `tests` `filesArg:true` ; oracle `types` commenté/désactivé). Surcharges env
  `FACTORY_COMMAND_*`, `FACTORY_CWD_*`, `FACTORY_FRONT_BUILD_HOST_MAP`, `FACTORY_ROOT`.
- `plan.mjs` → `extractJsonFragment`, `isSafePath`, `parsePlan`, `checkPlanFiles` (`existsSync`),
  `compareClaims`. Pur, sauf lecture disque de `checkPlanFiles`. Plan = `{files[], doneWhen, steps?}`.
- `agentos.mjs` → façade vers l'adaptateur TS `factory/src/adapters/agentos/*` (voir §3.3). Exports :
  `createCase`, `postMessage`, `bindFactoryStepResult`, `getCase`, `listEvents`, `killCase`,
  `listAgents`, `preflightAgent`, `listIntegrations`, `preflightWorkspace`, `preflightWritableWorkspace`,
  `preflightReadOnlyWorkspace`, `runAgentTurn`.
- `review.mjs` → `parseReviewResult`, `aggregateReviews`, `toReviewFacts` (politique : invalid-output →
  reject ; blocage veto → reject ; major → request-changes ; sinon approve ; champs
  `revision/attempt/...` interdits dans l'output reviewer ; prose LLM jamais dans les facts).
- `review-engine.mjs` → `runReview` parallèle, `agentOps` injecté, caseIds en `Map` locale, kill en
  best-effort, préflight lecture-seule strict.
- `review-gate.mjs` → **protocole IPC fichiers** (voir §3.5).
- `diagnostic-synthesis.mjs` → interprétation read-only bornée d'un oracle échoué (allow-list read-only,
  réponse JSON `actionable|ambiguous|insufficient-evidence`, artefact JSON
  `schemaVersion/rawOutput/sha256`).
- `worker-runtime.mjs` / `src/entrypoints/worker-runtime.ts` → `WorkerRuntime` (boucle claim/lease) +
  `createLocalWorkerRuntime`/`runLocalWorker` + `createDemoWorkExecutor` ; câble les repos SQL
  lease/work-unit/worker. Le domaine lease/workunit/worker est **déjà porté** côté `factory-service`
  (`lease/`, `worker/`, `workunit/`).

**Façades vs modules d'autorité dans `factory/lib/`** (précision) : parmi les `.mjs`, distinguer :

- **Façades stateless** (réexport pur du bundle `factory/runtime/factory-operational.mjs`, aucune logique
  à porter en tant que telle) : `registry.mjs`, `oracle.mjs`, `oracle-command.mjs`, `oracle-executor.mjs`,
  `agentos.mjs`, `shutdown.mjs`, `worker-runtime.mjs`, et la plupart des
  `workflow-*`/`delivery-*`/`work-unit-environment*`/`forge-*`. `oracle-definition.mjs` est une façade
  **avec un peu de câblage écrit main** (`createOracleDefinitionRepository`, lignes ~22-33).
- **Modules encore autoritaires** (logique Node réelle à porter, non déléguée au bundle) :
  `domains.mjs`, `plan.mjs`, `review.mjs`, `review-engine.mjs`, `review-gate.mjs`,
  `adversarial-review.mjs`, `diagnostic-synthesis.mjs`, `oracle-baseline.mjs`, `jira.mjs`,
  `workflow-definition-registry.mjs`, `workflow-relations.mjs`, `workflow-projection.mjs`,
  `workflow-resume-dispatch-store.mjs`, `workflow-timing-projector.mjs`, `git-worktree.mjs`,
  `coday-config.mjs`, `forge-roots.mjs`, `forge-ledger.mjs`, `forge-spec.mjs`, `forge-g2.mjs`,
  `forge-workflow-adapter.mjs`, `factory-frontend-runner.mjs`, `factory-frontend-composition.mjs`,
  `factory-review-package.mjs`, `factory-agent-step-executor.mjs`, `interval-aggregation.mjs`.

Conséquence pour W8 : le portage doit cibler les **sources TS** (`factory/src/**`) pour tout ce qui est
déjà dans le bundle (oracle executor/command/domain/baseline, agentos adapters, registry/active-case,
shutdown), et les **`.mjs` autoritaires** pour le reste (plan, review*, gates, diagnostics, domains,
jira, forge-*, frontend-runner). Rappel : `oracle-definition`/`oracle-definition-registry` et l'agrégat
oracles Kotlin existent déjà côté `factory-service` (voir §4.3) — vérifier la parité avant de re-porter.

Détail confirmé (façade `oracle-command.mjs`) : `buildOracleCommand` renvoie une sentinelle
`NoHostResult` qui **doit** devenir un signal d'infrastructure (gate humain), jamais un succès vide —
garde A8.

### 2.4 Diagnostics (`factory/diagnostics/`)

- `agentos-smoke.mjs` : phase `git-status` (code), phase `ping` (agent, `createCase` + `runAgentTurn`
  avec `startTimeoutMs:30s`, `workTimeoutMs:3min`, `snapshotDiff`/`diffSince`).
- `backend-oracle-check.mjs` : phase unique `back-build` (code, `domains.back`, timeout 20 min, aucun
  agent).

### 2.5 Oracles (`factory/oracles/`)

- `forge-frontend-verification@1.0.0.json` : définition déclarative (`argv`)
  `["node","factory/oracles/run-frontend-verification.mjs"]`, `cwd: repo-root`, `domain: frontend`,
  `success: {rule: "exit-code", requireWork: true}`, `timeoutMs: 1800000`, `applicable`
  stepIds/workflowTypes.
- `run-frontend-verification.mjs` : `spawnSync` de `pnpm nx run-many --target=build ...` puis
  `--target=<tests>`, `process.exit(status)`.
- **Contrat de définition déjà porté en Kotlin** :
  `factory-service/.../oracle/domain/OracleDefinition.kt` (schemaVersion 1, `argv`, `cwd=repo-root`,
  `timeoutMs`, `success.rule=exit-code`, `applicable`) + `OracleDefinitionValidator` (rejette shell /
  flags d'évaluation shell) + `OracleDefinitionRegistry` (charge `factory/oracles` par défaut,
  `FACTORY_ORACLE_DEFINITIONS_ROOT`).

### 2.6 Bundle opérationnel

- Source unique : `factory/src/entrypoints/factory-operational.ts` (réexporte active-case, registry,
  domaines workflow/evidence/interaction/agent-attempt/environment, storage-kernel, ports+adapters de
  persistance, shutdown, agentos, oracle, work-unit environment, delivery, forge-bmad, artifact,
  worker-runtime).
- Généré : `factory/runtime/factory-operational.mjs` par `factory/toolchain/build.mjs` (esbuild ESM,
  target node22.12, `external: ['node:*']`, metafile+sourcemap sous `factory/dist/`).
- Superficie instrument réellement utilisée par `run.mjs` + workflows : active-case, registry,
  shutdown/terminator, `agentos-operations` (createCase/postMessage/runAgentTurn/preflight/kill), oracle
  (domain+executor+command+baseline), artifact-store (facultatif).

### 2.7 Index des fichiers-clés

Instrument B :

- `factory/run.mjs` (dispatch, shutdown, exit)
- `factory/workflows/fix-loop.mjs` (591 l.), `factory/workflows/us-loop.mjs` (2472 l.),
  `factory/workflows/forge-epic.mjs` (33 l.)
- `factory/lib/README.md` (doc d'invariants des 12+ modules), `factory/lib/registry.mjs`,
  `factory/lib/oracle.mjs`, `factory/lib/oracle-command.mjs`, `factory/lib/plan.mjs`,
  `factory/lib/domains.mjs`, `factory/lib/agentos.mjs`, `factory/lib/review-gate.mjs`,
  `factory/lib/shutdown.mjs`, `factory/lib/review*.mjs`, `factory/lib/adversarial-review.mjs`,
  `factory/lib/diagnostic-synthesis.mjs`, `factory/lib/oracle-baseline.mjs`, `factory/lib/worker-runtime.mjs`
- `factory/src/application/oracle/oracle-executor.ts`, `factory/src/application/oracle/oracle-command.ts`,
  `factory/src/lib/registry.ts`, `factory/src/lib/active-case.ts`,
  `factory/src/adapters/agentos/agentos-http-client.ts`, `…/agentos-runtime-adapter.ts`,
  `…/agentos-dtos.ts`, `…/agentos-http-case-terminator.ts`
- `factory/src/entrypoints/factory-operational.ts`, `factory/src/entrypoints/worker-runtime.ts`,
  `factory/toolchain/build.mjs`, `factory/runtime/factory-operational.mjs` (généré)
- `factory/diagnostics/agentos-smoke.mjs`, `factory/diagnostics/backend-oracle-check.mjs`
- `factory/oracles/forge-frontend-verification@1.0.0.json`, `factory/oracles/run-frontend-verification.mjs`
- `factory/tests/*.mjs`, `factory/tests/fixtures/oracle-process/*`

Docs :

- `factory/README.md` (l.13, 122-136, 138, 148, 155, 164, 176-215), `factory/ARCHITECTURE.md` (l.5, 36),
  `factory/DEPENDENCY_MATRIX.md` (l.28-39, 48-60), `factory/AUTHORITY_SOURCES.md`,
  `factory/WORKFLOW_PROJECTION.md`, `factory/FORGE_WORKFLOW_ADAPTER.md`,
  `docs/study/software-factory.md` (l.69-71, 173-205, 221), `app_docs/9c104d1e_oracles-aggregate.md`,
  `app_docs/453164ec_forge-bmad-runs.md`, `app_docs/21e0b87f_factory-forge-plugin.md`,
  `app_docs/56405cbd_factory-sdk-pf4j.md`, `app_docs/084074d1_factory-cockpit-run-launch.md`,
  `app_docs/78037669_worker-runtime-core.md`, `app_docs/dbc99c6c_worker-runtime-local.md`
- **Absent** : `docs/factory-node-cut-inventory.md` (§3/§6 cités par le prompt).

Kotlin (control plane) :

- `factory-service/src/main/kotlin/io/whozoss/factory/runs/service/LegacyRunService.kt`,
  `runs/config/LegacyRun{Properties,Configuration}.kt`, `runs/web/LegacyRun{,Sse}Controller.kt`
- `factory-service/.../oracle/domain/OracleDefinition.kt`, `…/oracle/registry/OracleDefinitionRegistry.kt`,
  `…/oracle/service/OracleExecutionService.kt`
- `factory-service/.../proxy/{AgentOsProxyClient,HttpAgentOsProxyClient,ProxyProperties}.kt`
- `factory-service/build.gradle.kts`, `factory-service/settings.gradle.kts`,
  `factory-service/src/main/kotlin/io/whozoss/factory/FactoryServiceApplication.kt`,
  `factory-service/src/main/resources/application.yml`
- `factory-sdk/build.gradle.kts`, `factory-sdk/settings.gradle.kts`
- `factory-forge-plugin/build.gradle.kts`,
  `factory-forge-plugin/.../service/StoryOperationService.kt` (ports + verdict exitCode),
  `.../domain/ForgeFrontOracleResolution.kt` (ProcessBuilder), `.../domain/WorkflowSync.kt`,
  `.../plugin/ForgePlugin.kt`

---

## §3 Points durs du portage Kotlin

### 3.1 Exécution shell des oracles

- Nature : deux mécanismes distincts.
  - `runCommand(command, {cwd, timeoutMs})` = `spawnSync(command, { shell: true, maxBuffer: 200MB })`,
    verdict `exitCode === 0`, sortie tronquée à 100 000 car.
    (`factory/src/application/oracle/oracle-executor.ts` `runCommand`).
  - `executeOracle(definition, …)` = `spawn(argv[0], argv.slice(1), { shell:false,
    detached: process.platform!=='win32' })`, capture bornée (16 384 car.), timeout →
    `process.kill(-pid, SIGKILL)`, classification `CLEAN | PRODUCT_REGRESSION | EMPTY_SUCCESS |
    ORACLE_INFRASTRUCTURE` ; verdict jamais dérivé du texte.
- Kotlin : `ProcessBuilder` + `Process.waitFor(timeout, unit)` + `destroyForcibly()` (tuer le groupe de
  process). **Précédents Kotlin existants** : `ForgeFrontOracleResolution.inspectNxProject` fait
  `ProcessBuilder("pnpm","nx","show","project",name,"--json")` ; `StoryOperationService` définit un port
  `OracleCommandExecutor.run(command, cwd, timeoutMs) → OracleCommandResult(exitCode, durationMs,
  timedOut, crashed)` dont le défaut est `UnconfiguredOracleCommandExecutor` (renvoie `crashed`).
  **Aucun runner shell réel n'existe dans le control plane** : `OracleExecutionService` « records the run
  rather than spawning the oracle process » (doc `app_docs/9c104d1e_oracles-aggregate.md`).
- Décision requise : shell vs sans shell. `runCommand(shell:true)` gère les commandes template à chaîne
  (`pnpm nx ...`, `./gradlew ...`) ; `executeOracle` sans shell exige `argv`. Le portage doit reproduire
  les deux (template string→shell pour `domains`, argv sans shell pour définitions JSON). Pas de shell
  interpreter dans les définitions (validator Kotlin l'impose déjà).
- Trivial en Kotlin : capture stdout/stderr, exitCode, timeout. Décisions : gestion des groupes de
  process (kill des descendants), encodage, plafond mémoire/buffer.

### 3.2 Snapshot / diff de fichiers (colocalisation, préflight, `wroteNothing`)

- Nature : `snapshotDiff(cwd)` exécute `git diff HEAD --name-only` +
  `git ls-files --others --exclude-standard` (git sert seulement à lister), puis empreinte **SHA-256 du
  contenu** par fichier ; `diffSince` compare. Sentinelle `'unreadable'` si fichier disparu (un
  changement).
- Kotlin : `ProcessBuilder` git + `java.security.MessageDigest("SHA-256")`, `Files.readAllBytes`.
  Trivial. Décision : modèle `OracleSnapshot {modified: Map, untracked: Map}` + delta ; ligne de commande
  git.
- Colocalisation : `preflightWorkspace` compare `rootPath` FILE_ACCESS à `FACTORY_ROOT` (égalité stricte)
  → logique pure, triviale en Kotlin ; nécessite la liste des intégrations via l'API AgentOS.

### 3.3 Client AgentOS (spawn/tours d'agents)

- Nature : `factory/src/adapters/agentos/agentos-http-client.ts` détient **tous les chemins** :
  - `POST /api/cases` → `{id}` ;
  - `POST /api/cases/{id}/messages` (payload `@agent brief`) ;
  - `GET /api/cases/{id}` ;
  - `GET /api/case-events/by-parentId/{id}` ;
  - `POST /api/cases/{id}/kill` ;
  - `POST /api/cases/{id}/interrupt` ;
  - `GET /api/agent-configs/by-parentId/{ns}` ;
  - `GET /api/integration-configs?namespaceId={ns}`.
  - **Endpoint supplémentaire (interne)** : `PUT /internal/factory/cases/:id/step-result-binding`, header
    `x-factory-agentos-secret = FACTORY_AGENTOS_BINDING_SECRET` (binding d'un step-result, utilisé par
    `bindFactoryStepResult`). C'est un endpoint **interne** (hors `/api`) avec un secret partagé.

  Auth header `X-External-User-Id`.
- `agentos-runtime-adapter.ts` : `startExecution`, `terminateExecution`, `preflightAgent`,
  `preflightWorkspace`, `runAgentTurn(caseId, agent, brief, opts)`.
- `runAgentTurn` : publie le caseId dans active-case **avant tout appel réseau**, poll jusqu'à quiescence
  (`CaseStatusEvent` `IDLE|KILLED|ERROR`), exige RUNNING puis quiescence (F7), timeout → kill du case,
  retourne `{status, caseStatus, agentTurns, toolCallCount, failedToolCalls, killedByBudget, anchored,
  llmModels, agentsSelected, message}`.
- Autres constantes du transport : `AGENTOS_URL` défaut `http://localhost:8124`, `FACTORY_USER` défaut
  `benjamin.valdes`, timeout par appel 15 s, header `X-External-User-Id`.
- Kotlin existant : `HttpAgentOsProxyClient` (`factory-service/.../proxy/`) est un `RestClient`
  **read-only** (GET agents / namespace / case-events, résolution rootPath/runStoreRoot). **Il ne fait ni
  createCase, ni postMessage, ni preflight, ni runAgentTurn, ni kill.** Côté plugin,
  `StoryOperationService` attend un port `StoryRuntime` (preflightAgent/preflightReadOnlyWorkspace/
  preflightWritableWorkspace/createCase/runAgentTurn) livré avec `UnconfiguredStoryRuntime` par défaut.
- Décision requise : écrire un client AgentOS Kotlin complet (créer cases, poster messages, poll events,
  kill/interrupt, préflights) — soit par extension de `HttpAgentOsProxyClient`, soit nouveau module.
  **Attention frontière** : `DEPENDENCY_MATRIX.md:28` interdit d'importer les classes Kotlin d'AgentOS ;
  le client doit rester HTTP. Réutilisation possible : le service `agentos/` (Kotlin/Spring) expose déjà
  ces endpoints — vérifier les DTO exacts (`CaseStatusEvent`, événements d'outils) côté `agentos`.

### 3.4 Registre JSONL append-only + fail par défaut

- Nature : `registry.ts` (bundle) écrit `createRun`→`run_start`, `startPhase`→`phase`
  (`status:'fail'`), `passPhase`/`failPhase`→`phase_end`, `endRun`→`run_end`. Append pur, `facts`
  imbriqués non réinscriptibles, runId trié chronologiquement.
- Kotlin partiel **déjà présent** : `LegacyRunService.parseJsonl` / `reconstructPhases` / `summarizeRun`
  / `detailRun` / `listRuns` lisent et projettent le JSONL. **Il manque l'écriture** (registry writer) :
  `registerGate` existe mais n'est jamais appelé (aucun producteur de gate côté Kotlin).
- Décision requise : où vit le registre écrit ? (fichier JSONL `factory/runs/<runId>.jsonl`, chemin
  configurable `factory.runs.dir`). Doit rester le même format pour que `LegacyRunService` continue de le
  lire tel quel. Trivial en Kotlin (`Files.writeString(..., APPEND)`), mais attention concurrence et
  once-only `endRun`.
- Invariant : le nouveau moteur Kotlin doit écrire `fail` par défaut et n'écrire `pass` qu'explicitement.

### 3.5 Gates humains (protocole IPC fichier + secret)

- Nature (`factory/lib/review-gate.mjs`) : le workflow (processus enfant) écrit sur **stdout** une ligne
  JSON `{"__factory_gate":"open", ...}` ; le serveur dashboard validait `FACTORY_GATE_IPC_SECRET` (secret
  par process) + `runId` + `gateInstanceId` (token sûr), puis enregistrait la gate. La réponse humaine
  est écrite par le serveur dans `factory/runs/<runId>.<gateInstanceId>.gate-reply` ; le workflow poll ce
  fichier toutes les `GATE_POLL_MS = 2000`. Décisions allow-listées : `adversarial-review: [retry, ignore,
  fail]`, `oracle: [continue, fail]`. Single-use, pas de timeout (bloque jusqu'à `rejectAllPendingGates()`
  sur SIGTERM). Pas de prose LLM dans les signaux.
- **Trou actuel côté Kotlin** : `LegacyRunService.registerGate` n'est appelé nulle part ; la surface HTTP
  (`GET/POST /api/factory/runs/{id}/review-gate[/reply]`) lit/écrit une `pendingGates` in-memory qui n'est
  jamais alimentée par le stdout du process Node. Donc aujourd'hui le gate humain n'est **pas
  fonctionnel** via le control plane Kotlin.
- Décision requise : si l'instrument devient in-process, le transport « stdout IPC + fichier de réponse »
  disparaît au profit d'un appel direct (le moteur appelle `LegacyRunService.registerGate`) ou d'un bus
  interne. Il faut préserver : secret/instance-id, single-use, pas de prose, allow-list, pas de timeout,
  rejet à SIGTERM. C'est **le point d'archi le moins trivial** avec AgentOS.

#### 3.5.bis Bug de parité sur les gates (à corriger dans le livrable / avant portage)

`factory/lib/review-gate.mjs` définit les signatures à **gateInstanceId** :

- `emitGateOpen(runId, gateInstanceId, reviewResult)` (l.292)
- `emitOracleGateOpen(runId, gateInstanceId, oracleInfo)` (l.332)
- `waitForHumanDecision(runId, gateInstanceId, log, gateType='adversarial-review')` (l.392)

Mais `factory/workflows/us-loop.mjs` les appelle avec des signatures **legacy** :

- `emitGateOpen(theRun.runId, reviewResult)` (l.2085)
- `emitOracleGateOpen(theRun.runId, oracleGateInfo)` (l.1519, 1646, 1851)
- `waitForHumanDecision(theRun.runId, log, 'oracle')` (l.1520, 1647, 1852)
- `waitForHumanDecision(theRun.runId, log)` (l.2086)

Conséquence : `gateInstanceId` reçoit l'objet `reviewResult`/`log` → `reviewResult.outcomes` l.293 throw,
et `join(RUNS_DIR, …)` sur un objet throw. `tests/test-oracle-baseline.mjs` utilise l'ancienne arité
(l.546) alors que `tests/test-review-gate.mjs` utilise la nouvelle (l.221). Le chemin de gate humain est
donc **incohérent dans le Node actuel** — la conception Kotlin doit fixer une signature unique et non
ambiguë (single-use, secret, instance-id) au lieu de reproduire la divergence.

### 3.6 Terminaison gracieuse SIGTERM

- Nature : `shutdown.mjs` + `application/shutdown.ts` du bundle : flag `_shutdownInitiated` posé avant
  tout async ; tue les cases actifs ; écrit `run_end` `fail` + `checkoutMayBeIntermediate: true` ;
  `exit(1)` ; `markCompleted()` désarme.
- Kotlin : `Runtime.getRuntime().addShutdownHook` ou signal handler. Trivial, mais **dépend du modèle
  d'exécution** (in-process dans factory-service : le shutdown de la JVM tuerait le service entier ;
  sous-processus JVM : équivalent direct du `node ...` actuel).

### 3.7 Orchestrateur qui ne pose jamais de question

- Nature : pas d'appel interactif ; `pending_question` et `wroteNothing` terminent le run en `fail` avec
  des faits, l'humain relit le case dans AgentOS et relance.
- Kotlin : trivial (aucune API d'entrée clavier). Décision : conserver strictement `pending_question` →
  fail, et `QUERY_USER` opt-out dans le provisionnement des agents.

---

## §4 Rapport avec `factory-service` (LegacyRunService)

### 4.1 État actuel

- `factory-service/src/main/kotlin/io/whozoss/factory/runs/service/LegacyRunService.kt` : `launchRun`
  fait `ProcessBuilder(nodeExecutable(), runEntry, workflow).directory(runEntry.parentFile)`, lit
  `factory.runs.entry` (défaut `factory/run.mjs`) et `factory.runs.dir` (défaut `factory/runs`), pompe
  stdout/stderr vers SSE (`pump`), suit le process par `pid:<pid>` dans `activeRuns`, `stopRun` →
  `child.destroy()`, `review-gate` in-memory (jamais alimenté), parsing JSONL (`parseJsonl`,
  `reconstructPhases`, `summarizeRun`, `detailRun`, `listRuns`).
- Config : `LegacyRunProperties` (`dir`, `entry`, `agentosUrl`, jira creds) + `LegacyRunConfiguration`
  (bean core, hors plugin Forge). Valeurs par défaut : `factory/runs`, `factory/run.mjs`,
  `http://localhost:8080`.
- Surface HTTP : `LegacyRunController` (`/api/runs`, `/api/factory/runs` list/launch/detail/stop,
  `/api/factory/runs/{id}/review-gate[/reply]`) + `LegacyRunSseController`
  (`/api/runs/{id}/stream`).

### 4.2 Détails exacts du spawn `LegacyRunService`

Preuves :

- Site de spawn : `factory-service/.../runs/service/LegacyRunService.kt:224-226`
  `ProcessBuilder(nodeExecutable(), runEntry, workflow).directory(worker.parentFile)`.
- Exécutable : `:423` `System.getProperty("node.binary") ?: "node"`.
- Args : `[runEntry, workflow]` **seulement** → forme alias legacy `node factory/run.mjs fix-loop` (pas de
  catégorie `workflow|diagnostic`). Divergence avec l'autorité Node `factory/dashboard/run-routes.mjs:286-290`
  qui passe `['workflow'|<'diagnostic'>, workflow]`. Conséquence : `forge-epic` (pas d'alias) et
  `agentos-smoke`/`backend-oracle-check` ne sont pas dispatchables via ce chemin.
- cwd : répertoire parent de `runEntry` (`factory/`).
- stdin : `PIPE` (Node authority : `ignore`).
- **Env non propagé** : `FACTORY_NAMESPACE_ID`, `FACTORY_TASK`, `FACTORY_AGENT`, `FACTORY_SCOPE`,
  `FACTORY_DOMAIN`, `FACTORY_ROOT`, `AGENTOS_URL`, creds Jira, et le `FACTORY_GATE_IPC_SECRET` généré
  (`:228-229`) ne sont **jamais** exportés au process enfant (validés `:184-217`, puis perdus).
  L'autorité Node les injecte (`run-routes.mjs:258-284`).
- Suivi du run : Kotlin clé `activeRuns["pid:<pid>"]` (`:227`) et n'affecte jamais `trackedRunId`
  (déclaré `:46`, jamais lu/écrit) ; aucun thread de polling du nouveau `.jsonl` (autorité Node
  `run-routes.mjs:322-338`). `stopRun(runId)` (`:282`) et `attachSseStream(runId)` (`:390`) cherchent par
  `runId` → un run lancé n'est ni stoppable ni streamable par son runId.
- Gates : `pump` (`:264-280`) bufferise/diffuse les lignes sans parser le signal
  `{"__factory_gate":"open",...}` (autorité Node `run-routes.mjs:352-370`). Cohérent avec le constat
  §3.5 : `registerGate` n'est jamais appelé → **le gate humain est inopérant** dans le control plane
  Kotlin.
- Lectures JSONL : `parseJsonl` `:59-70`, `reconstructPhases` `:76`, `summarizeRun` `:111`, `detailRun`
  `:141`, `listRuns` `:156`. Config : `LegacyRunProperties.kt:19-20` (dir `factory/runs`, entry
  `factory/run.mjs`), `application.yml:35-36`.
- Format d'écriture du registre (autorité Node) : `factory/runtime/factory-operational.mjs` records
  `run_start` / `phase` / `phase_end` / `run_end`, append-only JSONL — à préserver à l'identique pour que
  les lectures Kotlin continuent de fonctionner.

**Implication de conception** : la bascule (§4.3 option B) doit corriger ces écarts (catégorisation des
args, propagation d'env dont le secret de gate, suivi runId, parsing des signaux de gate) **en même
temps** que le remplacement du binaire — sinon la parité fonctionnelle avec l'instrument Node est déjà
rompue.

### 4.3 Structure Gradle / déployabilité

- `factory-service` : **application Spring Boot unique** (`bootJar` → `factory-service.jar`), Java 25.
  Dépendances runtime lourdes : Spring Web/Actuator/Data-JDBC, Flyway, PostgreSQL, springdoc. Main :
  `io/whozoss/factory/FactoryServiceApplication.kt`. **settings.gradle.kts : indépendant d'AgentOS**
  (catalogue de versions local ; `includeBuild("../factory-sdk")`).
- `factory-sdk` : bibliothèque **Spring-free** (PF4J + Jackson annotations + Kotlin stdlib), publiée
  `mavenLocal()`. C'est le précédent d'un module Kotlin léger et autonome.
- `factory-forge-plugin` : **plugin PF4J** JAR, `compileOnly` de l'hôte + SDK (ne bundle rien), déployé
  par `deployPlugin` dans `factory-service/plugins/`.
- Il **n'existe pas** de module « instrument » isolé ; le seul artefact lançable est `factory-service.jar`
  (qui exige PostgreSQL pour booter) et le plugin (qui exige l'hôte).
- Précédent clé : `factory-service/settings.gradle.kts` prouve qu'on peut créer un sous-projet Gradle sans
  dépendre du graphe AgentOS/produit. Un module `factory-instrument/` (Kotlin/JVM pur, sans Spring, sans
  DB, sans PF4J) avec son propre `main`/`bootJar`-shadow est donc possible et préserve l'invariant.

### 4.4 Options et recommandation motivée

- **Option A — in-process dans factory-service** : `LegacyRunService` appelle directement un moteur Kotlin
  (pas de `ProcessBuilder`). Avantages : gates et SSE triviaux (appels directs), pas de sérialisation.
  Inconvénients : le moteur partage le cycle de vie du service Spring (si la DB/Postgres est down, le
  service ne démarre pas → l'instrument ne peut plus tourner) ; l'instrument devient dépendant du graphe
  produit/control plane. **Risque direct sur l'invariant autonome.**
- **Option B — module Kotlin dédié invoqué en sous-processus JVM** (recommandé a priori) : nouveau module
  `factory-instrument` (JVM pur, `java -jar factory-instrument.jar <workflow>`), invoqué par
  `LegacyRunService` exactement comme `node factory/run.mjs` aujourd'hui (même contrat : argv, env,
  stdout JSONL/gates, registre). Préserve « l'artefact autonome » : build/déploiement propres, démarrage
  sans la stack produit ni Spring/DB, exécution même si le build produit est cassé. Coût : lancement JVM
  (~démarrage plus lent que Node), et il faut définir le transport des gates (stdout + fichier reply,
  comme aujourd'hui, ou socket).
- **Option C — déployable séparé** (service HTTP dédié) : autonomie maximale mais surcoût opérationnel et
  détour réseau ; probablement surdimensionné pour un instrument.
- Critère de décision : l'invariant « ne doit pas dépendre de la santé de ce qu'il mesure » s'applique au
  **produit mesuré** (Coday/AgentOS), pas forcément au control plane. Mais si l'instrument devient un
  module de l'app Spring, il hérite des préconditions de boot (DB) — à éviter. Recommandation :
  **Option B**, avec `LegacyRunService` remplaçant `ProcessBuilder(node, runEntry, workflow)` par
  `ProcessBuilder(java, "-jar", instrumentJar, workflow)` (ou chemin configurable `factory.runs.entry` →
  commande d'instrument), et conservation du protocole stdout/JSONL/gate inchangé.

---

## §5 Périmètre : à porter / à supprimer / réutilisable

### 5.1 À porter (cœur B)

- `run.mjs` : dispatch (workflows + diagnostics + alias), composition shutdown, logger, exit codes.
- `workflows/fix-loop.mjs`, `workflows/us-loop.mjs` : orchestration complète (budgets, gates, briefs,
  gardes `wrongAgent`/`wroteNothing`/`emptySuccess`/timeout, boucle revue + retry).
- `lib` primitives non encore portées : registry **writer**, oracle executor (process + classification),
  oracle-command (owner/host resolution + build command), plan (parse/check/compare), domains, jira
  (extract/fetch), snapshot/diff git+SHA256.
- Client AgentOS complet (createCase/postMessage/getCase/listEvents/kill/interrupt/preflightAgent/
  preflightWorkspace/listAgents/listIntegrations/runAgentTurn + détection de quiescence).
- `review-gate` (protocole + allow-list + single-use) et `review`/`review-engine`/`adversarial-review` si
  la revue reste dans le périmètre.
- `diagnostic-synthesis` et `oracle-baseline` (utilisés par us-loop).
- `diagnostics/agentos-smoke.mjs` et `diagnostics/backend-oracle-check.mjs`.
- `shutdown` (SIGTERM) et active-case (registre des cases actifs).
- Oracles déclaratifs : déjà couverts côté définition Kotlin ; reste à brancher l'exécution réelle.

### 5.2 À supprimer à terme (après bascule)

- `factory/runtime/factory-operational.mjs` (bundle généré) et la toolchain `factory/toolchain/`
  (esbuild/tsconfig) si plus aucune source TS n'est exécutée par `run.mjs`.
- `factory/src/**` (sources TS migrées) et `factory/lib/*.mjs` (façades).
- `factory/run.mjs`, `factory/workflows/*.mjs`, `factory/diagnostics/*.mjs`, `factory/oracles/*.mjs`.
- `factory/lib/forge-*.mjs`, `forge-epic.mjs` une fois le plugin PF4J maître.
- Le dashboard Node `factory/dashboard/` (44 fichiers) si l'on veut supprimer Node intégralement (hors
  périmètre B mais à mentionner comme reste).
- Node comme runtime : dépendance `ProcessBuilder(node...)` dans `LegacyRunService` à retirer.

### 5.3 Réutilisable (déjà en Kotlin)

- **Plugin forge PF4J** (`factory-forge-plugin/`) : `ForgeRunService`, `ForgeGateService`,
  `StoryOperationService`, `WorkflowSync`, `ForgeWorkflowAdapter`, `ForgeFrontOracleResolution`,
  `FileForgeLedgerStore`, `HttpJiraClient`. Contient déjà l'idée de verdict `exitCode == 0`
  (`StoryOperationService` l.611) et des ports injectables
  `StoryRuntime`/`WorkspaceSnapshotter`/`OracleCommandExecutor`.
- **Agrégat oracles** (`factory-service/.../oracle/`) : `OracleDefinition`, `OracleDefinitionValidator`,
  `OracleDefinitionRegistry` (charge `factory/oracles`), `OracleExecution` (persistance, idempotence).
- **Proxy AgentOS read-only** (`HttpAgentOsProxyClient`) : à étendre pour les opérations mutantes.
- **LegacyRunService** : lecture/projection JSONL, SSE, endpoints runs/gates (à conserver, seul le
  lancement change).
- **factory-sdk** : précédent de module Kotlin Spring-free ; `FactoryRunLaunchContributor` /
  `FactoryWorkflowProjectionPublisher` pourraient être réutilisés pour exposer un lancement d'instrument.
- Domaine lease/worker/workunit côté `factory-service` pour le worker-runtime.

### 5.4 Workflows bmad-story (JSON)

`factory/workflows/bmad-story/{1.0.0,1.1.0}.json` et
`bmad-story-frontend/{1.0.0.json,briefs/*.md}` : définitions déclaratives (steps agent/human,
`dependsOn`), consommées côté Node par `factory/lib/factory-frontend-composition.mjs`/
`factory-frontend-runner.mjs` (dashboard) ; la lecture / sync est déjà côté Kotlin (WorkflowDefinition,
ForgeWorkflowAdapter, tests `factory-service`). Conclusion : ces workflows sont **du ressort du control
plane / plugin forge**, pas du moteur fix-loop/us-loop. Le plugin PF4J peut porter la partie « runner
frontend » ; ne pas la dupliquer dans l'instrument.

---

## §6 Plan en vagues W8.x

### 6.1 Contraintes de test

La suite factory Node est un ensemble de harnais offline `factory/tests/*.mjs`
(`node factory/tests/<test>.mjs`) avec fakes/in-memory (ex. `test-us-loop.mjs`, `test-oracle*.mjs`,
`test-review*.mjs`, `test-shutdown.mjs`, `test-worker-runtime-*.mjs`, `test-run-dispatch.mjs`). Côté
Kotlin, tests unitaires directs + `DomainIntegrationTest`/Testcontainers.

**Stratégie de validation sans agents réels** : injecter des ports (comme `OracleCommandExecutor`,
`StoryRuntime`, `WorkspaceSnapshotter`) et des fakes AgentOS ; oracles déterministes
(`factory/tests/fixtures/oracle-process/{pass,fail,timeout,empty,large-output}.mjs` ; `test-us-loop.mjs`).
Le précédent Kotlin existe : les ports du plugin forge sont déjà injectables et testés avec des lambdas
(`ForgeStoryOperationsTest` : `OracleCommandExecutor { … OracleCommandResult(exitCode=0) }`).

### 6.2 Découpage (de l'isolé au couplé)

- **W8.1 — Primitives déterministes** : registre JSONL writer (fail par défaut), snapshot/diff
  git+SHA256, oracle executor (`ProcessBuilder`, classification, timeout, sortie bornée), oracle-command,
  plan, domains, jira. Aucune dépendance AgentOS. Vérif : parité byte-à-byte des lignes JSONL, tests
  oracle déterministes.
- **W8.2 — Client AgentOS Kotlin + active-case + quiescence** : endpoints (dont
  `step-result-binding` interne), DTOs, `runAgentTurn`, préflights, kill/interrupt. Vérif : fake HTTP
  AgentOS (mock server) ; rejouer `agentos-smoke`.
- **W8.3 — Gates & shutdown** : protocole review-gate (transport à re-décider ; signature unique et non
  ambiguë, cf. §3.5.bis), SIGTERM, `endRun fail`. Vérif : tests single-use/secret/stale ; `test-shutdown`
  porté.
- **W8.4 — Workflows fix-loop puis us-loop** : budgets, briefs, gardes, phases, révisions/revue. Vérif :
  fakes AgentOS + oracles déterministes, comparaison des phases/statuts/facts avec les runs Node connus.
- **W8.5 — Diagnostics + worker-runtime** (si conservés) : smoke, backend-oracle-check ; wiring worker
  local.
- **W8.6 — Bascule `LegacyRunService`** : remplacer `node factory/run.mjs` par l'instrument Kotlin
  (sous-processus JVM ou in-process) ; garder lecture JSONL/SSE/gates. Corriger en même temps les écarts
  de spawn §4.2 (catégorisation des args, propagation d'env dont le secret de gate, suivi runId, parsing
  des signaux de gate). Vérif : run nominal de bout en bout, plus un run avec AgentOS down (doit échouer
  proprement et tracer un fait).
- **W8.7 — Suppression Node** : retirer bundle, toolchain TS, `.mjs`, dashboard ; mettre à jour
  coday.yaml/docs.

### 6.3 Risques principaux

1. **Transport des gates** (§3.5, §3.5.bis).
2. **Fidélité de `runAgentTurn`** (quiescence multi-tours, F7).
3. **Autonomie si l'instrument devient module Spring** (§4.3).
4. **Parité du format JSONL** (déjà consommé par `LegacyRunService`).
5. **Timeouts/groupes de process** pour les oracles longs (Gradle/Nx).
