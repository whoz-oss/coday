# Software Factory — templates Angular Material

Angular 20+ (testé en build strict avec Angular 21 et Material 21) : composants standalone, signals, nouveau control flow (`@if`, `@for`, `@let`), sans zone.js.

## Installation dans un projet existant

```bash
ng add @angular/material        # thème : choisir "Custom", il sera remplacé par src/styles.scss
```

Copier ensuite `src/` (ou seulement les dossiers voulus) et reprendre les deux `<link>` de polices de `src/index.html` (Manrope, JetBrains Mono, Material Symbols).

## Ce qu'il y a dedans

| Dossier | Contenu |
|---|---|
| `styles.scss` | Thème M3 (`mat.theme` + overrides) et tokens `--sf-*` (couleurs, polices) |
| `layout/` | Shell : `mat-toolbar` (fil d'Ariane, résumé des coûts), rail `mat-sidenav` |
| `features/sandboxes/` | Tableau de bord : indicateurs, formulaire « Nouvelle sandbox », tâches récentes, `sf-sandbox-card` |
| `features/session/` | Détail d'un run : en-tête, étapes, `sf-agent-timeline` (couloirs), `mat-accordion`, `sf-event-log` (virtual scroll, filtres, suivi live) |
| `features/history/` | `mat-table` + tri + pagination, filtres en chips, export CSV, barres de coût |
| `shared/ui/` | Atomes : `sf-status-chip`, `sf-metric`, `sf-phase-bar` |
| `shared/pipes/` | `usd`, `duration`, `tokens` |
| `core/` | Modèles TypeScript, `FactoryStore` (signals), données de démo, libellés FR du paginator |

## Brancher les vraies données

Tout passe par `core/factory.store.ts` : remplacer les données de `mock-data.ts` par `HttpClient` (REST) et le flux SSE de la factory (ex. `EventSource` → `signal.update`). Les composants ne lisent que des signals ; ils n'ont pas à changer.

Les actions (`Monter`, `Demander à Archay…`, `Détruire`…) émettent des événements ou font un `console.log` : à relier à vos services ou à des `MatDialog`.
