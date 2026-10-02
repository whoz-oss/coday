# Historique des sandboxes dans cockpit-v2

## Ce qui a changé

La route Angular `historique` de `apps/cockpit-v2` n’affiche plus le placeholder : elle charge maintenant `HistoryPageComponent` en lazy loading. La nouvelle page présente l’historique des sandboxes avec :

- recherche textuelle, filtre de statut (`Toutes`, `Au travail`, `Détruites`) et sélection du projet ;
- tableau Material triable et paginé, avec sandbox, projet/branche, statut, dernier run, phases, coût et actions ;
- coût cumulé, pagination configurée avec les libellés français de `FrPaginatorIntl`, et export CSV via `exportCsv()` ;
- barres de coût par sandbox, triées du coût le plus élevé au plus faible, avec distinction visuelle des sandboxes actives et détruites.

Le composant est standalone, utilise `OnPush` et les Signals Angular (`signal`, `computed`, `effect`, `viewChild`). Il dérive les lignes et les coûts depuis `FactoryStore`, connecte `MatSort` et `MatPaginator` à `MatTableDataSource`, réutilise `StatusChipComponent`, `PhaseBarComponent` et `UsdPipe`, et initialise le fil d’Ariane avec `Historique` via `ShellState`.

## Fichiers concernés

- `apps/cockpit-v2/src/app/features/history/history-page.component.ts` : composant standalone, état réactif, filtrage, tri des coûts, branchement Material et export CSV.
- `apps/cockpit-v2/src/app/features/history/history-page.component.html` : structure de la page, filtres, tableau, menu d’actions, paginator et graphique de coûts.
- `apps/cockpit-v2/src/app/features/history/history-page.component.scss` : mise en forme de l’en-tête, des filtres, du tableau et des barres de coûts.
- `apps/cockpit-v2/src/app/app.routes.ts` : remplacement du chargement de `HistoryPlaceholderComponent` par le chargement lazy de `HistoryPageComponent` pour `historique`.
- `specs/81ae7838_port_history_page_cockpit_v2.md` : spécification ajoutée décrivant le portage, les fichiers et la validation attendue.

## Utilisation et vérification

Ouvrir l’application `cockpit-v2` puis naviguer vers `/historique`. Vérifier que la page charge les sandboxes du store, que la recherche, les chips de statut et le projet filtrent le tableau et les coûts, que les en-têtes Coût/Statut sont triables, que le paginator est en français et que le bouton **Exporter CSV** télécharge `sandboxes.csv`. Les liens de session et les actions du menu sont affichés lorsqu’un run est disponible.

La spécification référence la vérification Angular via `pnpm nx test cockpit-v2` ou `pnpm nx affected -t test`; aucun résultat d’exécution de ces commandes n’est inclus dans le diff.
