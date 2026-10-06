# Frise waterfall SSSF du cockpit Factory

## Ce qui a changé

La timeline de détail d’un run n’est plus une liste verticale de steps à largeur uniforme. Elle est désormais rendue comme une frise horizontale de type SSSF :

- un `run-strip` affiche le titre, le statut, la date de démarrage, le type du run et les chips `COST`, `RUNTIME`, `TOKENS`, `READ`, `WRITTEN` ;
- `RUNTIME` est calculé depuis le premier démarrage jusqu’à la dernière fin (ou jusqu’à `now` pour un run actif) ; les métriques absentes de la projection affichent explicitement `-` ;
- l’axe temporel fournit des graduations relatives (`0s`, `30s`, `1m`, etc.) ;
- les lanes sont ordonnées `engineer`, `code` si nécessaire, puis une lane par agent distinct ;
- chaque bloc est positionné à partir de `startedAt` et `durationMs`, avec un plancher de largeur pour les phases très courtes et un placement séquentiel sans chevauchement ;
- les steps sans date de démarrage apparaissent comme blocs `queued` en pointillés ;
- les blocs conservent `data-step-id`, le glyphe de statut, le nom et la durée, ce qui préserve le contrat de sélection/détail des phases ;
- les lanes agent affichent la structure `Model` et `Context`, avec `-` et une barre de contexte vide puisque ces données ne sont pas dans la projection Factory.

## Fichiers concernés

- `factory/dashboard/js/components/temporal-lanes.mjs` contient le nouveau calcul (`buildWaterfallLayout`) et le rendu (`renderWaterfallTimeline`), ainsi que la classification des acteurs, le calcul des ticks, les glyphes et les placeholders de métriques. Le rendu compact historique utilisé par les cartes de workflow reste disponible.
- `factory/dashboard/js/views/run-detail.mjs` branche la vue de détail sur le layout waterfall et lui transmet le temps courant ainsi que l’origine fournie par le payload de timing lorsque nécessaire.
- `factory/dashboard/css/dockyard.css` ajoute la présentation deep-space du run strip, de l’axe, des labels de lanes et des blocs translucides avec couleurs par acteur, états et responsive layout.
- `factory/dashboard/js/components/temporal-lanes.test.mjs` ajoute des tests Node natifs couvrant la classification, le positionnement réel, le plancher minimal, l’absence de chevauchement, la normalisation, les steps en attente, les glyphes et l’affichage des `-`.
- `specs/b9d0737a_sssf_timeline_waterfall.md` décrit le périmètre, les fichiers ciblés et les vérifications prévues.

## Vérification

Test unitaire ciblé :

```bash
node --test factory/dashboard/js/components/temporal-lanes.test.mjs
```

La spécification indique également la vérification projet suivante :

```bash
pnpm tsx libs/integration/src/lib/factory.tools.node-test.ts
```

Pour la vérification manuelle, servir le cockpit sur `http://127.0.0.1:8141/`, ouvrir le run `test-run-1` et contrôler la présence du run strip, des lanes `engineer`, `code` et agent, ainsi que des blocs placés selon les timestamps avec leur durée. Vérifier aussi que le coût, les tokens, les lectures/écritures, le modèle agent et le contexte affichent `-` (la barre de contexte restant vide), et qu’un clic sur un bloc ouvre toujours le détail de la phase.
