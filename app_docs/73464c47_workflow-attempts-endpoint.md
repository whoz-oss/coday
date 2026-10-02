# Endpoint des tentatives de workflow

## Ce qui a changé

`factory-service` expose désormais `GET /api/factory/workflows/{workflowId}/attempts` pour le Cockpit V2. Le contrôleur résout le scope et le namespace avec le même mécanisme que les autres lectures (`resolveWorkflowCaller`), accepte `namespaceId` en query parameter ainsi que le `TrustContext` interne, puis appelle `DurableAgentAttemptService.findByWorkflow(...)`.

La réponse est une `WorkflowDataEnvelope` contenant directement une liste sous `data`. Une recherche sans tentative renvoie donc HTTP 200 avec `{ "data": [] }`.

## Modèle exposé et sécurité

`DurableAgentAttemptDto` est un modèle de lecture borné situé dans `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttemptDto.kt`. Il expose exactement :

- `attemptId`, `stepId`, `attemptNumber`, `agentName`, `status`, `caseId`
- `failureCode`, `resultEvidenceId`, `revision`
- `createdAt`, `startedAt`, `completedAt`

Le statut utilise sa valeur persistée stable (`dbValue`). Les secrets et données d’exécution internes — notamment `ownerToken`, `capabilityToken`, `commandId`, `brief`, `leaseExpiresAt`, `lastObservedEventId` et `turnCorrelation` — sont exclus du DTO et ne sont donc pas sérialisés.

## Fichiers concernés

- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt` : injection du service et route GET `/attempts` avec annotation OpenAPI.
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttemptDto.kt` : DTO public et mapping depuis `DurableAgentAttempt`.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowControllerHttpTest.kt` : tests HTTP avec plusieurs tentatives, cas vide et vérification de non-exposition des champs sensibles.
- `specs/73464c47_expose_workflow_attempts_endpoint.md` : spécification et plan de vérification associés.

## Utilisation et vérification

Exemple de requête :

```text
GET /api/factory/workflows/{workflowId}/attempts?namespaceId={namespaceId}
```

La vérification ciblée indiquée par le changement se lance depuis `factory-service` :

```bash
./gradlew test --tests "io.whozoss.factory.workflow.WorkflowControllerHttpTest"
```

Les assertions HTTP vérifient le statut 200, la liste des deux tentatives, les valeurs de statut et l’absence des noms et valeurs de champs sensibles dans le JSON.
