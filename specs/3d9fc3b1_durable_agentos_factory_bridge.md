# Plan: Rendre le bridge AgentOS-Factory opérationnel et reprenable

## Context & Objectives

L'objectif de cette intervention est de rendre le bridge entre **AgentOS** (le runtime agentique Spring Boot / PF4J) et la **Factory** totalement opérationnel, sécurisé, persistant (restart-safe) et automatiquement chargé au démarrage.

Toutes les consignes et contraintes strictes suivantes sont respectées :
1. **PAS de modification du cœur AgentOS** pour ajouter turnId/commandId/Last-Event-ID (phase 1).
2. **Protocole d'observation** : Replay SSE complet + déduplication par `eventId`. Maintien et persistance du High-Water Mark `(timestamp, id)`.
3. **NE PAS TOUCHER** la logique de polling existante dans `HttpAgentOsProxyClient` (elle demeure le mécanisme principal de la Factory).
4. **S'assurer que le plugin PF4J est chargeable et chargé** via la copie de la JAR dans `plugins/` lors du build (`./gradlew deployPlugins` et tâche de build globale/assemblage).

---

## Architecture & Design Decisions

### 1. (a) Câblage du transport Host dans `agentos-service`

Côté `agentos-service`, un mécanisme doit intercepter la création de case (ou la réception des requêtes de création/turn de la Factory portant les headers `X-Factory-*`) pour enregistrer le binding auprès de `FactoryStepResultBindingRegistry`.

- **Nouveau contrôleur Spring / Handler dans `agentos-service`** : `FactoryCaseBindingController` (ou extension de `CaseController` / filtre/intercepteur HTTP) exposant ou interceptant les métadonnées de binding lors de `POST /api/cases`.
  - La Factory (via `HttpAgentOsProxyClient`) envoie les headers :
    - `X-Factory-Attempt-Id` (ou `X-Attempt-Id`)
    - `X-Factory-Capability-Token` (ou `X-Capability-Token` / `X-Factory-Capability-Token`)
    - `X-Factory-Runtime-Id` (ou `X-Runtime-Id`)
  - Alternativement/Complémentairement, l'endpoint dédié de binding `POST /api/cases/{caseId}/factory-binding` (déjà conçu dans `FactoryStepResultBindingController` du plugin) doit être exposé dans l'application host via un contrôleur Spring (`FactoryBridgeBindingEndpoint`) déléguant au `FactoryStepResultBindingController` du plugin PF4J (ou `FactoryStepResultBindingRegistry` partagé).
  - Lors du `POST /api/cases`, si les headers `X-Factory-*` (ou le body) sont présents et qu'un `shared-secret` (configuré par env `AGENTOS_FACTORY_BRIDGE_SECRET` / `agentos.factory-bridge.secret`) est valide, le host effectue le binding immédiatement dans le `FactoryStepResultBindingRegistry`.
  - Ainsi, `FactoryExternalExecutionContextProvider` (extension PF4J) trouve le binding actif et injecte `capabilityToken`, `attemptId`, `runtimeId` dans le contexte d'exécution de la session message au lieu d'un DTO vide `{}`.

### 2. (b) Persistance durable de `bindings`, `leases` et `pending-checkpoints` dans le plugin

Actuellement, `FactoryStepResultBindingRegistry` et `pendingCheckpoints` dans `FactoryBridgeServices` utilisent un `ConcurrentHashMap` volatile en mémoire.
Pour survivre au redémarrage d'AgentOS :

- **Persistent Store File-Backed / KV Store simple dans `agentos-factory-bridge-plugin`** :
  - Implémenter un store persistant fichier JSON (ou SQLite / MapDB / KV store atomique sur disque) local au plugin (ex: dans `data/factory-bridge/` ou configuré via `AGENTOS_FACTORY_BRIDGE_DATA_DIR`).
  - **`FactoryBridgeStateStore`** :
    - Gère la sérialisation / désérialisation atomique (flush sur disque à chaque écriture / mise à jour atomique avec verrou fichier ou fichier temporaire + atomic rename).
    - Persiste :
      1. `bindings` : `Map<UUID, FactoryStepResultBindingState>` comprenant caseId, namespaceId, agentName, attemptId, runtimeId, capabilityToken, expiresAt, `leased: Boolean`.
      2. `pendingCheckpoints` : `Map<UUID, FactoryCheckpointRef>` (workflowId, interactionId, interactionRevision).
  - **Single-Flight Lease CAS (Compare-And-Swap)** :
    - La sémantique de lease doit rester strictement **single-flight** : `acquire(caseId, namespaceId, agentName)` bascule atomiquement `leased` de `false` à `true` et persiste l'état immédiatement sur disque.
    - Si `leased == true`, ou si l'expiration est dépassée, ou si namespace/agent ne correspondent pas -> rejet (`null` / fail-closed).
  - **Invalidation & Fail-Closed** :
    - Les cas terminaux (status `KILLED`, `ERROR`) ou expirés déclenchent la suppression du binding sur disque (`invalidate` / `remove`).

### 3. (c) SSE High-Water Mark Checkpoint Bridge-Side Restart-Safe

Création d'un composant de persistance et de lecture du High-Water Mark SSE pour le bridge.

- **Composant `FactorySseHighWaterMarkStore`** :
  - Clé composite : `(caseId: UUID, attemptId: String)` ou `(workflowId: String, attemptId: String)`.
  - Valeur : `SseHighWaterMark(timestamp: Instant, lastEventId: String)`.
  - Persisté sur disque dans le même répertoire de données du plugin (fichier JSON dédié ou section du state store).
  - Méthodes :
    - `fun getHighWaterMark(caseId: UUID, attemptId: String): SseHighWaterMark?`
    - `fun advanceHighWaterMark(caseId: UUID, attemptId: String, timestamp: Instant, eventId: String)`
  - Permet lors du replay SSE ou au redémarrage de filtrer les événements déjà traités (déduplication) dont le timestamp/id est <= au high-water mark persistant.

### 4. (d) Packaging, build et chargement automatique du plugin PF4J

- **Gradle Build & Deployment** :
  - S'assurer que le module `agentos-factory-bridge-plugin` est inclus dans la tâche `deployPlugins` de Gradle (`agentos/build.gradle.kts`). (Vérifié : il est déjà présent dans `pluginBuilds`).
  - Ajouter / s'assurer qu'un hook de build ou tâche Gradle dans `agentos-service` (ex. `processResources` ou `bootJar` / `prepPlugins`) exécute la copie de `agentos-factory-bridge-plugin.jar` dans le répertoire `plugins/` de l'application service lors des builds, afin que PF4J le charge automatiquement au démarrage.
  - Documenter le packaging, le déploiement et la configuration dans `agentos/agentos-factory-bridge-plugin/README.md` et `agentos/docs/plugin-system.md`.

---

## Detailed Task Breakdown

### Task 1: Binding Host Transport Wiring in `agentos-service`

**Files to modify / create:**
1. `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/factory/FactoryBridgeBindingController.kt` (nouveau)
   - Contrôleur Spring REST à l'adresse `/api/factory-bridge/bind/{caseId}` (ou `/api/cases/{caseId}/factory-binding`).
   - Reçoit `X-Factory-Secret` (ou query param / header authorization), vérifie le shared secret configurable (`agentos.factory-bridge.secret`).
   - Reçoit la payload DTO `FactoryStepResultBindingRequest` (namespaceId, agentName, attemptId, runtimeId, capabilityToken, expiresAt).
   - Injecte ou résout le `FactoryStepResultBindingRegistry` (disponible via le plugin chargé PF4J ou un bean Spring pont).
2. Interception lors de `POST /api/cases` dans `CaseController.kt` / `CaseService.kt` :
   - Extrait les headers HTTP `X-Factory-Attempt-Id`, `X-Factory-Capability-Token`, `X-Factory-Runtime-Id`, `X-Factory-Agent-Name`, `X-Factory-Expires-At` s'ils sont fournis lors de la création de case.
   - Si tous ces headers sont présents, effectue automatiquement l'enregistrement du binding pour le case créé.

### Task 2: Durable Persistence for Bindings, Leases, and Pending Checkpoints

**Files to modify / create in `agentos-factory-bridge-plugin`:**
1. `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/persistence/FactoryBridgeStateStore.kt` (nouveau)
   - Gère le fichier JSON persistant (ex. `bridge-state.json` dans le répertoire configuré).
   - Sauvegarde thread-safe et atomique avec locks en écriture.
2. `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryStepResultBindingRegistry.kt`
   - Refactoriser pour utiliser `FactoryBridgeStateStore` au lieu du pure `ConcurrentHashMap` volatile.
   - Conserver l'interface publique, mais assurer la synchronisation et la persistance immédiate lors de `bind`, `acquire` (CAS), `acknowledge`, `release`, `invalidate`, `remove`.
3. `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryBridgeServices.kt`
   - Mettre à jour `pendingCheckpoints` pour qu'il s'appuie sur le store persistant `FactoryBridgeStateStore`.

### Task 3: Bridge-Side Restart-Safe SSE High-Water Mark Store

**Files to modify / create in `agentos-factory-bridge-plugin`:**
1. `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/persistence/FactorySseHighWaterMarkStore.kt` (nouveau)
   - Enregistre et lit les objets `SseHighWaterMark(timestamp: Instant, lastEventId: String)`.
   - Clé d'indexation : `(caseId: UUID, attemptId: String)`.
   - Persistance fichier JSON atomique thread-safe.
2. `agentos/agentos-factory-bridge-plugin/src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/FactorySseHighWaterMarkStoreSpec.kt` (nouveau)
   - Tests unitaires validant la lecture/écriture, le filtrage dédupliqué et la survie au rechargement.

### Task 4: Plugin Packaging, Gradle Integration & Documentation

**Files to modify:**
1. `agentos/build.gradle.kts` & `agentos/agentos-service/build.gradle.kts`
   - S'assurer que le pipeline Gradle assure le dépôt de `agentos-factory-bridge-plugin.jar` dans `plugins/`.
2. `agentos/agentos-factory-bridge-plugin/README.md`
   - Documenter le packaging PF4J JAR, la configuration des variables d'environnement (`AGENTOS_FACTORY_BRIDGE_SECRET`, `AGENTOS_FACTORY_BRIDGE_DATA_DIR`), le binding endpoint et la persistance.

---

## Verification & Acceptance Tests Plan

1. **Test d'Intégration Binding & Execution Context** :
   - Vérifier qu'un case créé avec les headers `X-Factory-*` (ou via l'endpoint de binding) initialise un binding valide.
   - Interroger `FactoryExternalExecutionContextProvider` et valider qu'il retourne la map contenant `capabilityToken`, `attemptId`, `runtimeId` (non vide `{}`).

2. **Test de Durabilité après Simulation de Redémarrage** :
   - Créer un binding et une lease CAS.
   - Instancier un nouveau `FactoryStepResultBindingRegistry` pointant sur le même fichier de store (simulant un redémarrage d'AgentOS).
   - Vérifier que le binding non terminé et son état `leased` sont fidèlement restaurés.
   - Vérifier que l'invalidation sur case terminal (`KILLED`/`ERROR`) nettoie correctement le store persistant (principe fail-closed).

3. **Test Single-Flight CAS Lease** :
   - Tester l'acquisition simultanée/séquentielle de lease pour vérifier que seul un unique appel réussit (`compareAndSet` atomique persistant) après redémarrage ou en concurrence.

4. **Test Persistance High-Water Mark SSE** :
   - Avancer le high-water mark SSE `(timestamp, id)` pour un couple `(case, attempt)`.
   - Réinstancier le store et vérifier que la valeur relue est identique.

5. **Exécution des Suites de Tests** :
   - `cd agentos && ./gradlew :agentos-factory-bridge-plugin:test`
   - `cd agentos && ./gradlew :agentos-service:test`
