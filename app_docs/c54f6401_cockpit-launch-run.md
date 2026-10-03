# Lancement de runs dans Cockpit V2

## Ce qui change

Cockpit V2 dispose désormais d’un écran `/lancer` qui déclenche un workflow réel via `factory-service`. La soumission enchaîne :

1. `POST /api/factory/workflows/{workflowId}/start` pour matérialiser l’instance ;
2. `POST /api/factory/workflows/{workflowId}/run` pour lancer l’exécution durable, y compris la réponse HTTP 202 Accepted.

Un conflit d’identité ou d’existence (`WORKFLOW_IDENTITY_CONFLICT`, `WORKFLOW_ALREADY_EXISTS`, ou HTTP 409) lors de `start` est traité comme une instance déjà matérialisée : le client continue vers `run`. Les autres erreurs restent visibles dans une bannière et empêchent toute navigation ou faux succès.

## Où se trouve l’implémentation

- `apps/cockpit-v2/src/app/core/factory-api.service.ts` ajoute les contrats `StartWorkflowRequest`, `RunWorkflowRequest`, `RunWorkflowResponse` et `NamespaceItem`, ainsi que `startWorkflow`, `runWorkflow` et `getNamespaces`. Les appels réutilisent les mécanismes existants de corrélation, de namespace (query param et `X-Namespace-Id`), d’encodage d’URL et de normalisation des erreurs. La liste des namespaces accepte les réponses tableau ou `{ items }` et retombe sur `[]` en cas d’erreur.
- `apps/cockpit-v2/src/app/features/launch/launch-page.component.ts`, `.html` et `.scss` implémentent le formulaire standalone Material/Reactive Forms. Il charge les définitions et namespaces, déduplique les `workflowType`, permet une saisie libre lorsque les listes sont vides, et valide le namespace ainsi que `controllerRequest` (1 à 4000 caractères). Le payload fixe l’exécution sur `factory-dashboard`, `agentos` et `factory-agent`, et génère un identifiant `wf-<timestamp>-<suffixe>`.
- Après un lancement accepté, l’écran affiche `Lancement accepté` avec le `submissionId`, appelle `FactoryStore.refresh()` et navigue vers `/sessions/{workflowId}`. Le fil d’Ariane est positionné sur `Sandboxes > Lancer un run`.
- `apps/cockpit-v2/src/app/app.routes.ts` expose la nouvelle route lazy `/lancer`, sans retirer les routes existantes.
- `apps/cockpit-v2/src/app/core/factory.store.ts` rend `refresh()` public afin de recharger les projections de workflows actifs.
- `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.ts`, `.html` et `.scss` remplacent l’ancien formulaire informatif de création de sandbox par des liens explicites « Lancer un run » dans l’en-tête et le panneau dédié.

## Vérification

Les tests ajoutés couvrent les headers/query params et payloads de `startWorkflow` et `runWorkflow`, le déballage des namespaces et son fallback `[]`, ainsi que le formulaire : chargement initial, validation, conflit de matérialisation, succès avec refresh/navigation et erreurs backend sans navigation.

Fichiers concernés : `apps/cockpit-v2/src/app/core/factory-api.service.spec.ts`, `apps/cockpit-v2/src/app/features/launch/launch-page.component.spec.ts` et `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.spec.ts`.

Pour vérifier localement :

```bash
pnpm nx test cockpit-v2
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```

Le document de spécification associé est `specs/c54f6401_cockpit_v2_launch_workflow_run.md`.
