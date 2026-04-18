# Entity CRUD Pattern (agentos-ui)

Pattern standard pour toute vue CRUD d'entité dans `libs/agentos-ui`. Appliqué de façon cohérente sur Namespace, Integration, AiProvider, AiModel, User.

## Fichiers à créer

Pour une entité `Foo` :

```
libs/agentos-ui/src/lib/
  services/
    foo-state.service.ts          # state service dédié (ou foo-admin-state si contexte admin)
  components/
    foo-list/
      foo-list.component.ts       # container smart
      foo-list.component.html
      foo-list.component.scss
    foo-item/
      foo-item.component.ts       # card presentational
      foo-item.component.html
      foo-item.component.scss
    foo-form/
      foo-form.component.ts       # formulaire create + edit
      foo-form.component.html
      foo-form.component.scss
```

Exporter les nouveaux composants dans `libs/agentos-ui/src/index.ts` si accès externe nécessaire (rare).

## Routes

```typescript
{ path: 'foos',              loadComponent: () => FooListComponent }
{ path: 'foos/new',          loadComponent: () => FooFormComponent }
{ path: 'foos/:fooId/edit',  loadComponent: () => FooFormComponent }
```

Toutes les routes portent `canActivate: [agentosReadyGuard]`.

## Décisions de design

**State service vs appel direct API**
Les composants n'injectent jamais `*ControllerService` directement. Toujours passer par un state service. Si l'entité est liée au profil utilisateur courant, créer un service dédié séparé (ex: `UserStateService` ≠ `UserAdminStateService`).

**`BehaviorSubject refresh$` vs `computed()` sur signals**
Les listes existantes utilisent `BehaviorSubject<void>` + `switchMap` pour déclencher un rechargement après mutation (delete, create). C'est le pattern Observable dominant dans les containers existants. Les nouveaux containers peuvent utiliser des signals + `loadAll()` explicite si le state service expose déjà des signals — les deux coexistent.

**`itemTemplate` vs default card**
Toujours fournir un `[itemTemplate]` dès qu'on a des actions (edit, delete). Le default card de `ds-entity-list` n'a pas de slot d'actions — il convient uniquement pour les listes en lecture seule avec navigation au clic.

**Confirmation avant delete**
`confirm()` natif dans le composant item, avant d'émettre `deleteRequested`. La suppression effective est dans le container ou le state service.

**Formulaire dual-mode**
Un seul `FooFormComponent` pour create et edit. Le mode est déterminé par la présence de `:fooId` dans `ActivatedRoute.snapshot.params`. En edit, tenter de résoudre l'entité depuis le state déjà chargé avant de faire un appel HTTP.

## Mapping vers `EntityListItem`

`ds-entity-list` attend `{ id, name, description?, badges?, groupKey?, groupLabel? }`.

| Champ `EntityListItem` | Quoi y mettre |
|---|---|
| `name` | Libellé principal affiché en gras — souvent `entity.name`, ou `firstname + lastname` pour un user |
| `description` | Ligne secondaire — type, email, identifiant technique |
| `badges` | Statuts ou catégories visuels (warning/info/success/error) — à utiliser avec parcimonie |
| `groupKey` / `groupLabel` | Pour les listes groupées (ex: modèles groupés par provider). Omettre pour une liste plate |

## Références

- `libs/agentos-ui/src/lib/components/namespace-list/` — exemple de référence complet avec groupes et actions multiples
- `libs/agentos-ui/src/lib/components/user-list/` — exemple avec state service signals
- `libs/design-system/src/lib/components/entity-list/` — API complète de `ds-entity-list`
- `libs/design-system/src/lib/components/entity-card/` — slot `dsCardActions` pour actions inline
- `libs/design-system/src/lib/components/kebab-menu/` — `ds-kebab-menu` pour les menus d'actions
