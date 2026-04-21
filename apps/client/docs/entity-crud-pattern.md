# Entity CRUD Pattern (agentos-ui)

Standard pattern for any entity CRUD view in `libs/agentos-ui`. Consistently applied across Namespace, Integration, AiProvider, AiModel, User.

## Files to create

For an entity `Foo`:

```
libs/agentos-ui/src/lib/
  services/
    foo-state.service.ts          # dedicated state service (or foo-admin-state if admin context)
  components/
    foo-list/
      foo-list.component.ts       # smart container
      foo-list.component.html
      foo-list.component.scss
    foo-item/
      foo-item.component.ts       # presentational card
      foo-item.component.html
      foo-item.component.scss
    foo-form/
      foo-form.component.ts       # create + edit form
      foo-form.component.html
      foo-form.component.scss
```

Export new components from `libs/agentos-ui/src/index.ts` only if external access is needed (rare).

## Routes

```typescript
{ path: 'foos',              loadComponent: () => FooListComponent }
{ path: 'foos/new',          loadComponent: () => FooFormComponent }
{ path: 'foos/:fooId/edit',  loadComponent: () => FooFormComponent }
```

All routes carry `canActivate: [agentosReadyGuard]`.

## Design decisions

**State service vs direct API call**
Components never inject `*ControllerService` directly. Always go through a state service. If an entity is tied to the current user profile, create a separate dedicated service (e.g. `UserStateService` ≠ `UserAdminStateService`).

**`BehaviorSubject refresh$` vs `computed()` on signals**
Existing list containers use `BehaviorSubject<void>` + `switchMap` to trigger a reload after mutations (delete, create). This is the dominant Observable pattern in existing containers. New containers may use signals + explicit `loadAll()` if the state service already exposes signals — both coexist.

**`itemTemplate` vs default card**
Always provide an `[itemTemplate]` as soon as there are actions (edit, delete). The default card of `ds-entity-list` has no actions slot — it is only suitable for read-only lists with click navigation.

**Confirmation before delete**
Native `confirm()` in the item component, before emitting `deleteRequested`. The actual deletion is performed in the container or state service.

**Dual-mode form**
A single `FooFormComponent` handles both create and edit. The mode is determined by the presence of `:fooId` in `ActivatedRoute.snapshot.params`. In edit mode, try to resolve the entity from already-loaded state before making an HTTP call.

## Mapping to `EntityListItem`

`ds-entity-list` expects `{ id, name, description?, badges?, groupKey?, groupLabel? }`.

| `EntityListItem` field | What to put there |
|---|---|
| `name` | Primary label displayed in bold — usually `entity.name`, or `firstname + lastname` for a user |
| `description` | Secondary line — type, email, technical identifier |
| `badges` | Status or category visuals (warning/info/success/error) — use sparingly |
| `groupKey` / `groupLabel` | For grouped lists (e.g. models grouped by provider). Omit for a flat list |

## References

- `libs/agentos-ui/src/lib/components/namespace-list/` — full reference example with groups and multiple actions
- `libs/agentos-ui/src/lib/components/user-list/` — example with signal-based state service
- `libs/design-system/src/lib/components/entity-list/` — full `ds-entity-list` API
- `libs/design-system/src/lib/components/entity-card/` — `dsCardActions` slot for inline actions
- `libs/design-system/src/lib/components/kebab-menu/` — `ds-kebab-menu` for action menus
