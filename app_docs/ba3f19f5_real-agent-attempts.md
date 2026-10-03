# Tentatives d’exécution réelles dans le cockpit v2

## Résumé

Le cockpit v2 récupère désormais les tentatives d’exécution agent via l’endpoint existant `GET /api/factory/workflows/:id/attempts` et les rattache à la session et à l’étape active. Le compteur d’étape n’utilise plus la valeur trompeuse `0/0` : il affiche, lorsque les données sont disponibles, la tentative courante sur le nombre de tentatives du step (par exemple `2/2`), et retombe sur `1/1` lorsqu’aucune tentative n’est exposée.

Aucun changement backend Kotlin n’est inclus.

## Où se trouve le changement

- `apps/cockpit-v2/src/app/core/models.ts` définit `AgentAttempt`, ajoute les tentatives structurées à `PhaseDetail` et `SessionDetail`, et expose sur la phase les informations de la tentative courante (`agentName`, statut, `caseId`, et éventuellement `failureCode`).
- `apps/cockpit-v2/src/app/core/factory-api.service.ts` ajoute `getAttempts(workflowId, namespaceId?)`. La méthode encode l’identifiant du workflow, transmet le namespace lorsqu’il est fourni et normalise les réponses en tableau. Elle accepte les tableaux bruts ainsi que les enveloppes déjà déballées `{ items: [...] }` ou `{ data: [...] }`.
- `apps/cockpit-v2/src/app/core/mappers.ts` exporte `extractAttempts`, qui mappe défensivement les champs du DTO et fournit des valeurs de repli pour les enregistrements incomplets. `buildPhaseDetail` sélectionne le step actif (ou le dernier), filtre ses tentatives par `stepId`, choisit celle au plus grand `attemptNumber` et renseigne le compteur et les métadonnées de la tentative courante. `mapProjectionToSessionDetail` transmet les tentatives déballées à la phase et à la session.
- `apps/cockpit-v2/src/app/core/factory.store.ts` lance l’enrichissement des tentatives en parallèle des autres enrichissements. Une erreur de cet appel est ignorée : la projection de session et les autres données restent disponibles.
- `apps/cockpit-v2/src/app/core/mock-data.ts` remplace le compteur de la session mockée par `1/1`.
- Les tests de `factory-api.service.spec.ts`, `mappers.spec.ts` et `factory.store.spec.ts` couvrent le déballage API, le mapping défensif, plusieurs tentatives sur un step, l’absence de tentative et l’échec silencieux de l’enrichissement.
- `specs/ba3f19f5_cockpit_v2_real_agent_attempts.md` conserve le plan d’implémentation et le plan de vérification de cette évolution.

## Fonctionnement à retenir

Les tentatives sont conservées dans `SessionDetail.attempts`. Pour l’étape active, seules les tentatives dont `stepId` correspond à l’identifiant ou à la clé du step sont exposées dans `PhaseDetail.attempts`. La tentative ayant le plus grand numéro fournit le statut, l’agent, le `caseId` et le code d’échec éventuel affichés sur la phase.

Le store appelle `getAttempts` avec le namespace de la session lorsqu’il en connaît un. En cas d’erreur HTTP, l’observable d’enrichissement ne fait pas échouer la session ; la phase conserve son compteur neutre `1/1` et `SessionDetail.attempts` reste vide dans la projection construite sans données.

## Vérification

Les tests unitaires concernés peuvent être exécutés avec :

```bash
pnpm nx test cockpit-v2
```

Les contrôles prévus par le plan sont :

```bash
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```

Les tests ajoutés vérifient notamment le namespace et l’encodage de l’URL côté API, les enveloppes de réponse, le compteur dynamique (`2/2`), l’absence de `0/0` et la dégradation gracieuse lorsque l’endpoint des tentatives échoue.
