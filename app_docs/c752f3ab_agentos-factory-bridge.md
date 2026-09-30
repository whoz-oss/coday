# Bridge AgentOS–Factory durable

## Ce qui a changé

Le `factory-service` orchestre désormais les étapes `agent` via les briques existantes `DurableAgentAttemptService` et `AgentOsExecutionAdapter` lorsqu’elles sont injectées. Le chemin historique de polling reste utilisé si l’adapter ou le service de tentatives durables est absent.

Une exécution agent est découpée en phases :

1. réservation/claim de l’`attemptId` déterministe (`workflowId#stepId`) et du `caseId` déterministe (`case:<workflowId>#<stepId>`), avec bail et fencing ;
2. création ou récupération du case, démarrage idempotent du tour, puis observation/réconciliation AgentOS hors transaction Neo4j ;
3. finalisation dans une transaction courte, avec persistance d’une évidence `agent-result` et des sorties structurées du verdict.

Les tentatives terminales sont rejouées depuis leur état et leur évidence sans renvoyer de tour. Une tentative non terminale récupère son case et évite également un second `startTurn` si le démarrage avait déjà été marqué. Les erreurs de dispatch ou d’observation passent par `reconcile`, ou produisent un verdict `Indeterminate` si la réconciliation échoue.

Les briefs des étapes dépendantes sont construits par Factory à partir des sorties `outputs` des évidences `agent-result` réussies des dépendances, et non à partir d’un dernier message libre. La progression du DAG conserve ainsi les règles : une étape dépendante ne démarre qu’après la réussite durable de toutes ses dépendances ; un échec ou une attente humaine bloque/suspend la suite.

## HTTP `/run` et `/continue`

`WorkflowController` accepte désormais `sync=false` par défaut. Il écrit une soumission `session_run_requested` dans l’outbox transactionnelle et répond rapidement avec HTTP `202 Accepted`, le workflow, le namespace, l’opération et un `submissionId` de suivi. Le worker d’outbox recharge le repo root et le ticket depuis l’événement, puis appelle `SessionRunService` après commit ; une soumission non drainée reste donc récupérable après redémarrage. `sync=true` conserve l’exécution synchrone explicite.

`OutboxDrainWorker` sait aussi traiter ces événements de soumission, en plus des événements `result_submitted`. `SessionRunService` ajoute un journal d’erreur lorsqu’une étape échoue.

## Fichiers concernés

- `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt` : sélection adapter/polling, claim durable, exécution hors transaction, verdicts, évidence `agent-result`, finalisation et construction des briefs dépendants.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt` : réponses asynchrones `202` et option `sync=true` pour `/run` et `/continue`.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunSubmissionService.kt` : création transactionnelle des soumissions d’outbox.
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/OutboxDrainWorker.kt` : décodage et drainage des soumissions durables.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt` : journalisation des échecs d’étape.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/DurableAgentOsBridgeIntegrationTest.kt` : couverture des dépendances A→B, échecs, attente humaine, concurrence, récupération de case, idempotence après acceptation du message et absence de transaction pendant un tour long.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowRunAsyncHttpTest.kt` : vérification du `202`, de l’identité de suivi, de l’outbox et du drainage.
- `specs/c752f3ab_agentos_factory_bridge_orchestration.md` : spécification du découpage et des critères d’acceptation.

## Vérification

Depuis le dépôt, lancer la suite Factory avec `pnpm nx test factory-service`. Pour cibler le bridge :

```bash
./gradlew :factory-service:test --tests "io.whozoss.factory.workflow.DurableAgentOsBridgeIntegrationTest"
```

Les tests HTTP couvrent la soumission durable et son drainage manuel via `OutboxDrainWorker` ; les tests du bridge utilisent un faux `AgentOsExecutionAdapter` avec les vrais services et repositories Spring.
