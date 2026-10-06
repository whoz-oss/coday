# Plan de Mise en Œuvre — Bridge AgentOS-Factory dans SessionRunService et WorkflowController (Lot C / Étapes 3, 6, 7 & HTTP /run)

## 1. Contexte & Principes directeurs

### Objectif
Assembler et orchestrer l'intégration entre **Factory-Service** et **AgentOS** via :
1. `DurableAgentAttemptService` / `DurableAgentAttempt` / `AgentAttemptStatus` (`agentattempt`).
2. `AgentOsExecutionAdapter` / `AgentOsExecutionVerdict` (`adapter.agentos`).
3. L'asynchronisme / rejouabilité durable au niveau de l'entrée HTTP `POST /run` dans `WorkflowController` et `SessionRunService`.
4. Resserrement transactionnel strict (Étape 7) : **aucune transaction Neo4j ouverte pendant l'exécution distante AgentOS ou l'observation SSE**.

### Respect des Invariants d'Architecture
- **Pas de modification dans `agentos/`**.
- **Pas de réimplémentation des briques existantes** :
  - `DurableAgentAttemptService` gère l'enregistrement, la réservation (`claim`), les transitions d'état et la finalisation sous bail/fencing (`finalize`).
  - `AgentOsExecutionAdapter` gère la création/récupération du case (`createOrRecoverExecution`), le démarrage de tour (`startTurn`), l'observation SSE/réconciliation (`observeTurn` / `reconcile`) et l'idempotence par `attemptId`.
- **Fall-back & Compatibilité** :
  - Conserver la voie de repli / polling existante si `AgentOsExecutionAdapter` n'est pas injecté ou désactivé (`factory.adapter.agentos.enabled=false`), tout en intégrant `AgentOsExecutionAdapter` en tant que pilote prioritaire lorsqu'il est présent.
- **Resserrement transactionnel (Étape 7)** :
  1. `claimNextStep()` / réservation tentative : transaction courte Neo4j (`REQUIRES_NEW`).
  2. `startOrRecoverRemoteExecution()` + `observeRemoteExecution()` : **HOURS / SANS TRANSACTION**.
  3. `finalizeAttempt()` + `persistProjection()` : transaction courte Neo4j (`REQUIRES_NEW`).
  4. `scheduleNextStep()` / re-déclenchement : après commit.

---

## 2. Découpage des Modifications par Composant

### 2.1. HTTP /run Async & Durable (Étape 3) dans `WorkflowController` et `SessionRunService`

#### Situation actuelle :
- `WorkflowController.run` appelle synchroniquement `sessionRunService.runSession(...)` qui exécute l'ensemble du DAG dans le thread de la requête HTTP.

#### Nouvelles exigences :
- Le point d'entrée HTTP `POST /run` (et `POST /continue`) doit répondre rapidement en `202 Accepted` (avec structure d'enveloppe acceptée et identité de suivi / ticket) ou basculer sur un déclenchement asynchrone rejouable sans bloquer la requête HTTP pendant le tour agent.
- Les rejouabilités après redémarrage sont garanties par l'état persistant dans Neo4j (`running` / `ready` / `durable_agent_attempts`) et l'OutboxWorker / Worker d'arrière-plan rejouable.
- Support des consommateurs existants : un paramètre ou mode d'exécution (ex: `async=true/false` ou retour structuré d'acceptation 202 avec `status="accepted"`, `workflowId`, `namespaceId`, `runId`/`attemptId`). Pour préserver la compatibilité des tests existants qui s'attendent à ce que `runSession` s'exécute de manière synchrone lorsqu'explicitement appelée en mode blocant/embedded, nous fournirons un mode d'orchestration non-bloquant rejouable par worker / executor borné pour l'API HTTP, tout en conservant `runSession` invocable de manière rejouable.

#### Modifications dans `WorkflowController.kt` :
- Mettre à jour `runInternal` pour accepter la soumission et déléguer à un exécuteur d'arrière-plan rejouable (ex: `TaskExecutor` / worker dédié ou `sessionRunService.runSessionAsync`) et renvoyer une réponse 202 Accepted (ou `200 OK` avec statut `"running"` / `"accepted"` selon le contrat attendu, avec fallback sync si désiré via paramètre query `sync=true`).
- Garantir qu'aucune connexion HTTP reste ouverte en attente d'un SSE/turn agent de longue durée.

---

### 2.2. Création / Démarrage / Observation rejouables (Étape 3 & 7) dans `CapabilityExecutionService` / `SessionRunService`

#### Intégration de `AgentOsExecutionAdapter` et `DurableAgentAttemptService` :

Dans `CapabilityExecutionService` (ou un adapteur dédié de capacité agent) :
1. **Étape 1 (Transaction Courte `REQUIRES_NEW`) : Reservation & Claim Attempt**
   - Créer et réserver `DurableAgentAttempt` dans `DurableAgentAttemptService.register`.
   - Obtenir le verrou/claim via `DurableAgentAttemptService.claim(...)` avec un `ownerToken` unique (ex: `UUID.randomUUID().toString()`) et une durée de bail (`leaseTtlMs`).
   - Générer/Rattacher le `caseId` et réserver l'enregistrement dans `agent_step_attempts`.
   - Commit de la transaction courte.

2. **Étape 2 (HORS TRANSACTION - SANS TRANSACTION NEO4J) : Execution Distante AgentOS**
   - **Création / Récupération rejouable** : Appeler `agentOsExecutionAdapter.createOrRecoverExecution(namespaceId, workflowId, stepId, externalUserId, attemptId, capabilityToken, caseId)`.
     - Si le `caseId` existe déjà ou si la tentative redémarre après un crash, le case est retrouvé et rattaché grâce à l'idempotence sur `attemptId`.
   - **Démarrage du tour (`startTurn`)** : Appeler `agentOsExecutionAdapter.startTurn(caseId, persona, brief, externalUserId, attemptId, capabilityToken)`.
     - Gestion d'idempotence : si le tour est déjà démarré ou si une ambiguïté survient, appeler `reconcile(caseId)` pour vérifier si le tour est déjà en cours ou terminé.
   - **Observation du tour (`observeTurn`)** : Appeler `agentOsExecutionAdapter.observeTurn(caseId, attemptId, timeoutMs)`.
     - L'observation s'effectue via SSE avec réconciliation REST.
     - En cas d'interruption ou timeout d'observation, obtenir le verdict `AgentOsExecutionVerdict` (`Succeeded`, `Failed`, `WaitingHuman`, `Interrupted`, `Indeterminate`).

3. **Étape 3 (Transaction Courte `REQUIRES_NEW`) : Finalisation & Séquencement**
   - Appeler `DurableAgentAttemptService.finalize(...)` avec `ownerToken`, le statut final correspondant (`SUCCEEDED`, `FAILED`, `WAITING_HUMAN`, `INDETERMINATE`, `INTERRUPTED`), le code d'échec éventuel et l'ID d'évidence.
   - Persister l'évidence `agent-result` / `agent-turn`.
   - Mettre à jour l'étape et déclencher le recalcul du DAG dans la Factory.

---

### 2.3. Persistance des Sorties A -> B et Séquencement (Étape 6)

#### Traitement du Verdict A et transmission vers B :
1. **Pendant la transaction courte de finalisation de l'Étape A** :
   - Vérifier le `ownerToken` et l'état de la tentative (`fencing / lease`).
   - Valider le verdict structuré `AgentOsExecutionVerdict` :
     - Si `AgentOsExecutionVerdict.Succeeded(outputs, evidence)` :
       - Extraire et structurer les sorties (fichiers, artefacts, payload de verdict, hashes).
       - Persister ces sorties structurées dans le payload de la tentative A (`AgentStepAttemptRecord.payload`) et dans l'évidence `agent-result` (`WorkflowEvidenceItem.facts`).
       - Passer la tentative A à `SUCCEEDED` / `completed`.
       - Passer l'étape A à `WorkflowStatuses.COMPLETED`.
     - Si `AgentOsExecutionVerdict.Failed` / `Indeterminate` / `Interrupted` :
       - Persister les faits d'erreur dans l'évidence.
       - Passer la tentative A à `FAILED` / `INDETERMINATE`.
       - Passer l'étape A à `WorkflowStatuses.FAILED`.
     - Si `AgentOsExecutionVerdict.WaitingHuman` :
       - Suspendre l'étape A en `WorkflowStatuses.WAITING_HUMAN`.

2. **Calcul des étapes prêtes et Brief de B** :
   - Recalculer les étapes prêtes via `SessionSequencer.evaluate`.
   - Rendre l'étape B `ready` **SEULEMENT** si toutes ses dépendances sont méticuleusement satisfaites (A terminée avec succès). Si A a échoué ou est bloquée/suspendue, B **ne démarre JAMAIS** (reste `pending` ou passe `blocked`).
   - **Construction du Brief de B** : Le brief de l'étape B est assemblé par le code Factory en lisant les sorties **durablement persistées** de A (artefacts/facts de l'évidence `agent-result` de A), et **JAMAIS** en lisant le dernier message libre/non structuré du chat de l'agent.

---

### 2.4. Integration dans `SessionRunService` & Resserrement Transactionnel Strict (Étape 7)

- `SessionRunService.runSession` conserve son orchestration non-transactionnelle principale, sérialisée par verrou de workflow en mémoire (`withWorkflowLock`).
- Chaque opération sur la base de données Neo4j est encapsulée dans une transaction courte via `newTransaction { ... }` (utilisant `TransactionTemplate` avec `PROPAGATION_REQUIRES_NEW`).
- Structure de la boucle d'exécution de step agent :
  ```kotlin
  // 1. Claim step + Claim Durable Attempt (Tx courte)
  val claim = newTransaction {
      claimStepAndAttempt(scope, namespaceId, workflowId, step, ownerToken)
  }

  // 2. Exécution & Observation distante AgentOS (SANS TRANSACTION Neo4j)
  val verdict = executeAndObserveAgentTurn(claim)

  // 3. Finalisation Attempt + Evidence + Transition Step + Projection (Tx courte)
  newTransaction {
      finalizeAttemptAndTransitionStep(scope, namespaceId, workflowId, step, claim, verdict)
  }

  // 4. Séquencement suivant (après commit)
  ```

---

## 3. Stratégie de Tests d'Intégration & Validation

Rédiger une suite de tests d'intégration complète dans `factory-service` (ex: `DurableAgentOsBridgeIntegrationTest.kt`) validant l'ensemble des critères d'acceptation :

1. **Test "A bloque -> B ne démarre jamais"** :
   - Configurer un DAG A -> B.
   - Faire échouer A ou suspendre A en `WAITING_HUMAN`.
   - Vérifier que B reste `PENDING` ou passe `BLOCKED` et qu'aucune exécution de B n'est invoquée.

2. **Test "A se termine avec une sortie identifiable -> B démarre avec exactement cette sortie"** :
   - Configurer A -> B.
   - Exécuter A produisant une sortie structurée `outputs = mapOf("artifactHash" to "sha256:1234", "summary" to "Build OK")`.
   - Vérifier que A passe `COMPLETED`, puis B passe `RUNNING` et reçoit dans son brief/contexte la sortie persistée de A (provenant de l'évidence/payload de A).

3. **Test "Concurrency : Deux demandes concurrentes sur A"** :
   - Simuler deux demandes simultanées d'exécution du run.
   - Vérifier grâce au verrou de workflow et à l'atomic claim dans `DurableAgentAttemptService.claim` qu'une seule tentative possède l'exécution, la seconde étant rejetée ou idempotentée.

4. **Test "Crash après création du case mais avant persistance locale (Recovery)"** :
   - Simuler la création d'un case côté AgentOS, puis simuler un redémarrage/crash avant finalisation.
   - Relancer le run : vérifier que `createOrRecoverExecution` retrouve le case existant grâce à l'idempotence sur `attemptId` sans récréer un nouveau case dupliqué.

5. **Test "Crash après acceptation du message mais avant réponse HTTP (Idempotence)"** :
   - Simuler un échec réseau post-`startTurn`.
   - Relancer avec le même `attemptId` : vérifier que l'adapter utilise `reconcile` / `observeTurn` et n'envoie pas un second tour en doublon.

6. **Test "Tour plus long que le timeout Neo4j (Découpage transactionnel)"** :
   - Simuler un tour agent d'une durée arbitraire (ex. simuler une attente de plusieurs secondes sans transaction active).
   - Vérifier qu'aucune exception de transaction expirée/fermée ("Cannot run more queries in this transaction") n'est levée lors de la finalisation post-tour.

---

## 4. Fichiers à créer / modifier

| Fichier | Action | Description des modifications |
| :--- | :--- | :--- |
| `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt` | Modifier | Mettre à jour `/run` et `/continue` pour exécuter/déléguer de façon asynchrone durable ou rejouable (retour 202 / statut running) sans retenir la connexion HTTP pendant le tour agent. |
| `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt` | Modifier | Orchestrer l'exécution via transactions courtes (`REQUIRES_NEW`), déléguer l'exécution AgentOS sans transaction ouverte, gérer la rejouabilité et la persistance des sorties A->B. |
| `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt` | Modifier | Intégrer l'usage combiné de `DurableAgentAttemptService` et `AgentOsExecutionAdapter` lorsqu'activé. |
| `factory-service/src/test/kotlin/io/whozoss/factory/workflow/DurableAgentOsBridgeIntegrationTest.kt` | Créer | Tests d'intégration complets validant les 6 scénarios requis. |

---

## 5. Plan de Vérification

1. **Compilation Kotlin & Validation Gradle/Nx** :
   ```bash
   pnpm nx test factory-service
   ```
2. **Exécution ciblée de la suite de tests d'intégration** :
   ```bash
   ./gradlew :factory-service:test --tests "io.whozoss.factory.workflow.DurableAgentOsBridgeIntegrationTest"
   ```
3. **Vérification de la régression sur l'ensemble des tests du monorepo** :
   ```bash
   pnpm test
   ```
