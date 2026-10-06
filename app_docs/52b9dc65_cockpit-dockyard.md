# Correctifs du cockpit Factory : détail SSSF et liste Dockyard

## Ce qui a changé

Le cockpit Factory conserve désormais une seule timeline dans le détail d’un run : la frise SSSF waterfall (run-strip, lanes horizontales et blocs). L’ancien Gantt `ACTEUR · TEMPS` a été retiré de l’assemblage de la vue, sans supprimer le panneau de détail d’une phase sélectionnée.

La liste des runs reprend maintenant la structure visuelle des cartes Coday Dockyard : grille responsive, surface sombre, identifiant mono, chip de statut avec icône SVG et glow, phase-dots, métadonnées, statistiques et sous-cartes d’étapes. Les données indisponibles par la projection Factory affichent `-` pour COST et TOKENS ; la durée est calculée à partir des étapes/timing disponibles, sinon `-`.

Les interactions existantes restent branchées : clic sur une carte ou sur « Ouvrir le détail » vers la timeline du run, et actions Restaurer, Supprimer et Purger via délégation d’événements. Les liens d’identité case/thread continuent d’utiliser le composant de lien existant.

## Fichiers concernés

- `factory/dashboard/js/views/run-detail.mjs` : ne rend plus `renderGantt()` ; la composition est maintenant en-tête → waterfall SSSF → panneau de phase → métriques.
- `factory/dashboard/js/components/gantt.mjs` : ne contient plus le rendu du Gantt historique ni ses algorithmes de lanes ; la normalisation des étapes et le mapping de statut nécessaires restent disponibles.
- `factory/dashboard/js/components/temporal-lanes.mjs` : conserve explicitement les helpers de swimlanes compacts et le rendu waterfall SSSF.
- `factory/dashboard/js/components/workflow-card.mjs` : rend les cartes `.card`, chips et icônes inline, phase-dots, ligne `.card-meta`, stats COST/RUNTIME/TOKENS, sessions d’étapes et actions de cycle de vie. Les statuts sont mappés vers `success`, `fail`, `running` et `queued` avec leurs libellés français.
- `factory/dashboard/js/views/projection.mjs` : place les cartes de chaque groupe dans une grille `.runs` et gère la navigation explicite ainsi que le clic sur carte, tout en laissant les boutons et liens suivre leur propre comportement.
- `factory/dashboard/css/dockyard.css` : ajoute le style Dockyard des grilles, cartes, états, chips, dots, stats, sessions, actions, responsive mobile et animations `pulse`/`spin`.
- `specs/52b9dc65_cockpit_run_detail_dockyard_redesign.md` : décrit le plan, les choix de structure/style et les vérifications prévues pour ces deux correctifs.

## Vérification

Depuis le dépôt :

```bash
pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
pnpm nx affected -t lint --base="$(cat /work/data/baseline)"
pnpm nx affected -t build --base="$(cat /work/data/baseline)"
```

En vérification manuelle, ouvrir `http://127.0.0.1:8141/` et contrôler que la liste affiche les cartes Dockyard, leurs chips/statistiques/phase-dots et les actions Actifs/Supprimés. Ouvrir ensuite `test-run-1` : le détail doit montrer la frise SSSF unique ; la sélection d’un bloc doit toujours afficher le panneau de phase, sans second bloc `ACTEUR · TEMPS`.
