# Dé-mockage de l’écran Sandboxes du cockpit v2

## Résumé

L’écran Sandboxes de `apps/cockpit-v2` n’utilise plus une flotte de conteneurs fictive. Le store construit désormais une carte par workflow actif renvoyé par `FactoryApiService.getWorkflows('active')`. Les coûts affichés sont ceux des runs réels, et une erreur ou une absence de workflows produit un état vide sans réintroduire de données de démonstration.

## Ce qui a changé

- `apps/cockpit-v2/src/app/core/factory.store.ts`
  - Le signal `sandboxes` démarre à `[]` et n’importe plus `SANDBOXES`; `recentTasks` reste disponible mais vide.
  - Chaque snapshot actif est transformé directement en `Sandbox`, avec identité du workflow, namespace/projet, ticket/branche, type de workflow et statut `working`/`idle` dérivé de l’état réel.
  - Le `RunSummary` mappé est conservé sur la carte, notamment son coût et son `unknownCostCount`.
  - `CostSummary` compte les cartes issues des workflows actifs, additionne les coûts réels et expose `totalUsd === workflowsUsd`; les montants Archay et de destruction ne sont plus calculés.
  - En cas d’échec du chargement REST, workflows, sandboxes, sessions et enrichissements sont vidés. L’invalidation SSE recharge ensuite les workflows actifs réels.
  - La simulation de destruction a été retirée. Les surfaces de sessions et d’actions gouvernées restent présentes.

- `apps/cockpit-v2/src/app/core/models.ts` et `apps/cockpit-v2/src/app/core/mock-data.ts`
  - `Sandbox` accepte les métadonnées réelles optionnelles (`namespace`, `workflowType`, `ticket`, etc.) et conserve les anciens champs mock uniquement comme champs optionnels de compatibilité.
  - `CostSummary.archayUsd` et `destroyedUsd` sont optionnels; le résumé réel repose sur `active`, `workflowsUsd`, `totalUsd` et `unknownCostCount`.
  - La flotte `SANDBOXES` et `RECENT_TASKS` a été supprimée. Seule la session de démonstration `SESSION_872641A8` est conservée pour le fallback de détail de session.

- `apps/cockpit-v2/src/app/features/sandboxes/` et `apps/cockpit-v2/src/app/layout/shell.component.html`
  - Les KPI parlent de workflows actifs et de coûts réels; les KPI/mentions Archay et « Sandboxes détruites » ont disparu.
  - Les cartes affichent le type de workflow, le ticket et les métadonnées disponibles, sans roster, vague ni coût Archay fictifs.
  - Le bouton de destruction n’est plus proposé. Les contrôles « Monter » et « Best-of-N » sont explicitement désactivés/informatifs, faute d’API de flotte de conteneurs.
  - L’état vide affiche « Aucun workflow actif. » et le résumé global reprend les coûts réels ainsi que l’incertitude éventuelle.

## Tests ajoutés ou adaptés

- `apps/cockpit-v2/src/app/core/factory.store.spec.ts` couvre l’état initial vide, la dérivation directe depuis les snapshots actifs, le mapping des statuts, l’agrégation réelle des coûts, l’invalidation SSE et la dégradation sur erreur REST, tout en conservant les vérifications des sessions et actions gouvernées.
- `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.spec.ts` vérifie l’identité et le coût réels, l’absence de destruction et l’absence des champs mock.
- `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.spec.ts` vérifie l’état vide, les KPI/carte issus du réel et les contrôles de création désactivés.

## Vérification

Depuis la racine du monorepo, lancer :

```bash
pnpm nx test cockpit-v2
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```

Les fixtures et assertions modifiées dans les fichiers de specs constituent la couverture ciblée du nouveau comportement; aucune modification backend Kotlin n’apparaît dans ce changement.
