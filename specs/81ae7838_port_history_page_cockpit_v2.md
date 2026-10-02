# Plan de Portage de `HistoryPageComponent` dans `apps/cockpit-v2`

## Vue d'ensemble
Le but de cette tâche est de porter la page d'historique des sandboxes (`HistoryPageComponent`) de la maquette design system factory (`scratch/cockpit-v2-mockups/design system factory/`) vers `apps/cockpit-v2/src/app/features/history/` et de remplacer `HistoryPlaceholderComponent` dans `apps/cockpit-v2/src/app/app.routes.ts`.

## Fichiers à créer / modifier

1. **Création du dossier et fichiers du composant :**
   - `apps/cockpit-v2/src/app/features/history/history-page.component.ts`
   - `apps/cockpit-v2/src/app/features/history/history-page.component.html`
   - `apps/cockpit-v2/src/app/features/history/history-page.component.scss`

2. **Modification des routes :**
   - `apps/cockpit-v2/src/app/app.routes.ts`

---

## Instructions Détaillées par Fichier

### 1. `apps/cockpit-v2/src/app/features/history/history-page.component.ts`

- **Type / Architecture** : Standalone Angular Component avec `ChangeDetectionStrategy.OnPush`.
- **Imports** :
  - Angular core & router: `ChangeDetectionStrategy`, `Component`, `computed`, `effect`, `inject`, `signal`, `viewChild`, `RouterLink`.
  - Angular Material: `MatButtonModule`, `MatChipsModule`, `MatFormFieldModule`, `MatIconModule`, `MatInputModule`, `MatMenuModule`, `MatPaginatorModule`, `MatPaginatorIntl`, `MatSelectModule`, `MatSortModule`, `MatTableDataSource`, `MatTableModule`.
  - Services / Stores : `FactoryStore` (depuis `../../core/factory.store`), `ShellState` (depuis `../../core/shell-state`), `FrPaginatorIntl` (depuis `../../core/paginator-intl.fr`).
  - Core Models : `Sandbox` (depuis `../../core/models`).
  - Shared UI & Pipes : `StatusChipComponent` (depuis `../../shared/ui/status-chip.component`), `PhaseBarComponent` (depuis `../../shared/ui/phase-bar.component`), `UsdPipe` (depuis `../../shared/pipes/format.pipes`).
- **Providers** :
  - `{ provide: MatPaginatorIntl, useClass: FrPaginatorIntl }` dans le decorator `@Component` pour configurer le paginateur en français.
- **Decorators & Metadonnées** :
  - `selector: 'sf-history-page'`
  - `imports: [...]` (tous les modules Material et composants/pipes partagés listés ci-dessus)
  - `templateUrl: './history-page.component.html'`
  - `styleUrl: './history-page.component.scss'`
  - `changeDetection: ChangeDetectionStrategy.OnPush`
- **Champs & Logique de classe** :
  - Injecter `FactoryStore` et `ShellState`.
  - Dans le `constructor`, initialiser le fil d'Ariane via `inject(ShellState).crumbs.set([{ label: 'Historique' }])`.
  - `protected readonly columns = ['name', 'branch', 'status', 'run', 'phases', 'cost', 'actions']`
  - `protected readonly query = signal('')`
  - `protected readonly status = signal<StatusFilter>('all')` avec `type StatusFilter = 'all' | 'working' | 'destroyed'`
  - `protected readonly project = signal('coday')`
  - `protected readonly rows = computed<Row[]>(() => this.store.sandboxes().map((s) => ({ ...s, cost: s.run?.costUsd ?? s.finalCostUsd ?? 0 })))` avec `interface Row extends Sandbox { cost: number }`
  - `protected readonly counts = computed(() => ({ all: this.rows().length, working: this.rows().filter((r) => r.status !== 'destroyed').length, destroyed: this.rows().filter((r) => r.status === 'destroyed').length }))`
  - `protected readonly filtered = computed(() => { ... })` (filtre par project `r.project === this.project()`, status `all` / `working` / `destroyed`, et query textuelle sur `r.name`, `r.branch`, `r.run?.id`, `r.run?.workflow`).
  - `protected readonly totalCost = computed(() => this.filtered().reduce((sum, r) => sum + r.cost, 0))`
  - `protected readonly costBars = computed(() => { ... })` (trié du coût le plus élevé au plus bas, calcule `pct = Math.max((r.cost / max) * 100, 0.5)`).
  - `protected readonly dataSource = new MatTableDataSource<Row>([])`
  - `private readonly sort = viewChild(MatSort)`
  - `private readonly paginator = viewChild(MatPaginator)`
  - Configurer 2 `effect()` dans le constructor :
    1. Mise à jour de `this.dataSource.data = this.filtered()`.
    2. Liaison de `this.dataSource.sort = this.sort() ?? null` et `this.dataSource.paginator = this.paginator() ?? null`.
  - `protected exportCsv(): void` : Génère et télécharge le fichier `sandboxes.csv` (délimiteur `;`).

---

### 2. `apps/cockpit-v2/src/app/features/history/history-page.component.html`

- Déduire le template HTML exact d'après la maquette dans `scratch/cockpit-v2-mockups/design system factory/software-factory-ui.zip` (`history-page.component.html`) :
  - En-tête de page avec titre `"Historique des sandboxes"`, description, et bouton `"Exporter CSV"`.
  - Barre de filtres : Champ de recherche (`MatInput`), chips de statut (`MatChipListbox` / `MatChipOption`), et sélecteur de projet (`MatSelect`).
  - Section Tableau (`mat-table` avec `matSort`, colonnes : `name`, `branch`, `status`, `run`, `phases`, `cost`, `actions`).
  - Footer de tableau affichant le nombre de sandboxes, le coût cumulé (`totalCost() | usd: 2`), et le composant `mat-paginator`.
  - Section graphique des barres de coût par sandbox (`ul.bars` avec `@for (b of costBars(); track b.name)`).

---

### 3. `apps/cockpit-v2/src/app/features/history/history-page.component.scss`

- Reprendre strictement le CSS/SCSS de la maquette (`scratch/cockpit-v2-mockups/design system factory/history-page.component.scss`) :
  - `.page`, `.page-head`, `.titles`, `.filters`, `.search`, `.project`.
  - Styles de la table Material (`.table-wrap`, `table`, `th.mat-mdc-header-cell`, `td.mat-mdc-cell`, etc.).
  - Styles pour les noms, liens, types mono, puces et badges.
  - Styles pour le footer de table `.table-foot`.
  - Styles pour la section des barres de coûts (`.costs`, `.costs-head`, `.legend`, `.sw`, `.bars`, `.bar-row`, `.bar-track`, `.bar-fill`, `.bar-val`).

---

### 4. `apps/cockpit-v2/src/app/app.routes.ts`

- Remplacer le lazy-loading de `HistoryPlaceholderComponent` pour la route `'historique'` par `HistoryPageComponent` :
  ```ts
  {
    path: 'historique',
    loadComponent: () =>
      import('./features/history/history-page.component').then((m) => m.HistoryPageComponent),
  },
  ```

---

## Directives & Règles à respecter
- Ne toucher à aucun autre fichier en dehors de `apps/cockpit-v2/src/app/features/history/*` et `apps/cockpit-v2/src/app/app.routes.ts`.
- S'assurer d'utiliser TypeScript en mode strict, OnPush, Signals Angular (`signal`, `computed`, `effect`, `viewChild`).

## Validation
- Vérifier la compilation et les tests de l'application via `pnpm nx test cockpit-v2` / `pnpm nx affected -t test`.
