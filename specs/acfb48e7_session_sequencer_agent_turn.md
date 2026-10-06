# Plan W8.3 — Séquenceur de Session DAG & Capacité Agent-Turn HTTP

## Vision Globale & Invariants
L'objectif de W8.3 est d'implémenter l'exécution automatique et autonome d'une session déclarative (DAG de steps) et d'y intégrer la capacité `agent` via des appels HTTP vers AgentOS.

Conformément à la feuille de route (`plans/2026-09-27-factory-instrument.md`) et aux décisions prises :
1. **Séquenceur DAG (`SessionSequencer` / `SessionRunService`)** :
   - Déroulement **automatique** et autonome du DAG sans intervention pas-à-pas.
   - **Règle d'échec** :
     - Step `PASS` / `COMPLETED` -> ses dépendants directs deviennent candidats (`ready`).
     - Step `FAIL` / Erreur -> passe en `failed` ; **TOUS** ses dépendants transitifs passent en `blocked` ; les branches indépendantes **CONTINUENT**.
     - Step `HUMAN` -> ouvre une interaction (`human_interactions`) et **SUSPEND** la session dans l'état `waiting_human`. La session reprend à l'arrivée de la réponse humaine.
     - État terminal de la session : `completed` quand tous les steps sont passés, `failed` si au moins un step a échoué/bloqué et qu'aucun step n'est plus exécutable (`ready`).
     - **Pas de retry automatique** (la reprise après échec est conversationnelle hors séquenceur).
   - **Exécution** : Séquentielle-dans-le-parallèle (ou boucle d'évaluation des steps `ready`). Par simplicité et sécurité déterministe, à chaque itération le séquenceur évalue les steps `ready`, en exécute un ou tous les ready indépendants, applique les transitions et réévalue jusqu'à quiescence/suspension/fin.
   - **Traçabilité & Persistance** : Chaque step exécuté enregistre sa transition (`workflow_transitions`), son evidence (`workflow_evidence`), son état de step (`workflow_step_states`), et met à jour la projection d'instance (`workflow_projections` / `workflow_instances`).
   - **Idempotence / Reprise** : Si relancé sur une session en cours/suspendue, reprend sans re-tester les steps déjà `completed`.

2. **Capacité Agent-Turn HTTP (`HttpAgentOsProxyClient` + `AgentOsAgentTurnCapability`)** :
   - Ajout de `executeAgentTurn(...)` à `AgentOsProxyClient` et son implémentation `HttpAgentOsProxyClient`.
   - Appels HTTP stricts vers AgentOS (`/api/cases`, `/api/cases/{caseId}/messages`, `/api/cases/{caseId}/events`, etc.). **Règle absolue : AUCUN import de classe Kotlin d'AgentOS** (frontière `DEPENDENCY_MATRIX`).
   - Branchement dans `CapabilityResolver` pour `kind = agent` via un `AgentOsAgentTurnCapability` (ou extension de `CapabilityResolver`).
   - Ouvre une tentative `agent_step_attempts`, envoie le brief/message à AgentOS, suit le case jusqu'à quiescence (`IDLE`, `KILLED`, `ERROR` ou événements), extrait le résultat/fichiers modifiés, et clôt l'attempt + enregistre l'evidence.
   - Si AgentOS est injoignable ou en erreur : échec propre et explicite (FAIL), conforme à la règle d'échec.

3. **Invariants d'Architecture & Validation** :
   - `factory-verification-core` reste 100% PUR (aucun import HTTP/Spring). Le client AgentOS HTTP est dans `factory-service` (`io.whozoss.factory.proxy`).
   - Séquenceur & services d'orchestration dans `factory-service` (`io.whozoss.factory.workflow`).
   - Tests d'intégration dans `factory-service` étendant `DomainIntegrationTest` (PostgreSQL conteneurisé). **PAS de nouvelle variante `@SpringBootTest`**.
   - Utilisation de **fakes/mocks HTTP AgentOS** dans les tests (ne jamais appeler de vrai AgentOS).
   - Mettre à jour `plans/2026-09-27-factory-instrument.md` en marquant W8.3 fait.

---

## Architecture & Composants à créer / modifier

### 1. `io.whozoss.factory.proxy` (Client HTTP AgentOS & Agent-Turn)

- **`AgentOsProxyClient.kt`** :
  Ajouter le contrat d'exécution d'un tour d'agent :
  ```kotlin
  fun executeAgentTurn(
      namespaceId: String,
      persona: String,
      stepId: String,
      workflowId: String,
      repoRoot: Path,
      externalUserId: String? = null
  ): AgentTurnResult
  ```

- **`HttpAgentOsProxyClient.kt`** :
  Implémenter `executeAgentTurn` :
  - `POST /api/cases` : Créer un Case dans le `namespaceId` avec un titre/context lié au step (`stepId`).
  - `POST /api/cases/{caseId}/messages` : Poster le brief initial / la consigne à destination de la persona.
  - Polling / Attente de quiescence : Interroger `GET /api/cases/{id}` jusqu'à ce que `status` devienne `IDLE` (succès), `KILLED` ou `ERROR` (échec), avec timeout configurable.
  - Récupération des événements / faits : `GET /api/case-events/by-parentId/{caseId}` pour extraire les faits/résultats/résumés.
  - Support de gestion d'erreur / indisponibilité : `AgentOsUnavailableException` ou retour `AgentTurnResult` en échec.

- **`AgentOsAgentTurnCapability.kt`** (dans `io.whozoss.factory.capability` ou `proxy`) :
  Implémente `AgentTurnCapability`.
  1. Génère un `attemptId`.
  2. Insère un enregistrement dans `agent_step_attempts` (statut `running`).
  3. Appelle `agentOsProxyClient.executeAgentTurn(...)`.
  4. Selon le résultat (Succès/Échec/Timeout), clôture l'attempt dans `agent_step_attempts` (statut `completed` / `failed`), et retourne `AgentTurnResult.Completed(...)`.

- **`CapabilityResolver.kt`** & **`ProxyConfiguration.kt`** :
  Brancher la nouvelle implémentation `AgentOsAgentTurnCapability` à la place de `NoOpAgentTurnCapability` par défaut dans la configuration Spring (`CapabilityResolver` injecte `AgentTurnCapability`).

---

### 2. `io.whozoss.factory.workflow` (Persistance Step States & Séquenceur DAG)

- **`WorkflowStepStateRecord.kt`** (dans `domain/WorkflowModels.kt`) :
  Représente une ligne de la table `workflow_step_states` (V3) :
  ```kotlin
  data class WorkflowStepStateRecord(
      val namespaceId: String,
      val workflowId: String,
      val stepId: String,
      val revision: Int,
      val status: String, // pending, ready, running, completed, failed, blocked, waiting_human
      val payload: Map<String, Any?> = emptyMap(),
      val createdAt: Instant? = null,
      val updatedAt: Instant? = null,
  )
  ```

- **`WorkflowRepository.kt` & `JdbcWorkflowRepository.kt`** :
  Ajouter les méthodes d'accès à `workflow_step_states` :
  - `findStepStates(scope, namespaceId, workflowId): List<WorkflowStepStateRecord>`
  - `upsertStepState(scope, record: WorkflowStepStateRecord)`
  - `updateStepStatus(scope, namespaceId, workflowId, stepId, expectedRevision, nextStatus, payload): Boolean`

- **`SessionSequencer.kt`** (dans `service/` ou `domain/`) :
  Moteur pur/service qui calcule l'état du DAG et applique les règles de transition :
  - **Inputs** : Liste des `WorkflowStepDefinition`, état courant des `WorkflowStepStateRecord` pour l'instance.
  - **Méthodes** :
    - `calculateNextStates(...)` :
      - Pour chaque step en `pending` : si toutes ses dépendances dans `dependsOn` sont `completed` -> passe en `ready`.
      - Si une dépendance dans `dependsOn` est `failed` ou `blocked` -> passe en `blocked`.
    - `determineOverallStatus(...)` :
      - Si au moins un step est `running` ou `waiting_human` -> status d'instance correspondant / `active`.
      - Si tous les steps sont `completed` -> `completed`.
      - Si aucun step n'est `ready` ni `running`, et qu'il y a des `failed`/`blocked` -> `failed`.

- **`SessionRunService.kt`** :
  Service Spring orchestrateur (`@Service`) :
  - `startOrResumeSession(scope: TenantScope, namespaceId: String, workflowId: String, repoRoot: Path): WorkflowInstanceRecord`
    1. Charge l'instance et sa définition de workflow (`WorkflowDefinitionRecord`).
    2. Si l'instance est nouvelle (`pending`), initialise les `workflow_step_states` pour tous les steps (steps sans dépendances en `ready`, autres en `pending`).
    3. Boucle de déroulement du DAG :
       - Récupère tous les steps actuellement `ready`.
       - Si aucun step `ready` : met à jour le statut global de la session (`completed`, `failed`, ou `waiting_human`) et termine.
       - Pour chaque step `ready` :
         a. Passe le step en `running` dans `workflow_step_states`.
         b. Appelle `CapabilityExecutionService.resolveAndRecord(...)`.
         c. Analyse le `CapabilityExecution` / `CapabilityOutcome` :
            - **`CodeExecuted`** :
              - Verdict `true` -> Step `completed`.
              - Verdict `false` -> Step `failed`.
            - **`AgentCompleted`** :
              - Statut `pass` / `COMPLETED` -> Step `completed`.
              - Statut `fail` / `ERROR` -> Step `failed`.
            - **`HumanCheckpointRequired`** :
              - Step `waiting_human`.
              - Suspend la boucle de session -> instance globale passe en `waiting_human`.
            - **Échec / Exception** : Step `failed`.
         d. Enregistre l'état terminal du step dans `workflow_step_states` + enregistre la transition dans `workflow_transitions`.
         e. Réévalue les dépendants : passe en `blocked` les dépendants transitifs des steps `failed`.
       - Répète la boucle jusqu'à ce qu'il n'y ait plus de steps `ready` ou que la session soit suspendue (`waiting_human`).
    4. Publie/met à jour la projection de l'instance (`workflow_projections` / `workflow_instances`).

- **`WorkflowController.kt`** :
  Endpoints REST pour piloter les sessions :
  - `POST /api/factory/workflows/{workflowId}/run` (ou réutilisation de `POST /api/factory/workflows` pour instancier + lancer) : démarre ou reprend le déroulé de la session.
  - `GET /api/factory/workflows/{workflowId}` : renvoie l'état de l'instance, des steps (`workflow_step_states`), des transitions et de la projection.

---

### 3. Strategy de Test (`factory-service/src/test/kotlin/...`)

1. **`AgentOsProxyMockTest.kt` / `AgentOsAgentTurnCapabilityTest.kt`** :
   - Test unit/intégration léger de `HttpAgentOsProxyClient` et `AgentOsAgentTurnCapability` avec `MockRestServiceServer`.
   - Scénarios :
     - Succès AgentOS : Création case -> message -> status IDLE -> retour `PASS`.
     - Échec / Error AgentOS : Case status ERROR -> retour `FAIL`.
     - Timeout / Injoignable : Serveur répond 500 ou time out -> levée propre de `AgentOsUnavailableException` ou verdict `FAIL`.

2. **`SessionSequencerIntegrationTest.kt`** (étend `DomainIntegrationTest`) :
   - Test d'intégration complet du séquenceur DAG avec PostgreSQL.
   - **Test 1 : DAG Linéaire (Step1 Code -> Step2 Code)** :
     - Step 1 pass -> Step 2 ready -> Step 2 pass -> Session `completed`.
   - **Test 2 : DAG Parallèle / Branches indépendantes avec échec** :
     - Step A (Backend, pass) -> Step B (Backend-Verify, dépend de A).
     - Step C (Frontend, fail) -> Step D (Frontend-Verify, dépend de C).
     - Vérifier : Step C passe en `failed`, Step D passe en `blocked`. Step A passe en `completed`, Step B passe en `ready` puis `completed`. Session finale `failed`.
   - **Test 3 : Step Human (Suspension & Reprise)** :
     - Step 1 Code (pass) -> Step 2 Human -> Step 3 Code (dépend de 2).
     - Déroulé 1 : Step 1 pass -> Step 2 `waiting_human` -> Session `waiting_human`.
     - Résolution humaine (simulation réponse) -> Relance session -> Step 2 `completed` -> Step 3 pass -> Session `completed`.
   - **Test 4 : Capacité Agent-Turn Fake** :
     - Step Agent avec un fake `AgentTurnCapability`.

---

## Étapes de Réalisation (Commits)

### Étape 1 : Persistance des états de steps & Modèle de Séquenceur
- Créer `WorkflowStepStateRecord` et étendre `WorkflowRepository` / `JdbcWorkflowRepository` pour gérer `workflow_step_states`.
- Implémenter la logique pure de calcul de graphe `SessionSequencer`.

### Étape 2 : Client HTTP Agent-Turn & Capability
- Étendre `AgentOsProxyClient` & `HttpAgentOsProxyClient` pour supporter `executeAgentTurn` (créer case, poster message, attente quiescence).
- Créer `AgentOsAgentTurnCapability` gérant le cycle de vie `agent_step_attempts`.
- Brancher `AgentOsAgentTurnCapability` dans `CapabilityResolver`.

### Étape 3 : Service Séquenceur `SessionRunService` & Endpoints REST
- Implémenter `SessionRunService` déroulant le DAG (boucle d'évaluation, gestion des règles d'échec, propagation `blocked`, suspension `waiting_human`, mise à jour des projections/instances/transitions/evidences).
- Exposer/mettre à jour les endpoints dans `WorkflowController`.

### Étape 4 : Tests d'Intégration BDT / DAG & Agent-Turn Mock
- Écrire `SessionSequencerIntegrationTest` couvrant DAG linéaire, branches indépendantes avec échec/blocked, suspension/reprise human.
- Écrire les tests mock RestClient pour `AgentOsAgentTurnCapability`.
- S'assurer que `./gradlew clean test` passe à 100%.

### Étape 5 : Mise à jour de la documentation & Roadmap
- Mettre à jour `plans/2026-09-27-factory-instrument.md` (marquer W8.3 comme complété).

---

## Directives pour le Builder
- Respecter strictement `DomainIntegrationTest` pour tous les tests d'intégration Spring.
- Ne modifier AUCUNE migration Flyway (les tables `workflow_step_states`, `workflow_transitions`, `agent_step_attempts` existent déjà dans V3/V6).
- Garder `factory-verification-core` vierge de toute dépendance Spring/HTTP.
- Ne pas importer de types Kotlin venant d'AgentOS. Le contrat est uniquement HTTP/JSON.
- Valider le build complet via `./gradlew clean test`.
