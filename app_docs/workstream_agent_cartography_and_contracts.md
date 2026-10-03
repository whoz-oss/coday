# Phase 0 — Cartographie & contrats : Factory gouvernée et Workstream Agent

Statut : **Phase 0 (design / reconnaissance)** — aucun code runtime n'est introduit par ce document.
Portée : le plan de contrôle Factory existant (`factory-service`, pont AgentOS `agentos-factory-bridge-plugin`,
adaptateur transitoire Coday `libs/integration`) et la surface HTTP `/api/factory/**`.
Objectif : figer l'inventaire des faits, la carte des tools, les identités, les capacités et les DTO
**avant** d'introduire le Workstream Agent et de séparer ses responsabilités de celles de la Factory
et des workers.

> Toutes les références `chemin/fichier` ci-dessous sont relatives à la racine du monorepo (`/work/app`).

---

## 1. Inventaire des stores, composition roots et composant autoritaire par fait

### 1.1 Principe d'autorité unique

Un seul composant est autoritaire par famille de faits. Un client (Cockpit, Workstream Agent, worker)
ne recalcule jamais un état : il lit le fait autoritaire ou émet une **commande** qui est validée par
le composant autoritaire sous fence de révision.

L'ordre de résolution d'identité est toujours :
`ToolContext` (côté AgentOS) ou `TrustContext` (côté HTTP) → `TenantScope` → store Neo4j.
L'identité n'est **jamais** dérivée d'un champ rédigé par le modèle.

### 1.2 Table des faits autoritaires

| Fait (fact) | Store / Repository (persistance) | Nœuds Neo4j | Composant autoritaire (écriture/validation) | Endpoint(s) autoritaire(s) |
|---|---|---|---|---|
| **workflow** — projection, instance, step state, transitions, définitions | `WorkflowRepository` → `Neo4jWorkflowRepository` | `WorkflowProjectionNode`, `WorkflowInstanceNode`, `WorkflowStepStateNode`, `WorkflowTransitionNode`, `WorkflowCodeTransitionNode`, `WorkflowDefinitionNode` | `WorkflowService` (+ `WorkflowTransitionPolicy`, `SessionSequencer`) | `GET/PUT /api/factory/workflows/{workflowId}`, `POST /{workflowId}/start`, `POST /{workflowId}/transitions`, `POST /{workflowId}/code-transitions` |
| **evidence** — faits immuables d'un step (agent-result, artifact, oracle-result, human-decision) | `WorkflowEvidenceRepository` → `Neo4jWorkflowEvidenceRepository` | `WorkflowEvidenceNode` | `WorkflowService` (`listEvidence` / `appendEvidence`) | `GET/POST /api/factory/workflows/{workflowId}/evidence` |
| **human interaction** — checkpoints humains (open → reply) + events | `HumanInteractionRepository` → `Neo4jHumanInteractionRepository` | `HumanInteractionNode`, `HumanInteractionEventNode` | `WorkflowService` (`openInteraction`, `replyInteraction`, `submitAgentQuestionAnswer`) | `GET/POST /api/factory/workflows/{workflowId}/interactions`, `POST /{workflowId}/interactions/{id}/reply`, `POST /{workflowId}/agent-questions/{qid}/answer` |
| **environment** — worktree Git provisionné d'un work-unit | `WorkEnvironmentRepository` → `Neo4jWorkEnvironmentRepository` | `WorkEnvironmentNode` | `WorkUnitEnvironmentService` | `GET /api/factory/workflows/{workflowId}/environment`, `POST .../provision`, `POST .../reconcile`, `POST .../release` |
| **attempt** — tentative d'exécution durable d'un agent turn | `DurableAgentAttemptRepository` → `Neo4jDurableAgentAttemptRepository` | `DurableAgentAttemptNode` | lecture : `DurableAgentAttemptService` ; commande/annulation : `BridgeCancellationService` ; recouvrement : `BridgeRecoveryWorker` | `GET /api/factory/workflows/{workflowId}/attempts`, `POST /{workflowId}/attempts/{attemptId}/cancel` |
| **structured result** — résultat métier soumis par un worker (status, summary, claims, artifacts, findings) | `AgentStepResultRepository` → `Neo4jAgentStepResultRepository` | `AgentStepResultNode`, `AgentStepAttemptNode` | `AgentStepResultService` | `POST /api/factory/agent-step-results`, `POST /api/factory/step-result-bindings` (vérification de capability) |
| **capability** — droit d'exécuter un turn et de soumettre un résultat, matérialisé par un token | `ResultCapabilityRepository` (Spring Data `SpringDataNeo4jResultCapabilityRepository`) + champ `capabilityToken` de `DurableAgentAttempt` | `ResultCapabilityNode` | `CapabilityResolver` + `AgentTurnCapability` (`AgentOsAgentTurnCapability`) ; émission/rédemption : `AgentStepResultService` | pas d'endpoint d'écriture direct ; `POST /api/factory/step-result-bindings` résout le binding |
| **workstream** — groupe métier tenant-scopé | `WorkstreamRepository` → `Neo4jWorkstreamRepository` | `WorkstreamNode` (id composite `organizationId|workstreamId`) | `WorkstreamService` | `GET/POST /api/factory/workstreams` |
| **oracle execution** | `OracleExecutionRepository` → `Neo4jOracleExecutionRepository` | `OracleNode`(s) | `OracleService` | `GET/POST /api/factory/oracles/**` |
| **delivery** | `DeliveryRepository` → `Neo4jDeliveryRepository` | `DeliveryNode`, `DeliveryRecordNode` | `DeliveryService` / opérations gouvernées | `GET/POST /api/factory/deliveries/**` |
| **lease** — bail d'exécution d'un worker | `LeaseRepository` → `Neo4jLeaseRepository` | nœuds lease | `LeaseService` | surface interne/proxy |
| **workunit** — unité de travail | `WorkUnitRepository` → `Neo4jWorkUnitRepository` | `WorkUnitNode` | `WorkUnitService` | surface interne |
| **worker** — nœud worker (heartbeat, capabilities, protocolVersion) | `WorkerRepository` → `Neo4jWorkerRepository` | `WorkerNode` | `WorkerService` | surface interne/proxy |
| **artifact** — artefact persistant + blob | `ArtifactStore` → `Neo4jArtifactStore` (+ blob store) | nœuds artifact | `ArtifactService` | `GET/POST /api/factory/artifacts/**`, admin `/api/factory/admin/**` |

### 1.3 Composition roots

| Composition root | Rôle | Fichier |
|---|---|---|
| `Neo4jPersistenceConfiguration` | Active les Spring Data Neo4j repositories pour tous les agrégats (oracle, artifact, delivery, lease, worker, workunit, environment, agentattempt, workflow, workstream) et déclare le transaction manager unique | `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt` |
| `Neo4jSchemaInitializer` | Contraintes / index Neo4j | `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jSchemaInitializer.kt` |
| `WorkflowService` | Agrégat workflow : projections, transitions, evidence, interactions, métriques | `factory-service/.../workflow/service/WorkflowService.kt` |
| `SessionRunService` / `SessionRunSubmissionService` | Exécution / soumission durable des sessions depuis le control-plane | `factory-service/.../workflow/service/SessionRun*.kt` |
| `CapabilityResolver` / `CapabilityExecutionService` | Routage `(kind, name)` → capability code / agent / human ; exécution des checkpoints | `factory-service/.../capability/*.kt` |
| `WorkUnitEnvironmentService` | Composition de l'environnement de work-unit | `factory-service/.../environment/service/WorkUnitEnvironmentService.kt` |
| `AgentStepResultService` | Émission/vérification de capability et soumission de résultat structuré | `factory-service/.../agentattempt/service/AgentStepResultService.kt` |
| `DurableAgentAttemptService` / `BridgeCancellationService` / `OutboxDrainService` | Cycle de vie des attempts, annulation explicite, drain d'outbox | `factory-service/.../agentattempt/service/*.kt` |
| `WorkstreamService` | Lecture/création de workstreams | `factory-service/.../workstream/WorkstreamService.kt` |
| `ProxyConfiguration` / `AgentOsAdapterConfiguration` / `ArtifactConfiguration` / `OutboxSchedulingConfiguration` | Wiring technique (agentos, artefacts, scheduling) | `factory-service/.../config/*`, `.../proxy/ProxyConfiguration.kt`, `.../adapter/agentos/AgentOsAdapterConfiguration.kt` |
| `FactoryBridgePlugin` / `FactoryBridgeServices` / `FactoryToolPlugin` / `FactoryBindingRegistrar` | Plugin AgentOS : tools, interception des réponses, bindings de contexte case/thread | `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/*.kt` |
| `FactoryTools` (legacy Node/Express) | Adaptateur **transitoire** des tools Factory côté Coday Express | `libs/integration/src/lib/factory.tools.ts` |

> Les migrations de bases et les pipelines de release sont **hors périmètre** de la Phase 0 (ne pas y toucher).

---

## 2. Carte de tous les tools Factory existants, callers et endpoints HTTP

### 2.1 Tools du plugin AgentOS (canal principal, `FACTORY` integration)

Tous sont construits par `buildFactoryTools(services)` dans
`agentos/agentos-factory-bridge-plugin/.../FactoryToolPlugin.kt` et exposés à un agent uniquement
si le grant explicite `FACTORY` les liste via `FactoryToolGrantService` (absence ou allowlist vide ⇒ aucun droit).

| Tool (nom wire) | Classe | Endpoint HTTP appelé | Callers |
|---|---|---|---|
| `FACTORY__get_workflow` | `FactoryGetWorkflowTool` | `GET /api/factory/workflows/{workflowId}?namespaceId={ns}` | agents/personas granteés |
| `FACTORY__start_workflow` | `FactoryStartWorkflowTool` | `POST /api/factory/workflows/{workflowId}/start` | agents/personas granteés |
| `FACTORY__provision_environment` | `FactoryProvisionEnvironmentTool` | `POST /api/factory/workflows/{workflowId}/environment/provision` | agents/personas granteés |
| `FACTORY__record_agent_result` | `FactoryRecordAgentResultTool` | `POST /api/factory/workflows/{workflowId}/evidence` | agents/personas granteés |
| `FACTORY__record_artifact` | `FactoryRecordArtifactTool` | `POST /api/factory/workflows/{workflowId}/evidence` | agents/personas granteés |
| `FACTORY__submit_step_result` | `FactorySubmitStepResultTool` | `POST /api/factory/agent-step-results` (binding via `FactoryStepResultBindingRegistry`) | workers / agents porteurs d'un capability token |
| `FACTORY__request_human_decision` | `FactoryRequestHumanDecisionTool` | `POST /api/factory/workflows/{workflowId}/interactions` | agents/personas granteés |
| `FACTORY__request_transition` | `FactoryRequestTransitionTool` | `POST /api/factory/workflows/{workflowId}/transitions` | agents/personas granteés |
| `FACTORY__transition_workflow` | `FactoryTransitionWorkflowTool` (délégué) | `POST /api/factory/workflows/{workflowId}/transitions` | **alias legacy** — voir §3 |
| `FACTORY__publish_projection` | `FactoryPublishProjectionTool` | `PUT /api/factory/workflows/{workflowId}/projection` | **legacy** (workflows déclaratifs) — voir §3 |

### 2.2 Tools de l'adaptateur transitoire Coday (`FactoryTools`, `libs/integration`)

`libs/integration/src/lib/factory.tools.ts` marqué `@deprecated Transitional Express adapter`.
Il expose les mêmes noms `{instanceName}__get_workflow`, `__start_workflow`, `__record_agent_result`,
`__record_artifact`, `__request_human_decision`, `__request_transition`, `__transition_workflow`,
`__publish_projection` et frappe les **mêmes endpoints** `/api/factory/workflows/**`.
Callers : agents Coday configurés avec l'intégration `FACTORY` (legacy), en cours de remplacement par
le plugin AgentOS.

### 2.3 Endpoints HTTP `/api/factory/**` (surface de contrôle)

**Workflows** (`WorkflowController`, base `/api/factory/workflows`) :

| Méthode + chemin | Rôle |
|---|---|
| `GET /` | lister les projections (filtre optionnel `namespaceId`, `state`) |
| `GET /{workflowId}` | lire projection / état de cycle de vie |
| `DELETE /{workflowId}` | retirer (récupérable) |
| `GET /{workflowId}/projection` | lire la projection courante |
| `PUT /{workflowId}/projection` | publier une projection déclarative (**legacy**) |
| `POST /{workflowId}/start`, `POST /start` | démarrer un workflow gouverné |
| `POST /{workflowId}/transitions`, `POST /transitions` | transition gouvernée (fence de révision) |
| `POST /{workflowId}/code-transitions`, `POST /code-transitions` | transition code déterministe |
| `POST /{workflowId}/run`, `POST /{workflowId}/continue` | exécution/continuation de session (`?sync=`) |
| `GET /{workflowId}/attempts` | lister les attempts durables |
| `POST /{workflowId}/attempts/{attemptId}/cancel` | annulation explicite fencée |
| `GET /{workflowId}/session` | état du DAG de session |
| `POST /{workflowId}/retries` | ouvrir un retry d'un step bloqué |
| `POST /{workflowId}/restore`, `POST /{workflowId}/purge`, `DELETE /{workflowId}/purge` | cycle de vie de la projection |
| `GET /{workflowId}/timing`, `GET /{workflowId}/retries`, `GET /{workflowId}/metrics` | métriques |
| `GET/POST /{workflowId}/evidence` | lire / ajouter de l'evidence auditée |
| `POST /{workflowId}/agent-questions/{questionEventId}/answer` | réponse humaine authentifiée (canal AgentOS) |
| `GET/POST /{workflowId}/interactions`, `POST /{workflowId}/interactions/{interactionId}/reply` | checkpoints humains |
| `GET /{workflowId}/actions` | actions autorisées + blockers (autoritaire) |
| `POST /{workflowId}/cost/continue`, `POST /{workflowId}/cost/stop` | relais coût run vers AgentOS |

**Environnement** (`WorkUnitEnvironmentController`, base `/api/factory/workflows/{workflowId}/environment`) :
`GET /`, `POST /provision`, `POST /reconcile`, `POST /release`.

**Résultat structuré** (`AgentStepResultController` + `FactoryStepResultBindingController`) :
`POST /api/factory/agent-step-results`, `POST /api/factory/step-result-bindings`.

**Workstreams** (`WorkstreamController`, base `/api/factory/workstreams`) : `GET /`, `POST /`.

**Autres surfaces** : `/api/factory/oracles/**`, `/api/factory/deliveries/**`, `/api/factory/artifacts/**`,
`/api/factory/admin/**`, `/api/factory/config`, proxy AgentOS (`AgentOsProxyController`).

Tous les succès utilisent l'enveloppe `{ "data": ... }` ; les erreurs l'enveloppe
`{ "error": { code, message, details } }` rendue par `FactoryExceptionHandler`.

---

## 3. Doublons legacy à déprécier vs transitions gouvernées

| Élément legacy | Statut | Remplacé par | Règle de dépréciation |
|---|---|---|---|
| `PUT /api/factory/workflows/{workflowId}/projection` + tool `FACTORY__publish_projection` | **déprécié** (workflows déclaratifs uniquement) | transitions gouvernées `POST /{workflowId}/transitions` + `request_transition` | Refusé sur workflow gouverné : codes `DECLARATIVE_WORKFLOW` / `GOVERNED_WORKFLOW_REQUIRES_TRANSITION`. À retirer une fois tous les workflows déclaratifs migrés. |
| tool `FACTORY__transition_workflow` | **alias legacy** de `FACTORY__request_transition` | `FACTORY__request_transition` | Conserver en compatibilité ProductEngineer, ne pas documenter comme canonique, émettre un avertissement de dépréciation dans le grant. |
| `FactoryTools` Node/Express (`libs/integration/src/lib/factory.tools.ts`) | **adaptateur transitoire** | plugin AgentOS (`agentos-factory-bridge-plugin`) + `POST /ai/...` SDK | À supprimer lorsque plus aucun agent Coday n'utilise l'intégration `FACTORY` Express. |
| routes dashboard Node (`workflow-projection-routes.mjs`, `workflow-transition-routes.mjs`, etc.) | **portées** vers Kotlin | `WorkflowController` | Source de vérité = Kotlin ; le Node n'est plus qu'historique. |

**Transitions gouvernées** (canoniques) : `request_transition` — le demandeur propose
`(workflowId, stepId, expectedRevision, requestedStatus, evidenceIds, idempotencyKey?)` ;
la Factory valide via `WorkflowTransitionPolicy` et applique sous fence de révision. Le statut
`SUCCEEDED`/`completed` n'est jamais atteignable sans evidence correspondante.

---

## 4. Contrats d'identité et frontière de confiance

| Identifiant | Forme / portée | Source de vérité (frontière de confiance) | Qui peut le fournir |
|---|---|---|---|
| `workstreamId` | slug `^[a-z0-9]+(?:-[a-z0-9]+)*$`, clé composite `(organizationId, workstreamId)` | `TrustContext.workstreamId` (jeton/service vérifié) ; à défaut, paramètre validé à la frontière | **jamais** le modèle / le worker |
| `workflowId` | `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`, immutable, namespace-scopé | créé/validé par `WorkflowController` ; capture contrôleur | opérateur / control-plane ; le tool vérifie le pattern |
| `attemptId` | identifiant durable émis par la Factory | `DurableAgentAttempt` (créé par le bridge/outbox) | émis par la Factory, jamais choisi par l'agent |
| `caseId` | id de case AgentOS | `ToolContext.caseEvents` (bridge) ou `execution.caseId` vérifié ; `TrustContext.caseId` | résolu depuis le contexte de trust, pas depuis l'input modèle |
| `namespaceId` | namespace AgentOS | `ToolContext.namespaceId` / `TrustContext.namespaceId` / `execution.namespaceId` validé | contexte de trust |
| `actorId` | principal humain authentifié | `TrustContext.principalId` (JWT / proxy-signature / loopback-dev) | contexte de trust ; requis pour un checkpoint humain |
| `organizationId` | tenant | `TenantScope` dérivé de `TrustContext` | contexte de trust (fails closed `TRUST_CONTEXT_UNAVAILABLE` / 401) |

**Frontière de confiance** :
- Côté HTTP, `resolveWorkflowCaller` / `resolveFactoryCaller` construisent un `TenantScope` à partir du
  `TrustContext` vérifié. Un contexte absent ⇒ 401 `TRUST_CONTEXT_UNAVAILABLE`.
- Côté bridge, `FactoryRequestTransitionTool` et consorts refusent l'exécution si `caseEvents` ne
  contient pas exactement un case (`CASE_CONTEXT_UNAVAILABLE`), si l'agent est absent
  (`AGENT_CONTEXT_UNAVAILABLE`) ou si l'acteur est absent (`USER_CONTEXT_UNAVAILABLE`).
- Aucun tool ne lit une identité depuis les arguments du modèle : l'input contient au plus un
  `workflowId`/`stepId`, tout le reste est injecté depuis le `ToolContext`.
- Le `capabilityToken` est lié à `(attemptId, workflowId, stepId, namespaceId, caseId, agentName, briefHash)`
  et n'est jamais persisté en clair (seul le hash est stocké).

---

## 5. Matrice de capacités

Colonnes :
- **Workstream Agent** : agent de supervision/produit, sans droit d'écriture d'exécution.
- **worker RO** : worker en lecture seule (observation, diagnostic).
- **worker writable** : worker autorisé à exécuter et soumettre.
- **humain / control-plane** : opérateur authentifié (Cockpit V2, admin).

| Capacité | Workstream Agent | worker RO | worker writable | humain / control-plane |
|---|:--:|:--:|:--:|:--:|
| Lire workstream / workflows (`get_workstream`, `list_workflows`, `get_workflow`) | ✅ | ✅ | ✅ | ✅ |
| Lire attempts d'un step (`get_step_attempts`) | ✅ | ✅ | ✅ | ✅ |
| Lire blockers (`get_blockers`) | ✅ | ✅ | ✅ | ✅ |
| Lire actions humaines requises (`get_required_human_actions`) | ✅ | ✅ | ✅ | ✅ |
| `start_workflow` | ❌ | ❌ | ✅ (scopé) | ✅ |
| `request_transition` | ❌ | ❌ | ✅ (step agent-owned + evidence) | ✅ |
| `request_human_decision` (ouvrir un checkpoint) | ✅ (proposé) | ❌ | ✅ | ✅ |
| Répondre à un checkpoint (`interactions/{id}/reply`) | ❌ | ❌ | ❌ | ✅ (humain authentifié) |
| `propose_plan_change` | ✅ | ❌ | ❌ | ✅ |
| `request_agent_retry` | ✅ (demande) | ❌ | ❌ | ✅ |
| `interrupt_attempt` / cancel | ❌ | ❌ | ❌ | ✅ |
| `record_evidence` (agent-result / artifact) | ❌ | ❌ | ✅ | ✅ |
| `submit_step_result` | ❌ | ❌ | ✅ (capability-bound) | ✅ |
| `publish_projection` (legacy déclaratif) | ❌ | ❌ | ❌ | ✅ |
| `provision_environment` | ❌ | ❌ | ✅ (scopé) | ✅ |
| `cost/continue` / `cost/stop` | ❌ | ❌ | ❌ | ✅ |

Règles transverses :
- Le droit est **explicite** : `FactoryToolGrantService` n'accorde rien sans allowlist `FACTORY`.
- Un worker writable ne peut écrire que dans le périmètre de son `attemptId`/`caseId` et sous fence de révision.
- Le Workstream Agent ne produit jamais d'effet d'exécution ; il **propose** (plans, retries, checkpoints).
- Le `human-control-plane` est le seul à pouvoir lever un blocker humain (approve/reject).

---

## 6. Schémas DTO bornés des futurs tools

Principes communs :
- Inputs et outputs sont **bornés** (longueurs max, `additionalProperties: false`, listes plafonnées).
- Aucune donnée secrète (`ownerToken`, `capabilityToken`, `brief`, `leaseExpiresAt`, `lastObservedEventId`,
  `turnCorrelation`) ne franchit la frontière publique.
- Toute commande porte `expectedRevision` et, si applicable, `idempotencyKey`.
- Les erreurs sont des `{ code, message }` stables (voir §7).

### 6.1 Tools de lecture

#### `get_workstream`
```jsonc
// input
{ "type": "object", "additionalProperties": false,
  "properties": { "workstreamId": { "type": "string", "maxLength": 128 } },
  "required": ["workstreamId"] }
// output (data)
{ "workstreamId": "string", "organizationId": "string", "name": "string",
  "status": "string", "revision": 1 }
```

#### `list_workflows`
```jsonc
// input
{ "type": "object", "additionalProperties": false,
  "properties": {
    "workstreamId": { "type": "string", "maxLength": 128 },
    "state": { "enum": ["active", "removed", "purged", "all"] },
    "limit": { "type": "integer", "minimum": 1, "maximum": 200 },
    "cursor": { "type": "string", "maxLength": 256 } },
  "required": [] }
// output (data)
{ "items": [ { "workflowId": "string", "workflowType": "string", "title": "string",
              "status": "string", "revision": 1 } ],
  "nextCursor": "string|null" }
```

#### `get_workflow`
```jsonc
// input
{ "type": "object", "additionalProperties": false,
  "properties": { "workflowId": { "type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$" } },
  "required": ["workflowId"] }
// output (data)
{ "state": "absent|existing|removed|purged", "workflowId": "string",
  "revision": 1, "workflowType": "string", "status": "string",
  "steps": [ { "stepId": "string", "status": "string", "revision": 1 } ],
  "blockers": [ /* WorkflowBlockerDto */ ] }
```

#### `get_step_attempts`
```jsonc
// input
{ "type": "object", "additionalProperties": false,
  "properties": { "workflowId": { "type": "string", "maxLength": 128 },
                  "stepId": { "type": "string", "maxLength": 128 } },
  "required": ["workflowId", "stepId"] }
// output (data) — dérivé de DurableAgentAttemptDto, sans secret
[ { "attemptId": "string", "stepId": "string", "attemptNumber": 1,
    "agentName": "string",
    "status": "pending|claiming|starting|running|waiting_human|succeeded|failed|indeterminate|interrupted",
    "caseId": "string", "failureCode": "string|null",
    "resultEvidenceId": "string|null", "revision": 1,
    "createdAt": "iso", "startedAt": "iso|null", "completedAt": "iso|null" } ]
```

#### `get_blockers`
```jsonc
// input
{ "type": "object", "additionalProperties": false,
  "properties": { "workflowId": { "type": "string", "maxLength": 128 } },
  "required": ["workflowId"] }
// output (data) — dérivé de WorkflowBlockerDto
[ { "code": "WAITING_HUMAN_INTERACTION|STEP_BLOCKED|ATTEMPT_FAILED|REAL_COST_PAUSED|VERIFICATION_FAILED|UNKNOWN_RUNTIME",
    "stepId": "string|null", "message": "string" } ]
```

#### `get_required_human_actions`
```jsonc
// input
{ "type": "object", "additionalProperties": false,
  "properties": { "workflowId": { "type": "string", "maxLength": 128 } },
  "required": ["workflowId"] }
// output (data) — dérivé des interactions ouvertes + AllowedActionDto
[ { "interactionId": "string", "stepId": "string", "questionEventId": "string|null",
    "prompt": "string", "actions": [ { "id": "approve|reject", "label": "string" } ],
    "expectedRevision": 1 } ]
```

### 6.2 Tools de commande

#### `request_transition`
```jsonc
{ "type": "object", "additionalProperties": false,
  "properties": {
    "workflowId": { "type": "string", "maxLength": 128 },
    "stepId": { "type": "string", "maxLength": 128 },
    "expectedRevision": { "type": "integer", "minimum": 1 },
    "requestedStatus": { "enum": ["pending","ready","running","waiting_human","blocked","completed","failed","cancelled"] },
    "evidenceIds": { "type": "array", "maxItems": 100, "uniqueItems": true, "items": { "type": "string", "maxLength": 128 } },
    "idempotencyKey": { "type": "string", "maxLength": 128 } },
  "required": ["workflowId","stepId","expectedRevision","requestedStatus","evidenceIds"] }
// output (data) : { "workflowId": "string", "stepId": "string", "revision": 1, "changed": true }
```

#### `request_human_decision`
```jsonc
{ "type": "object", "additionalProperties": false,
  "properties": {
    "workflowId": { "type": "string", "maxLength": 128 },
    "stepId": { "type": "string", "maxLength": 128 },
    "expectedRevision": { "type": "integer", "minimum": 1 },
    "prompt": { "type": "string", "maxLength": 2000 },
    "actions": { "type": "array", "minItems": 2, "maxItems": 2,
                 "items": { "type": "object", "additionalProperties": false,
                            "properties": { "id": { "enum": ["approve","reject"] }, "label": { "type": "string", "maxLength": 120 } },
                            "required": ["id","label"] } },
    "idempotencyKey": { "type": "string", "maxLength": 128 } },
  "required": ["workflowId","stepId","expectedRevision","prompt","actions","idempotencyKey"] }
// output (data) : { "interactionId": "string", "workflowId": "string", "stepId": "string",
//                   "expectedRevision": 1, "state": "open", "idempotent": false }
```

#### `propose_plan_change`
```jsonc
{ "type": "object", "additionalProperties": false,
  "properties": {
    "workflowId": { "type": "string", "maxLength": 128 },
    "expectedRevision": { "type": "integer", "minimum": 1 },
    "summary": { "type": "string", "maxLength": 2000 },
    "operations": { "type": "array", "maxItems": 50,
                    "items": { "type": "object", "additionalProperties": false,
                               "properties": { "op": { "enum": ["add_step","remove_step","reorder_step","change_responsibility"] },
                                               "stepId": { "type": "string", "maxLength": 128 },
                                               "target": { "type": "string", "maxLength": 128 } },
                               "required": ["op","stepId"] } },
    "idempotencyKey": { "type": "string", "maxLength": 128 } },
  "required": ["workflowId","expectedRevision","summary","operations","idempotencyKey"] }
// output (data) : { "proposalId": "string", "workflowId": "string", "status": "pending_validation", "revision": 1 }
```
> `propose_plan_change` n'applique **rien** : la Factory valide et décide. Le Workstream Agent ne modifie jamais le plan directement.

#### `request_agent_retry`
```jsonc
{ "type": "object", "additionalProperties": false,
  "properties": {
    "workflowId": { "type": "string", "maxLength": 128 },
    "stepId": { "type": "string", "maxLength": 128 },
    "expectedRevision": { "type": "integer", "minimum": 1 },
    "reasonCode": { "type": "string", "maxLength": 64 },
    "idempotencyKey": { "type": "string", "maxLength": 128 } },
  "required": ["workflowId","stepId","expectedRevision","reasonCode"] }
// output (data) : { "workflowId": "string", "stepId": "string", "revision": 1, "status": "retry_requested" }
```

#### `interrupt_attempt`
```jsonc
{ "type": "object", "additionalProperties": false,
  "properties": {
    "workflowId": { "type": "string", "maxLength": 128 },
    "attemptId": { "type": "string", "maxLength": 128 },
    "expectedRevision": { "type": "integer", "minimum": 1 },
    "reason": { "type": "string", "maxLength": 500 },
    "idempotencyKey": { "type": "string", "maxLength": 128 } },
  "required": ["workflowId","attemptId","expectedRevision"] }
// output (data) : { "workflowId": "string", "attemptId": "string", "stepId": "string",
//                   "status": "interrupted", "revision": 1, "idempotent": false,
//                   "reconciledVerdict": "string|null" }
```

---

## 7. Table de codes d'erreur stables

Format d'enveloppe : `{ "error": { "code": "<CODE>", "message": "...", "details": ... } }`.
Les codes sont **stables** et ne doivent pas être renommés sans versionnage.

### 7.1 Domaine workflow (`WorkflowErrorCodes`)

| Code | HTTP | Signification |
|---|---|---|
| `INVALID_REQUEST` | 400 | Corps de requête malformé / champs non autorisés |
| `INVALID_EXECUTION` | 400 | Bloc `execution` invalide |
| `INVALID_NAMESPACE_ID` | 400 | `namespaceId` absent ou invalide |
| `INVALID_WORKFLOW_ID` | 400 | `workflowId` hors pattern |
| `INVALID_START_REQUEST` | 400 | Demande de démarrage invalide |
| `INVALID_PROJECTION` | 400 | Projection invalide |
| `INVALID_TRANSITION_REQUEST` | 400 | Transition invalide |
| `UNTRUSTED_REQUEST_ID` / `UNTRUSTED_WORKFLOW_INPUT` | 400 | Entrée non fiable |
| `WORKFLOW_ID_MISMATCH` | 400 | `workflowId` incohérent avec la route/corps |
| `INVALID_LIFECYCLE_TRANSITION` | 400 | Transition de cycle de vie interdite |
| `UNKNOWN_STEP` | 400 | `stepId` inconnu |
| `INVALID_EVIDENCE` | 400 | Evidence malformée |
| `INVALID_INTERACTION` / `INVALID_REPLY` | 400 | Interaction / réponse invalide |
| `ACTION_NOT_ALLOWED` | 400 | Action non permise par l'état courant |
| `UNSUPPORTED_STATE` | 400 | État non supporté |
| `WORKFLOW_DEFINITION_INVALID` (+ codes définitions) | 400 | Définition invalide |
| `TRUST_CONTEXT_UNAVAILABLE` | 401 | Contexte de confiance absent |
| `UNAUTHENTICATED_ACTOR` | 401 | Acteur non authentifié pour un checkpoint |
| `FACTORY_ONLY_EVIDENCE` | 403 | Evidence réservée au control-plane (`oracle-result`, `human-decision`) |
| `WORKFLOW_NOT_FOUND` / `WORKFLOW_DEFINITION_NOT_FOUND` / `INTERACTION_NOT_FOUND` / `ROUTE_NOT_FOUND` | 404 | Introuvable |
| `REVISION_CONFLICT` | 409 | Fence de révision échouée |
| `WORKFLOW_ALREADY_EXISTS` / `WORKFLOW_IDENTITY_CONFLICT` | 409 | Conflit d'identité |
| `WORKFLOW_NOT_GOVERNED` / `DECLARATIVE_WORKFLOW` / `GOVERNED_WORKFLOW_REQUIRES_TRANSITION` | 409 | Mode de gouvernance incompatible |
| `WORKFLOW_DEFINITION_MISMATCH` / `AMBIGUOUS` | 409 | Définition incohérente/ambiguë |
| `IDEMPOTENCY_KEY_COLLISION` | 409 | Collision de clé d'idempotence |
| `INTERACTION_STALE` / `INTERACTION_SCOPE_MISMATCH` | 409 | Interaction périmée / hors périmètre |
| `WORKFLOW_STORAGE_FAILURE` / `EVIDENCE_STORAGE_FAILURE` / `HUMAN_INTERACTION_FAILURE` | 5xx/409 | Échec de stockage |
| `WORKFLOW_REMOVED` / `WORKFLOW_PURGED` | 410 | Workflow retiré/purgé |
| `METRICS_DATA_INCOMPLETE` | 422 | Métriques incomplètes |

Codes additionnels émis par les contrôleurs : `INVALID_RETRY_REQUEST`, `INVALID_METRICS_SCOPE`,
`INVALID_WORKSTREAM_REQUEST`, `INVALID_WORKSTREAM_SLUG`, `WORKSTREAM_ALREADY_EXISTS`,
`BRIDGE_CANCELLATION_UNAVAILABLE` (503).

### 7.2 Domaine résult/capability

| Code | HTTP | Signification |
|---|---|---|
| `RESULT_CAPABILITY_INVALID` | 401 | Token de capability inconnu/expiré |
| `RESULT_IDENTITY_MISMATCH` | 400 | Identité observée/déclarée (`attemptId`, `caseId`, `agentName`, `namespaceId` de confiance) ≠ binding |
| `RESULT_ALREADY_SUBMITTED` | 409 | Résultat déjà soumis (idempotence) |
| `RESULT_SEMANTIC_COLLISION` | 409 | Replay avec un payload différent pour le même attempt |
| `RESULT_CAPABILITY_EXPIRED` | 410 | Capability expirée |
| `RESULT_BUDGET_EXHAUSTED` | 409 | Budget de soumissions épuisé |
| `AGENT_NO_STRUCTURED_RESULT` | n/a (verdict/raison) | Turn `IDLE` sans question en attente et sans résultat structuré soumis via la capability — un message libre n'est jamais un succès autoritatif |

### 7.3 Domaine bridge / transport (côté tools)

| Code | HTTP équivalent | Signification |
|---|---|---|
| `FACTORY_UNAVAILABLE` | 503 | Factory injoignable |
| `FACTORY_TIMEOUT` | 504 | Timeout d'appel |
| `FACTORY_REQUEST_FAILED` | 4xx/5xx | Échec renvoyé par la Factory sans code exploitable |
| `MALFORMED_FACTORY_RESPONSE` | 502 | Réponse Factory invalide |
| `CASE_CONTEXT_UNAVAILABLE` | 400 | Contexte case unique introuvable |
| `AGENT_CONTEXT_UNAVAILABLE` | 400 | Identité d'agent absente |
| `USER_CONTEXT_UNAVAILABLE` | 400 | Identité d'acteur absente |

### 7.4 Domaine vérification / plan

| Code | HTTP | Signification |
|---|---|---|
| `VERIFICATION_NOT_DECLARED` | 400 | Vérification non déclarée dans `factory/verification.json` |
| `PASS_EVIDENCE_REQUIRED` | 409 | Evidence de passage requise pour l'étape |
| `AGENT_NOT_IMPLEMENTED_YET` | 501 | Capability agent non câblée |

---

## 8. Décision d'architecture

La séparation **Workstream Agent / Factory / worker** est consignée dans
[`docs/adr/0001-workstream-agent-factory-worker-separation.md`](../docs/adr/0001-workstream-agent-factory-worker-separation.md).

Résumé : la Factory reste l'autorité unique des faits de workflow (projections, transitions, evidence,
interactions, attempts) ; le **Workstream Agent** devient un superviseur produit en lecture +
propositions (jamais d'effet d'exécution direct) ; les **workers** exécutent et soumettent des résultats
sous capability, dans le périmètre de leur attempt ; le **human-control-plane** seul lève les
checkpoints humains.
