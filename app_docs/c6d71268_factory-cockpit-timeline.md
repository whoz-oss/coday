# Cockpit Factory : runs, timeline et namespace optionnel

## Résumé

Le cockpit vanilla présente désormais la liste des workflows comme écran d’accueil (`#/runs`). L’ancien onglet Projection n’est plus une vue distincte : `#/projection` reste un alias vers la liste, tandis que les onglets Lancer, Forge et Admin sont conservés. Un clic sur une carte de run ouvre `#/detail?workflowId=…` et affiche sa timeline, dont les swimlanes HUMAIN / AGENT / CODE réutilisent le layout temporel existant.

Le namespace est traité comme optionnel pour ce parcours. Sans namespace, le front n’envoie plus `namespaceId=` vide ; le flux SSE utilise également l’URL sans query string. Le backend accepte l’absence de namespace pour le stream et les réponses de liste portent le `namespaceId` de chaque ligne, ce qui permet de réouvrir ensuite le détail dans le bon scope.

## Où se trouve le changement

### Navigation et parcours utilisateur

- `factory/dashboard/cockpit.html` réduit la navigation à Runs, Lancer, Forge et Admin. La section Runs affiche un état de chargement et la section Détail explique comment y accéder depuis la liste.
- `factory/dashboard/js/app.mjs` définit `/runs` comme route par défaut, conserve `/projection` comme alias de compatibilité et monte `mountProjectionView` dans `#view-runs`. Il lit `workflowId` depuis la route détail, transmet le namespace éventuel et monte `factory/dashboard/js/views/run-detail.mjs`.
- `factory/dashboard/js/views/projection.mjs` rend les cartes cliquables (hors liens et boutons d’action) et navigue vers le détail en transmettant le namespace de la carte lorsqu’il existe. Les chemins de liste, détail, actions, timing et SSE omettent `namespaceId` quand il est absent.
- `factory/dashboard/js/components/workflow-card.mjs` ajoute le namespace aux attributs de la carte (`data-namespace-id`) et conserve l’affichage des trois swimlanes temporelles dans la liste.
- `factory/dashboard/js/views/run-detail.mjs` accepte désormais un namespace absent et construit les requêtes sans paramètre vide. Le détail ajoute un panneau Timeline rendu via `buildBlueprintLayout` et `renderTemporalLanes`, avant le Gantt et les panneaux existants.
- `factory/dashboard/css/dockyard.css` indique que toute la carte est une cible de clic, tout en gardant le curseur approprié pour les liens et boutons imbriqués.

### Backend et couverture HTTP

- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/sse/WorkflowSseController.kt` résout le caller avec `requireNamespace = false`, afin qu’un stream sans namespace puisse s’enregistrer sur le scope global.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt` inclut `namespaceId` dans les snapshots publics de projections et d’instances. Une liste scope-wide peut ainsi router chaque workflow vers son détail namespace-scoped.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowControllerHttpTest.kt` vérifie le GET de liste sans namespace sous le contexte loopback : statut 200, absence d’erreur et présence de workflows de deux namespaces. Le cas avec namespace fourni vérifie que le filtrage reste limité au namespace demandé.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowSseHttpTest.kt` couvre l’ouverture réelle du stream HTTP sans `namespaceId`, puis la réception d’un événement SSE nommé ; le test existant avec namespace explicite reste présent.
- `factory-service/src/test/kotlin/io/whozoss/factory/web/CockpitStaticServingIntegrationTest.kt` adapte les assertions au shell statique actuel (`<title>Factory</title>` et `cockpit-topbar`).

Les tests front correspondants dans `factory/tests/test-cockpit-run-detail.mjs` vérifient notamment qu’un montage de détail sans namespace réussit et que les requêtes peuvent donc rester sans query parameter.

## Vérification

Tests backend complets, avec Postgres Testcontainers :

```bash
cd factory-service
./gradlew clean test
```

Vérification manuelle du parcours :

1. Démarrer `factory-service` sur le port 8141.
2. Ouvrir `http://127.0.0.1:8141/`.
3. Vérifier que l’écran initial affiche la liste des runs et que `test-run-1` apparaît sans erreur `INVALID_NAMESPACE_ID`.
4. Cliquer sur `test-run-1`.
5. Vérifier l’arrivée sur le détail et l’affichage de la timeline swimlanes HUMAIN / AGENT / CODE, avec les étapes, statuts et durées disponibles.

La suite front ciblée est également dans `factory/tests/test-cockpit-run-detail.mjs` ; les tests SSE et HTTP listés ci-dessus sont les contrôles de non-régression du parcours sans namespace.
