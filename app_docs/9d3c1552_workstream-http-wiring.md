# Phase 11 — câblage HTTP réel du cockpit Workstream

## Ce qui a changé

Le cockpit Workstream Angular n’est plus branché directement sur `WorkstreamMockService`. Le nouveau `FactoryWorkstreamService` appelle par défaut les endpoints same-origin `/api/factory/**`, désenveloppe les réponses Factory `{ data: ... }`, normalise les erreurs `{ error: ... }` et conserve la fraîcheur observée (`revision`, `ETag`, date de synchronisation). Le mock reste disponible explicitement pour le développement et les tests (`useMock` vaut `false` par défaut, avec `setUseMock()` ou `configure()`).

Les lectures et commandes couvertes sont les suivantes : workstream, projection agrégée, liste et détail des workflows, tentatives d’étapes, actions autorisées/blocages, interactions humaines, propositions de changement de plan et historique de controller-case. Les trois commandes du cockpit sont envoyées au backend : demande de retry, réponse à une interaction et décision de plan-change, avec `expectedRevision` lorsque disponible. Les lectures d’interactions, de propositions et d’historique restent best-effort ; une réponse indisponible est ramenée à une collection/historique vide pour ne pas faire tomber la vue.

Le mapping défensif accepte les DTO enveloppés ou bare et plusieurs formes de listes/actions. Les actions de l’interface ne sont plus déduites d’une règle locale seule : les boutons Retry et Reply sont activés uniquement si l’action correspondante figure dans `allowedActions`, et transmettent sa révision attendue. Le navigateur ne lance aucun worker.

## Intégration UI et états affichés

`WorkstreamCockpitComponent` passe par `FactoryWorkstreamService`, précharge les détails/actions nécessaires au résumé, sélectionne le premier workflow disponible et relit le workflow après une commande. Il expose désormais les signaux de chargement et d’erreur, affiche une alerte HTTP, indique `live` ou `mock data`, ainsi que la révision et la date `as of`. Le résumé affiche aussi un état vide lorsqu’aucun workflow n’est présent.

Les vues enfant reçoivent les actions autorisées et les intentions de commande portent les informations de workflow/révision nécessaires. Les badges distinguent notamment `waiting_human` (warning), `blocked`/`failed` (error), `indeterminate` (neutral), `completed` (success) et les états retirés `archived`, `runtime-closed`, `removed` ou `purged` (muted). Le détail de workflow applique également cette classification à son état de projection.

## Fichiers porteurs

- `apps/client/src/app/core/services/factory-workstream.service.ts` : client HTTP, bascule mock, enveloppes/erreurs, fraîcheur, mapping DTO et endpoints de commande.
- `apps/client/src/app/core/services/factory-workstream.service.spec.ts` : tests HTTP Angular des URLs, paramètres, corps POST, enveloppes, fraîcheur, erreurs, fallback historique et mode mock.
- `apps/client/src/app/core/services/workstream-mock.service.ts` : ajoute une réponse mock `allowedActions` cohérente avec les interactions et tentatives des fixtures.
- `apps/client/src/app/core/models/workstream.model.ts` : enveloppes Factory, actions autorisées, réponses d’actions/projection et accusés de commande ; états de workflow Phase 10.
- `apps/client/src/app/components/workstream-cockpit/workstream-cockpit.component.ts` et `.html`/`.scss` : orchestration live/mock, chargement, erreurs, fraîcheur et raccordement des actions.
- `apps/client/src/app/components/workstream-cockpit/workstream-cockpit.component.spec.ts` : couverture du mode mock et des scénarios HTTP live, dont erreur de lecture et rendu d’un workflow réel.
- `apps/client/src/app/components/workstream-cockpit/workstream-badges.ts` : classification des statuts en classes de badge.
- `apps/client/src/app/components/workstream-cockpit/human-interactions/human-interactions.component.ts`, `.html`, `.scss` : Reply conditionné par `allowedActions` et indication d’une réponse non autorisée.
- `apps/client/src/app/components/workstream-cockpit/step-attempts/step-attempts.component.ts`, `.html` : Retry conditionné par l’action backend et le statut de tentative.
- `apps/client/src/app/components/workstream-cockpit/plan-changes/plan-changes.component.ts` : propagation de `workflowId` et `expectedRevision` dans une décision.
- `apps/client/src/app/components/workstream-cockpit/summary-view/workstream-summary.component.html`, `.scss` : état vide et style muted.
- `apps/client/src/app/components/workstream-cockpit/workflow-detail/workflow-detail.component.html`, `.scss` : badges de l’état et du statut, incluant les états muted.
- `specs/9d3c1552_workstream_cockpit_real_http_wiring.md` : matrice des endpoints et description de la mise en œuvre Phase 11.

## Utilisation et vérification

Par défaut, injecter `FactoryWorkstreamService` suffit : les appels partent vers `/api/factory`. Pour un scénario dev/test sans HTTP, appeler `service.setUseMock(true)` ; `service.configure({ baseUrl, useMock })` permet également de définir la base et le mode. Le cockpit affiche alors le marqueur `mock data`; en mode normal il affiche `live`.

Vérifier la couverture front avec :

```bash
pnpm nx test client
pnpm nx lint client
pnpm nx build client
```

Les tests ajoutés utilisent `provideHttpClientTesting` et vérifient notamment le désenveloppement `{ data }`, la capture `ETag`/révision, les routes `/api/factory/**`, les corps et paramètres des commandes, la normalisation d’une erreur Factory et l’absence de requête HTTP en mode mock.

La méthode `getStepLanes()` reste explicitement alimentée par le mock, car le diff indique qu’il n’existe pas encore de source Factory réelle pour cette métadonnée d’affichage.
