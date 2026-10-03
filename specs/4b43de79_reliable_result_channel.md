# Plan — Phase 1 : fiabiliser le canal de résultat structuré worker → Factory

> Objectif : rendre fiable et autoritaire le canal de soumission de résultat structuré
> (worker → Factory) **avant** d'introduire le Workstream Agent. Code autoritaire :
> `factory-service/` (Kotlin / Spring Boot, Neo4j embarqué).

## Contexte autoritaire (lire avant de coder)

- `app_docs/workstream_agent_cartography_and_contracts.md` — carte des faits, identités, codes d'erreur.
- `docs/adr/0001-workstream-agent-factory-worker-separation.md` — invariants d'autorité.
- `CLAUDE.md` — style (pas de `;`, single quotes côté TS ; Kotlin : types explicites, kebab-case fichiers).

### Invariants à préserver (ne jamais violer)

1. La Factory reste l'**unique autorité** des faits.
2. Les identités (`attemptId`, `caseId`, `namespaceId`, `workflowId`, `stepId`, `agent`) viennent de
   la **frontière de confiance** (`TrustContext` / `ToolContext` / capability token), **jamais** des
   arguments rédigés par le modèle.
3. Une tentative terminale est **immutable**.
4. **Pas de fin inférée du silence** ; un dernier message brut n'est jamais un résultat autoritatif.
5. Ne **pas** toucher aux migrations Neo4j ni aux pipelines de release.
6. Commits conventionnels, focalisés, un par étape/composant, sur la branche sandbox courante
   (`sbx/coday-result-channel-0c63`).

### Vérification (tests lancés par l'usine après chaque build — ne pas les lancer soi-même)

```
pnpm nx affected -t test  --base="$(cat /work/data/baseline)" --parallel=2
pnpm nx affected -t lint  --base="$(cat /work/data/baseline)"
pnpm nx affected -t build --base="$(cat /work/data/baseline)"
```
Cible directe : `pnpm nx test factory-service` (délègue à `./gradlew test`).

---

## État actuel (vérifié) — ce qui existe déjà

Chemins relatifs à `factory-service/src/main/kotlin/io/whozoss/factory/`.

- **Service applicatif** : `agentattempt/service/AgentStepResultService.kt` (`@Service`).
  - `issue(scope, identity, now, ttl)` → `IssuedCapability` (token clair rendu une seule fois).
  - `resolveCapability(scope, token)` → `AgentStepResultCapability?` (lecture seule).
  - `submit(scope, token, business, observed, idempotencyKey, now)` → `AgentStepResultSubmission`.
- **Repository** : `agentattempt/persistence/Neo4jAgentStepResultRepository.kt` (`@Repository`,
  impl de `AgentStepResultRepository`). `submit(...)` écrit **dans une seule transaction** :
  `result-submitted` (via `insertSubmitted`/`updateResult`), `attempts.terminalize(...)`, puis
  `insertOutbox(...)` (`result_submitted`).
- **Idempotency** : `agentattempt/persistence/Neo4jIdempotencyRepository.kt` + port
  `IdempotencyRepository` ; couche `X-Idempotency-Key` sur `IdempotencyRecordNode`
  (clé composite `(organizationId, idempotencyKey)`).
- **Contrôleurs** : `agentattempt/web/AgentStepResultController.kt`
  (`POST /api/factory/agent-step-results`) et `FactoryStepResultBindingController.kt`
  (`POST /api/factory/step-result-bindings`).
- **Runner** : `capability/CapabilityExecutionService.kt` (`@Service`).
- **Récupération démarrage** : `agentattempt/service/BridgeRecoveryWorker.kt`
  (`@Component`, `@EventListener(ApplicationReadyEvent)`, gated `factory.adapter.agentos.enabled`).
- **Outbox** : `agentattempt/service/OutboxDrainService.kt` + `OutboxDrainWorker.kt`
  (`@Scheduled`, gated `factory.outbox.drain-enabled`) → sur `result_submitted`, avance le DAG via
  `SessionRunService.runSession(...)` **après** commit de la transaction de drain.
- **Verdict** : `adapter/agentos/VerdictDeriver.kt`.
- **Validation** : `agentattempt/domain/AgentStepResultValidation.kt` ; hash canonique :
  `agentattempt/domain/CanonicalJsonHash.kt`.
- **Codes d'erreur** : `agentattempt/domain/AgentStepResultModels.kt`
  (`AgentAttemptErrorCodes` + exceptions `AgentAttemptException`). `RESULT_IDENTITY_MISMATCH`,
  `RESULT_SEMANTIC_COLLISION`, `RESULT_CAPABILITY_INVALID`, `RESULT_CAPABILITY_EXPIRED` existent déjà.

### Harnais de test

- Base : `src/test/kotlin/io/whozoss/factory/Neo4jIntegrationTest.kt` (Neo4j in-process, pas de Docker,
  profil `embedded-neo4j`, `clearGraph()` en `@BeforeEach`, `factory.outbox.drain-enabled=false`).
- `Neo4jDomainIntegrationTest.kt` fournit `scope = TenantScope("org-local-dev","ws-default")`.
- `AgentStepResultServiceIntegrationTest` : setup via `attempts.insert(...)` + `service.issue(...)`,
  assertions `outcome.idempotent` (false=201/created, true=200/replay) et
  `ResultSemanticCollisionException`/`IdempotencyKeyCollisionException` pour les collisions.
- HTTP : `AgentStepResultControllerHttpTest.kt` — **`@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`**
  (pas de MockMvc, pas de `@WebMvcTest`). Le `TrustContext` n'est **pas** stubbé : il est résolu pour de
  vrai à la frontière HTTP ; via loopback + `server.forward-headers-strategy=framework` l'appelant est le
  principal **loopback-dev** → `org-local-dev` / `ws-default` (scope wildcard), ce qui aligne le `scope`
  des tests service avec le tenant résolu en HTTP. Envelope d'erreur rendue par `FactoryExceptionHandler`
  (`{ "error": { code, message, details } }`). Helpers : `Authorization: Bearer <token>`, headers
  `X-AgentOS-Case-Id` / `X-AgentOS-Agent-Name` / `X-Idempotency-Key`.

---

## Constats de reconnaissance qui fondent le plan

### C1 — Composition root : DÉJÀ un singleton partagé (étape 1 = surtout verrouillage par test)
Les quatre classes sont des stereotypes (`@Service`/`@Repository`) scannés une seule fois sous
`io.whozoss.factory` (`FactoryServiceApplication` : `@SpringBootApplication` + `@ConfigurationPropertiesScan`).
Aucun `@Bean`, aucune construction manuelle en source MAIN, aucun `@Scope`. Les contextes PF4J enfants
(`factory-forge-plugin/.../ForgePlugin.kt`) ont `parent = contexte racine` et ne scannent que
`io.whozoss.factory.forge` → ils **héritent** ces beans, ne les redupliquent pas. Stratégie de
classloading APD (`config/PluginConfiguration.kt`) → pas de double copie de classe.
**Nuance à corriger** : dans `CapabilityExecutionService`, la dépendance
`agentStepResultService: AgentStepResultService? = null` est **optionnelle** (nullable + défaut). En prod
elle est bien injectée, mais le `?: return null` dans `issueCapability(...)` masque silencieusement une
absence de câblage. De plus le chemin durable primaire (`resolveAgentViaAdapter`) **n'émet jamais** de
capability structurée (il passe `capabilityToken = null`), seule la voie legacy `resolveAgentViaPolling`
appelle `issueCapability`. ⇒ Étape 1 : rendre le partage explicite et testé (pas de duplication), et
documenter/garantir l'instance unique ; traiter l'injection optionnelle.

### C2 — Finalisation single-use & replay idempotent durable : logique présente, à durcir + tester restart
`Neo4jAgentStepResultRepository.submit(...)` gère déjà : première soumission → `SubmitOutcome.Created`
(201) ; replay même hash → `Replayed` (200) ; replay hash différent → `ResultSemanticCollisionException`
(409, `RESULT_SEMANTIC_COLLISION`). Tout est persisté en Neo4j (nœuds `AgentStepResult`,
`ResultCapability`, `OutboxEvent`, `IdempotencyRecord`) donc **survit à un restart**. **Manque** : aucun
test ne prouve la durabilité à travers un redémarrage. ATTENTION — le harnais in-process actuel
(`EmbeddedNeo4jTestConfiguration`, `newInProcessBuilder().withDisabledServer()`) utilise un répertoire
**temporaire éphémère** : ré-instancier un `Driver` contre le même `boltURI()` ne constitue PAS un vrai
redémarrage (même DBMS vivant). Une vraie preuve de restart nécessite une variante de harnais basée sur
`newInProcessBuilder(Path)` sur un `@TempDir`, fermée (`close()`) puis rouverte sur le **même répertoire**.
Également : le test de collision sémantique ne couvre pas exhaustivement le replay via `X-Idempotency-Key`
(même clé + payload identique vs divergent).

### C3 — Binding & fencing d'identité : partiel, à compléter
À la soumission (`Neo4jAgentStepResultRepository.submit`), seuls `attemptId`, `caseId`, `agentName`
sont comparés à la capability (via `AgentStepResultObservedIdentity`, qui ne porte que ces trois champs).
`namespaceId` / `workflowId` / `stepId` ne sont **pas** vérifiés explicitement — ils ne sont protégés
qu'indirectement par le `TenantScope` (organizationId, workstreamId) et par le fait que le lookup de
capability se fait par token hash scopé au tenant. Problème : `observed.attemptId/caseId/agentName`
proviennent du **corps de requête / headers HTTP** (model-controlled) dans `AgentStepResultController`,
pas du `TrustContext`. Le `TrustContext` porte pourtant `namespaceId` et `caseId`. ⇒ Étape 3 :
(a) étendre `AgentStepResultObservedIdentity` pour transporter `namespaceId` (dérivé du `TrustContext`),
(b) rejeter toute soumission dont `namespaceId` (trusted) ≠ `capability.namespaceId` avec
`RESULT_IDENTITY_MISMATCH`, (c) privilégier le `TrustContext.caseId`/`namespaceId` sur les valeurs du
corps quand ils sont présents.

### C4 — Séquencement d'écriture & Outbox : conforme, à verrouiller par test
`submit` écrit `result-submitted` + terminalise l'attempt + enfile `result_submitted` dans **une seule
transaction `@Transactional`** du repository ; aucune transition de workflow n'est émise dans cette
transaction. L'`OutboxDrainWorker` collecte la continuation *dans* la transaction de drain mais l'exécute
*après* commit (`sessionRunService.runSession` hors transaction). ⇒ Conforme à l'exigence « persister +
terminaliser AVANT toute transition » ; manque un test explicite (ordre d'écriture + outbox pending).

### C5 — Fencing des messages non structurés : TROU identifié
`VerdictDeriver.derive(...)`, branche `IDLE` sans question en attente : si le dernier `MessageEvent` agent
est non vide, il renvoie `AgentOsExecutionVerdict.Succeeded(outputs = {"summary": <texte libre>})`.
Ce verdict devient ensuite une evidence `agent-result` « pass » dans
`CapabilityExecutionService.finalizeAgentAttempt`. ⇒ **Un dernier message brut peut aujourd'hui servir de
résultat autoritatif de succès en l'absence de soumission structurée.** C'est la violation la plus nette
de l'invariant « pas de fin inférée du silence / pas de résultat depuis un message brut ». ⇒ Étape 5 :
exiger une **soumission structurée via la capability** pour qu'un step `agent` soit `SUCCEEDED` ; en
l'absence de résultat structuré soumis, un `IDLE` sans question ne doit pas être un `Succeeded` dérivé du
texte (→ `Indeterminate` / `Failed` explicite, code dédié).

### C6 — Réconciliation au démarrage : attempts couverts, capabilities expirées/soumises à renforcer
**DISTINCTION CLÉ (vérifiée)** : il existe DEUX agrégats attempt distincts, chacun avec son nœud et son
repository, jamais réconciliés l'un contre l'autre au démarrage :
- `DurableAgentAttempt` (`DurableAgentAttemptNode` / `Neo4jDurableAgentAttemptRepository`) — cycle de vie
  d'exécution SSE fencé par lease ; c'est le SEUL agrégat balayé par `BridgeRecoveryWorker`
  (`DurableAgentAttemptService.findNonTerminal`).
- `AgentStepAttempt` (`AgentStepAttemptNode` / `Neo4jAgentStepAttemptRepository`) — racine de l'agrégat
  AGENT-STEP *résultat* (porte les result rows + la capability) ; terminalisé par
  `Neo4jAgentStepResultRepository.submit(...)` via `terminalize`.
`BridgeRecoveryWorker` balaye au démarrage les `DurableAgentAttempt` **non terminaux** et les réconcilie
(reconcile REST, finalize sous lease fraîche, resume SSE, re-drive si jamais démarré) via
`@EventListener(ApplicationReadyEvent)` (une seule fois, pas de @Scheduled). Il ne traite **pas** la
cohérence du canal de résultat (`AgentStepAttempt` + `ResultCapability` + outbox) : capabilities
**expirées** encore « réservées » (status `collision_detected`, payload `capability-reserved`) dont
l'`AgentStepAttempt` est resté non terminal, ou capabilities déjà **soumises** (`result-submitted`
présent) dont l'`AgentStepAttempt` n'aurait pas été terminalisé (crash entre les deux écritures — même si
elles sont normalement dans la même transaction, prévoir la réconciliation défensive). ⇒ Étape 6 :
ajouter une passe de réconciliation du canal résultat au démarrage (expired/submitted) qui libère/met à
jour l'état des `AgentStepAttempt` correspondants, sans jamais fabriquer un succès.

---

## Travaux à réaliser (une étape = un ou plusieurs commits conventionnels focalisés)

> Ordre recommandé : **1 → 3 → 2 → 4 → 5 → 6** (fencing d'identité avant les tests de durabilité ;
> composition root d'abord car socle). Chaque étape doit laisser le build vert.

### Étape 1 — Composition Root / instance unique partagée
**But** : garantir et prouver qu'il n'existe **qu'une** instance partagée de `AgentStepResultService`,
`AgentStepResultRepository`/`Neo4jAgentStepResultRepository` et `IdempotencyRepository`/
`Neo4jIdempotencyRepository` entre le runner et les deux contrôleurs ; aucune ré-instanciation du store
ni de la logique métier.

Fichiers :
- `capability/CapabilityExecutionService.kt` : rendre `agentStepResultService` **non optionnel** pour la
  voie production. Option retenue : garder le paramètre `AgentStepResultService?` pour les tests unitaires
  purs **mais** ajouter un `init {}` ou un log d'avertissement unique si `null` en contexte Spring ;
  simplement, extraire `issueCapability(...)` pour qu'une absence de service soit un fait explicite
  (log `WARN` `RESULT_CAPABILITY_ISSUER_ABSENT`) plutôt qu'un `?: return null` muet. Ne pas changer la
  signature publique si cela casse les tests existants (`CapabilityExecutionIntegrationTest`,
  `SessionSequencerIntegrationTest`, etc. construisent le service à la main) ; conserver le défaut `null`.
- (Pas de nouveau `@Bean` ni de `@Configuration` : la DI par stéréotype suffit et est déjà correcte.)

Test (nouveau) : `src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultWiringTest.kt`
(étend `Neo4jIntegrationTest`) — autowire `AgentStepResultService`, `AgentStepResultRepository`,
`IdempotencyRepository`, `AgentStepResultController`, `FactoryStepResultBindingController`,
`CapabilityExecutionService`, et asserter via réflexion/`ApplicationContext.getBean(...)` l'**identité
d'instance** (`===`) du service et des repos partagés entre runner et contrôleurs. Vérifier aussi qu'il
n'y a qu'un seul bean de chaque type (`context.getBeanNamesForType(...)` taille 1).

Commit : `test(factory): assert single shared agent-step result wiring across runner and controllers`
(+ `refactor(factory): make capability issuer absence explicit in CapabilityExecutionService` si code touché).

### Étape 3 — Binding & fencing d'identité stricts
**But** : lier exactement, à l'émission et à la soumission, `attemptId`, `caseId`, `namespaceId`,
`workflowId`, `stepId`, `agent` ; rejeter toute soumission d'un autre case **ou** namespace que celui
pour lequel la capability a été émise → `RESULT_IDENTITY_MISMATCH`. Les identités de confiance
proviennent du `TrustContext`, pas du corps.

**Nature exacte du changement (vérifiée)** : aujourd'hui, dans `Neo4jAgentStepResultRepository.submit`,
`namespaceId`/`workflowId`/`stepId` sont pris **depuis la capability elle-même** (jamais déclarés par
l'appelant), donc aucun `RESULT_IDENTITY_MISMATCH` ne peut être levé sur ces champs ; seuls
`attemptId`/`caseId`/`agentName` **déclarés dans le corps/headers** sont comparés à la capability.
`TrustContext` porte pourtant un `namespaceId` et un `caseId` de confiance (claims JWT signés), non
utilisés par le contrôleur. Le correctif consiste donc à **ancrer** le fencing sur le `TrustContext`
(valeurs de confiance) plutôt que sur des champs rédigés par l'appelant : comparer le `namespaceId`
(et le `caseId` quand présents dans le `TrustContext`) à ceux de la capability et rejeter toute
divergence. NB : la branche loopback-dev lit `namespaceId`/`caseId` depuis des headers
(`x-factory-namespace-id`/`x-factory-case-id`) mais uniquement sur socket loopback + opt-in — acceptable.

Fichiers :
- `agentattempt/domain/AgentStepResultModels.kt` : étendre `AgentStepResultObservedIdentity` avec
  `namespaceId: String?` (et éventuellement `workflowId`/`stepId` si disponibles de façon fiable).
- `agentattempt/persistence/Neo4jAgentStepResultRepository.kt` (`submit`) : après le lookup par token,
  comparer `observed.namespaceId` à `capability.namespaceId` (quand fourni) et lever
  `ResultIdentityMismatchException` sur divergence, en plus des comparaisons `attemptId/caseId/agentName`
  existantes. Documenter que `TenantScope` fence déjà `(organizationId, workstreamId)` et que le token
  hash est scopé au tenant (une soumission avec `caseId` identique mais tenant différent ne résout pas la
  capability → `RESULT_CAPABILITY_INVALID`).
- `agentattempt/web/AgentStepResultController.kt` : dériver `namespaceId` (et `caseId` si présent) depuis
  le `TrustContext` (champ `trustContext.namespaceId` / `trustContext.caseId`) et le passer dans
  `AgentStepResultObservedIdentity`. **Préférer** la valeur du `TrustContext` à celle du corps quand les
  deux sont présentes ; si elles divergent, rejeter (`RESULT_IDENTITY_MISMATCH`). Conserver la rétro-compat
  des deux formes de corps.
- `agentattempt/service/AgentStepResultService.kt` : propager `namespaceId` dans le hash de requête
  (`hashRequest`) pour que l'idempotence couvre le namespace observé.

Tests :
- Étendre `AgentStepResultServiceIntegrationTest` : soumission avec `namespaceId` ≠ capability →
  `ResultIdentityMismatchException`. Soumission `caseId` ≠ → déjà couvert, garder.
- Étendre `AgentStepResultControllerHttpTest` : `TrustContext.namespaceId` divergent du corps → 400
  `RESULT_IDENTITY_MISMATCH` ; `TrustContext.namespaceId` cohérent → 201.

Commit : `feat(factory): fence agent step result submissions by trusted namespace identity`.

### Étape 2 — Finalisation single-use avec replay idempotent **durable** (preuve de restart)
**But** : prouver et durcir le comportement 201/200/409 et la survie à un restart.

Fichiers (durcissement seulement si un test révèle un manque) :
- `agentattempt/persistence/Neo4jAgentStepResultRepository.kt` / `service/AgentStepResultService.kt` :
  vérifier que le replay identique (même payload + même attempt/case/namespace) renvoie `Replayed` (200)
  et que le replay payload divergent lève `ResultSemanticCollisionException` (409) **même après restart**
  (l'état `result-submitted` est relu depuis le nœud `AgentStepResult`, pas d'état en mémoire — OK).

Tests :
- Nouveau test de durabilité `src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultDurabilityTest.kt`.
  **Choisir une des deux approches** (la 1 est obligatoire ; la 2 est la vraie preuve de restart, à viser) :
  1. *Relecture server-side (léger)* : soumettre un résultat, puis obtenir une **nouvelle** instance de
     `Driver`/repository contre le même `harness.boltURI()` et relire via `findFirstByAttempt(...)` /
     `findAllByOrganization(...)` → prouve que la donnée est côté serveur (pas en cache de session). Prouver
     ensuite (a) replay identique idempotent (200), (b) replay divergent `RESULT_SEMANTIC_COLLISION` (409),
     (c) attempt terminal et immuable. NB : ce n'est PAS un vrai restart (harnais éphémère), le documenter.
  2. *Vrai restart (ciblé)* : ajouter une **variante de harnais test-only** basée sur
     `Neo4jBuilders.newInProcessBuilder(path)` sur un `@TempDir`, écrire le résultat, `close()` le harnais,
     en rouvrir un second sur le **même répertoire**, rouvrir un `Driver`, et asserter que les nœuds
     `:AgentStepResult` / `:OutboxEvent` / `:ResultCapability` / `:IdempotencyRecord` sont toujours présents
     et que les sémantiques 200/409 tiennent après réouverture. Modeler sur `EmbeddedNeo4jTestConfiguration`.
  Dans les deux cas, **ne pas** utiliser `clearGraph()` au milieu du scénario (override `@BeforeEach` si besoin).
- Compléter `AgentStepResultServiceIntegrationTest` : matrice replay via `X-Idempotency-Key`
  (même clé + même payload → 200 ; même clé + payload divergent → `IdempotencyKeyCollisionException` 409).

Commit : `test(factory): prove single-use result submission survives restart with idempotent replay`.

### Étape 4 — Séquencement d'écriture & Outbox (verrou par test)
**But** : prouver que `result-submitted` est persisté et l'attempt terminalisé **dans la transaction
autoritative Neo4j AVANT** toute transition de workflow, et que l'avancement du DAG est porté par l'outbox.

Fichiers : aucun changement de comportement attendu (déjà conforme). Si un test révèle que
`insertOutbox` ou `terminalize` peut s'exécuter hors de la transaction de `submit`, corriger pour tout
inclure dans le `@Transactional` du repository.

Tests :
- Étendre `AgentStepResultServiceIntegrationTest` / nouveau test ciblé : après `submit`, asserter en une
  seule lecture que (a) le nœud `AgentStepResult` est `result-submitted`, (b) l'attempt est terminal,
  (c) un `OutboxEvent` `result_submitted` est `pending` — tout cela sans qu'aucune transition de workflow
  n'ait été appliquée (le drain est désactivé en test). 
- Vérifier via `OutboxDrainServiceIntegrationTest` existant que le drain avance ensuite le DAG après
  commit (déjà couvert ; ajouter une assertion d'ordre si pertinent).

Commit : `test(factory): assert result-submitted and attempt terminalization commit before any workflow transition`.

### Étape 5 — Fencing des messages non structurés (correctif de sécurité)
**But** : garantir qu'un dernier `MessageEvent` / texte libre ne peut **jamais** être un résultat
autoritatif de succès en l'absence de soumission structurée via la capability.

Fichiers :
- `adapter/agentos/VerdictDeriver.kt` : dans la branche `IDLE` sans question en attente, **ne plus**
  dériver `Succeeded` à partir de `lastAgentMessage(...)`. Remplacer par un verdict non autoritatif :
  retourner `AgentOsExecutionVerdict.Indeterminate(IDLE_WITHOUT_STRUCTURED_RESULT, ...)` (ou un `Failed`
  avec un code dédié, p. ex. `AGENT_NO_STRUCTURED_RESULT`), de sorte que l'autorité du succès vienne
  exclusivement de la soumission structurée (`result-submitted`) via la capability. Mettre à jour la
  doc du fichier (le commentaire décrit explicitement le comportement actuel à changer) et renommer/ajouter
  la constante de raison. **Attention** : conserver `WaitingHuman` (question non répondue) et les verdicts
  `ERROR`/`KILLED` tels quels ; ne toucher que le cas `IDLE` sans question.
- Vérifier le point de jonction : `CapabilityExecutionService.finalizeAgentAttempt` persiste l'evidence
  `agent-result` « pass » uniquement sur `Succeeded`. Confirmer qu'après le changement, un step agent sans
  résultat structuré soumis **n'est pas** `SUCCEEDED`. Documenter dans le code que le succès autoritatif
  d'un step agent provient du canal `agent-step-results` (capability), le verdict SSE ne servant qu'à
  l'observation du cycle de vie et aux échecs explicites.
- Inspecter `HttpAgentOsProxyClient` (voie polling legacy) : le commentaire de `VerdictDeriver` note qu'il
  « treats an IDLE with no question as success even with an empty summary ». Aligner/documenter : la voie
  legacy ne doit pas non plus fabriquer un succès par silence. Si modification nécessaire, la faire dans
  un commit séparé et borné.

Tests :
- Étendre les tests de `VerdictDeriver` (chercher `VerdictDeriverTest`/équivalent sous
  `src/test/kotlin/io/whozoss/factory/adapter/agentos/`; en créer un si absent) : `IDLE` + question non
  répondue → `WaitingHuman` ; `IDLE` + message libre non vide, sans question → **plus** `Succeeded` mais
  `Indeterminate`/`Failed` (code dédié) ; `ERROR`/`KILLED` inchangés.
- Si pertinent, un test de `CapabilityExecutionService` prouvant qu'un turn qui atteint IDLE avec seulement
  un message libre ne produit pas d'evidence `agent-result` « pass ».

Commit : `fix(factory): never derive an authoritative success from a free-text agent message`.

### Étape 6 — Réconciliation au démarrage (capabilities expirées / déjà soumises)
**But** : au démarrage, réconcilier les capabilities **expirées** ou **déjà soumises** et
libérer/mettre à jour l'état des attempts correspondants, sans jamais fabriquer de succès.

Fichiers :
- `agentattempt/service/BridgeRecoveryWorker.kt` **ou** nouvelle méthode dans
  `agentattempt/service/AgentStepResultService.kt` (préférer une méthode `reconcileOnStartup(...)` dans
  le service du canal résultat, appelée depuis un `@EventListener(ApplicationReadyEvent)` dédié ou depuis
  `BridgeRecoveryWorker`). Logique :
  - Pour chaque capability dont un **résultat `result-submitted` existe** mais dont l'attempt n'est pas
    terminal : terminaliser l'attempt dans l'état cohérent avec le `status` du résultat
    (`PASS→completed`, `FAIL→failed`), sous les mêmes garanties de fence/immutabilité.
  - Pour chaque capability **expirée** (now > `expiresAt`) encore en réservation sans résultat soumis :
    laisser l'attempt être traité par la réconciliation d'attempt existante (ne pas inventer de succès) ;
    marquer/documenter l'expiration (pas de nouvelle soumission possible). Ne pas supprimer de données.
- Ports repository : ajouter au besoin dans `agentattempt/persistence/AgentStepResultRepository.kt` +
  `Neo4jAgentStepResultRepository.kt` une requête de lecture « capabilities soumises dont l'attempt n'est
  pas terminal » et « capabilities expirées sans résultat », via de nouvelles méthodes `@Query` sur
  `SpringDataNeo4jResultCapabilityRepository` / `SpringDataNeo4jAgentStepResultRepository`
  (lectures seules, pas de migration de schéma). Scoper toutes les requêtes par tenant.

Tests :
- Nouveau `src/test/kotlin/io/whozoss/factory/agentattempt/ResultChannelRecoveryTest.kt` (étend
  `Neo4jDomainIntegrationTest`) : (a) résultat soumis + attempt non terminal → après `reconcileOnStartup`,
  attempt terminal cohérent ; (b) capability expirée sans résultat → attempt non « réussi », état
  cohérent ; (c) idempotence de la passe (rejouer ne change rien).

Commit : `feat(factory): reconcile submitted and expired result capabilities on startup`.

---

## Découpage des commits (récapitulatif)

1. `test(factory): assert single shared agent-step result wiring across runner and controllers`
   (+ éventuel `refactor(factory): make capability issuer absence explicit ...`).
2. `feat(factory): fence agent step result submissions by trusted namespace identity`.
3. `test(factory): prove single-use result submission survives restart with idempotent replay`.
4. `test(factory): assert result-submitted and terminalization commit before any workflow transition`.
5. `fix(factory): never derive an authoritative success from a free-text agent message`.
6. `feat(factory): reconcile submitted and expired result capabilities on startup`.

(Chaque commit doit laisser `./gradlew test` vert pour `factory-service`.)

---

## Pièges & notes pour le builder

- **Ne pas** introduire de nouveau `@Bean`/`@Configuration` pour les repos/service : la DI par stéréotype
  est déjà correcte et unique (vérifié). L'étape 1 est surtout un **verrou par test** + clarification du
  chemin issuer optionnel ; ne pas casser les constructeurs utilisés par les tests unitaires existants
  (`CapabilityExecutionIntegrationTest`, `SessionSequencerIntegrationTest`,
  `DurableAgentOsBridgeIntegrationTest`, `SessionDefinitionImportIntegrationTest`,
  `CapabilityExecutionCapabilityIssuanceTest`, `TransactionBoundaryIntegrationTest`).
- **Codes d'erreur** : réutiliser les codes stables existants (`RESULT_IDENTITY_MISMATCH`,
  `RESULT_SEMANTIC_COLLISION`, `RESULT_CAPABILITY_EXPIRED`, `RESULT_CAPABILITY_INVALID`,
  `IDEMPOTENCY_KEY_COLLISION`). N'ajouter un nouveau code (p. ex. `AGENT_NO_STRUCTURED_RESULT`) que si
  nécessaire, et l'ajouter à `AgentAttemptErrorCodes` + table de `app_docs/..._contracts.md` (section 7).
- **Immutabilité terminale** : toute écriture dans `submit`/réconciliation doit respecter qu'un attempt
  terminal ne peut pas être muté (voir garde existante `payloadType(existing.payload) == SUBMITTED_TYPE`).
- **Tenant scoping** : toute nouvelle requête `@Query` doit filtrer par `organizationId` + `workstreamId`.
- **Pas de fin inférée du silence** : l'étape 5 est la plus sensible ; bien distinguer « observation du
  cycle de vie » (SSE/verdict) de « résultat autoritatif » (soumission structurée via capability).
- **Scratch** : tout fichier temporaire va dans `/tmp`, jamais dans l'arbre repo.
- **Hors périmètre** : migrations Neo4j, `Neo4jSchemaInitializer` (sauf index/contrainte strictement
  nécessaire et documenté), pipelines de release, Workstream Agent lui-même.
- **Tests** : harnais in-process (pas de Docker). Nommage `*Test.kt` sous
  `factory-service/src/test/kotlin/io/whozoss/factory/...`. Lancer `pnpm nx test factory-service` en local
  si besoin de debug (l'usine lance la suite affected automatiquement).
