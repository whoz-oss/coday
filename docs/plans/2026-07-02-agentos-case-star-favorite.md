# AgentOS Case Star / Favorite Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let a user mark a case as favorite (star), persist it per-user, and surface starred cases in a "Favoris" section at the top of the case drawer. This is PR B of issue #1014 (rename and soft-delete are handled separately).

**Architecture:** `star` is a boolean property on the existing `(User)-[:ADMIN|MEMBER]->(Case)` Neo4j relation (not a new node/relation). Two idempotent endpoints `PUT`/`DELETE /api/cases/{id}/star` set/clear it (mirroring the `NamespacePermissionEndpoints` grant/revoke pattern), gated on case `READ` because it edits the caller's own preference edge. The case list DTO (`CaseResource`) gains a `favorite` attribute populated by a companion "starred ids" query, so the front receives it in the existing list call (no separate favorites endpoint). The Angular drawer sorts favorites first and renders them in a "Favoris" accordion group.

**Tech Stack:** Kotlin / Spring Boot / Spring Data Neo4j (backend), OpenAPI-generated Angular TypeScript client, Angular 20 standalone components + design-system (frontend), Jest (front) + JUnit/Kotlin specs with embedded Neo4j (back).

---

## Decisions (locked)

- **Endpoint:** `PUT /api/cases/{id}/star` (favorite, 200) + `DELETE /api/cases/{id}/star` (unfavorite, 204). No body. Gate `@PreAuthorize("hasPermission(#id, 'Case', 'READ')")`. Generated client methods: `starCase(id)` / `unstarCase(id)`.
- **Persistence:** boolean property `starred` on the `[:ADMIN|MEMBER]` edge, set via new Cypher in `PermissionNodeNeo4jRepository`, exposed through `PermissionRepository` → `PermissionService` (mirrors `grantPermission`/`revokePermission`).
- **Listing:** add `favorite: Boolean = false` to `CaseResource`; enrich `GET /api/cases/by-parentId/{parentId}` (the endpoint the front uses) via a companion `listStarredEntityIds` query. Other list endpoints keep `favorite=false` for now (out of scope, note for a follow-up).
- **Display:** "Favoris" accordion group at the top when at least one case is starred; otherwise the current flat list. Star toggle button per row.

## Limitations to document (accepted for v1)

- Star writes to the caller's **direct** `[:ADMIN|MEMBER]` edge. A namespace-admin viewing a case they do not directly participate in (transitive access only, no direct edge) will **no-op** on star. Acceptable: favorites are personal to case participants. Do NOT create a new edge on star (would change the user's permission footprint).
- If a user somehow holds both an `ADMIN` and a `MEMBER` edge to the same case, `SET r.starred` applies to both; reads treat any starred edge as favorite. Consistent, no action needed.

## Branch

Work on a dedicated branch off `master` (independent of PR A):

```bash
git fetch origin && git switch -c feat/selim/#1014-agentos-case-star origin/master
```

---

## Task 1: Persist `starred` on the user↔entity edge (repository layer)

**Files:**
- Modify: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/permissions/PermissionNodeNeo4jRepository.kt`
- Modify: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/permissions/PermissionRepository.kt`
- Modify: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/permissions/Neo4jPermissionRepository.kt`
- Test: mirror `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/persistence/neo4j/AbstractCasePersistenceSpec.kt` (embedded Neo4j). Add a spec that grants a user ADMIN on a case, sets starred=true, asserts `findStarredEntityIds` returns the case id, then sets starred=false and asserts it is empty.

**Step 1: Write the failing persistence test**

Locate the embedded-Neo4j persistence spec pattern (`EmbeddedNeo4jCasePersistenceSpec` / `AbstractCasePersistenceSpec`). Add a test that:
1. creates a user + case, grants ADMIN (`createAdminPermission`),
2. calls `setStarred(userId, caseId, "Case", true)`,
3. asserts `findStarredEntityIds(userId, "Case")` contains the case id,
4. calls `setStarred(..., false)`,
5. asserts `findStarredEntityIds` no longer contains it.

**Step 2: Run it, verify it fails** (methods don't exist yet)

Run: `cd agentos && ./gradlew :agentos-service:test --tests "*StarPersistence*"`
Expected: FAIL to compile / method not found.

**Step 3: Add the Cypher methods** to `PermissionNodeNeo4jRepository.kt` (after the existing management queries, ~line 149):

```kotlin
    // Star / favorite — a per-user boolean property on the user↔entity relation.

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN|MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        SET r.starred = $starred
    """,
    )
    fun setStarred(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("starred") starred: Boolean,
    )

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN|MEMBER]->(e)
        WHERE $entityLabel IN labels(e) AND r.starred = true
        RETURN e.id
    """,
    )
    fun findStarredEntityIds(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>
```

**Step 4: Add to the `PermissionRepository` interface:**

```kotlin
    /**
     * Sets the per-user "starred" (favorite) flag on the user's direct relation to an entity.
     * No-op if the user has no direct ADMIN/MEMBER relation on the entity.
     */
    fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean)

    /** Ids of entities of the given type that the user has starred (favorited). */
    fun listStarredEntityIds(userId: String, entityType: EntityType): Set<String>
```

**Step 5: Implement in `Neo4jPermissionRepository`** (mirror `grantPermission` / `listEntitiesForUser` error handling):

```kotlin
    override fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean) {
        try {
            permissionNodeRepository.setStarred(
                userId = userId,
                entityId = entityId,
                entityLabel = entityType.label,
                starred = starred,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error setting starred=$starred for user=$userId on $entityType:$entityId" }
            throw e
        }
    }

    override fun listStarredEntityIds(userId: String, entityType: EntityType): Set<String> =
        try {
            permissionNodeRepository.findStarredEntityIds(userId, entityType.label).toSet()
        } catch (e: Exception) {
            logger.error(e) { "Error listing starred entities for user=$userId, type=$entityType" }
            emptySet() // fail-closed
        }
```

**Step 6: Run the test, verify GREEN.** `cd agentos && ./gradlew :agentos-service:test --tests "*StarPersistence*"` → PASS.

**Step 7: Commit** `git add -A && git commit -m "feat: #1014 persist starred flag on user-case relation"`

---

## Task 2: Expose star through `PermissionService`

**Files:**
- Modify: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/permissions/PermissionService.kt`
- Modify: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/permissions/PermissionServiceImpl.kt`

**Step 1:** Add to the `PermissionService` interface:

```kotlin
    /** Sets the caller's per-user favorite flag on their direct relation to an entity. */
    fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean)

    /** Ids of entities of the given type the user has starred. */
    fun listStarredEntityIds(userId: String, entityType: EntityType): Set<String>
```

**Step 2:** Implement in `PermissionServiceImpl` (pure delegation; NO cache involvement — starred does not affect permission checks):

```kotlin
    override fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean) =
        permissionRepository.setStarred(userId, entityType, entityId, starred)

    override fun listStarredEntityIds(userId: String, entityType: EntityType): Set<String> =
        permissionRepository.listStarredEntityIds(userId, entityType)
```

**Step 3: Run** `cd agentos && ./gradlew :agentos-service:compileKotlin` → success. **Commit.**

---

## Task 3: `favorite` on the DTO + star endpoints + enriched listing (controller)

**Files:**
- Modify: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseResource.kt`
- Modify: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseController.kt`
- Test: mirror the existing case controller test (find it: `rg -l "class CaseController" agentos/agentos-service/src/test`). Add: (a) `starCase` calls `permissionService.setStarred(..., true)`, `unstarCase` calls `..., false)`; (b) `listByParent` sets `favorite=true` on cases whose id is in `listStarredEntityIds`.

**Step 1:** Add the field to `CaseResource` (data class, so `.copy(favorite = …)` works downstream):

```kotlin
    val removed: Boolean = false,
    val favorite: Boolean = false,
```

**Step 2:** Add imports to `CaseController.kt`:

```kotlin
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
```

**Step 3:** Replace `listByParent` body to enrich with `favorite` in BOTH branches (admin fast-path AND filtered):

```kotlin
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<CaseResource> {
        val user = userService.getCurrentUser()
        val userId = user.id.toString()
        val starredIds = permissionService.listStarredEntityIds(userId, EntityType.CASE)
        val isNamespaceAdmin =
            permissionService.hasPermission(userId, EntityType.NAMESPACE, parentId.toString(), Action.WRITE)
        val cases =
            if (isNamespaceAdmin) caseService.findByParent(parentId)
            else caseService.findAccessibleByUserInNamespace(user.id, parentId)
        return cases.map { toResource(it).copy(favorite = it.metadata.id.toString() in starredIds) }
    }
```

**Step 4:** Add the two endpoints (near `killCase`, before the `companion object`):

```kotlin
    /** PUT /api/cases/{id}/star — mark the case as favorite for the current user. */
    @PutMapping("/{id}/star")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#id, 'Case', 'READ')")
    fun starCase(
        @PathVariable id: UUID,
    ) {
        val userId = userService.getCurrentUser().id.toString()
        permissionService.setStarred(userId, EntityType.CASE, id.toString(), true)
        logger.info { "User $userId starred case $id" }
    }

    /** DELETE /api/cases/{id}/star — remove the case from the current user's favorites. */
    @DeleteMapping("/{id}/star")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'Case', 'READ')")
    fun unstarCase(
        @PathVariable id: UUID,
    ) {
        val userId = userService.getCurrentUser().id.toString()
        permissionService.setStarred(userId, EntityType.CASE, id.toString(), false)
        logger.info { "User $userId unstarred case $id" }
    }
```

**Step 5:** Write the controller tests (mirror the existing spec), run RED, implement is already above, run GREEN.
Run: `cd agentos && ./gradlew :agentos-service:test --tests "*CaseController*"` → PASS.

**Step 6: Commit** `git commit -am "feat: #1014 add case star endpoints and favorite in listing"`

---

## Task 4: Regenerate the OpenAPI client

**Files:** generated — `agentos/openapi/agentos-openapi.yaml`, `libs/agentos-api-client/src/lib/**` (do NOT hand-edit).

**Step 1:** Run the orchestrated regen (spec from backend → TS client):

```bash
nx run agentos-service:regenerate
```

**Step 2:** Verify the outputs:
- `libs/agentos-api-client/src/lib/model/case.ts` now has `favorite: boolean`.
- `libs/agentos-api-client/src/lib/api/case-controller.service.ts` now has `starCase(id, …)` and `unstarCase(id, …)`.

Run: `rg -n "favorite|starCase|unstarCase" libs/agentos-api-client/src/lib` → all three present.

**Step 3: Commit the generated files** `git add agentos/openapi libs/agentos-api-client && git commit -m "chore: #1014 regenerate api client for case star"`

---

## Task 5: Carry `favorite` to the list item (design-system + mapper)

**Files:**
- Modify: `libs/design-system/src/lib/components/entity-list/entity-list.component.ts:24-31` (`EntityListItem`)
- Modify: `libs/agentos-ui/src/lib/components/case-item/case-item.component.ts`

**Step 1:** Extend `EntityListItem` with an optional favorite flag (backward-compatible):

```typescript
export interface EntityListItem {
  id: string
  name: string
  description?: string
  badges?: EntityCardBadge[]
  groupKey?: string
  groupLabel?: string
  favorite?: boolean
}
```

**Step 2:** Update `CaseItemComponent.toListItem` to surface favorite (keep `name = c.id` — title display stays out of scope, owned by the rename/#1010 work):

```typescript
  static toListItem(c: Case): EntityListItem {
    return {
      id: c.id ?? '',
      // Cases don't have a user-facing name yet — display the full id
      name: c.id ?? '—',
      description: undefined,
      favorite: c.favorite ?? false,
    }
  }
```

**Step 3:** `nx lint design-system agentos-ui` → clean. **Commit.**

---

## Task 6: Drawer — favorites-first grouping + star toggle

**Files:**
- Modify: `libs/agentos-ui/src/lib/components/case-drawer/case-drawer.component.ts`
- Modify: `libs/agentos-ui/src/lib/components/case-drawer/case-drawer.component.html`
- Modify: `libs/agentos-ui/src/lib/components/case-drawer/case-drawer.component.scss`
- Test: `libs/agentos-ui/src/lib/components/case-drawer/case-drawer.component.spec.ts`

**Step 1: Write failing tests** (append to the existing spec):

```typescript
  it('emits starToggled with the opposite state when a star is toggled', () => {
    const c = new CaseDrawerComponent()
    const emitted: Array<{ id: string; starred: boolean }> = []
    c.starToggled.subscribe((e) => emitted.push(e))

    c['onStarToggled']({ id: 'case-9', name: 'case-9', favorite: false })

    expect(emitted).toEqual([{ id: 'case-9', starred: true }])
  })

  it('orders favorites first and groups them when at least one case is starred', () => {
    const c = new CaseDrawerComponent()
    c.cases = [
      { id: 'a', namespaceId: 'ns' } as unknown as Case,
      { id: 'b', namespaceId: 'ns', favorite: true } as unknown as Case,
    ]
    c.ngOnChanges({ cases: { currentValue: c.cases } } as unknown as SimpleChanges)

    expect(c['caseItems'].map((i) => i.id)).toEqual(['b', 'a'])
    expect(c['caseItems'][0].groupKey).toBe('favorites')
    expect(c['caseItems'][1].groupKey).toBe('cases')
  })
```

Add imports at the top of the spec: `import { Case } from '@whoz-oss/agentos-api-client'` and `import { SimpleChanges } from '@angular/core'`.

**Step 2:** Run RED — `nx test agentos-ui --testFile=case-drawer.component.spec.ts` → FAIL (`starToggled`/`onStarToggled` missing, grouping absent).

**Step 3:** Update `CaseDrawerComponent`:

```typescript
  @Output() deleteRequested = new EventEmitter<string>()
  @Output() starToggled = new EventEmitter<{ id: string; starred: boolean }>()

  // …

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['cases']) {
      const sorted = [...this.cases].sort((a, b) => Number(b.favorite ?? false) - Number(a.favorite ?? false))
      const hasFavorites = sorted.some((c) => c.favorite)
      this.caseItems = sorted.map((c) => {
        const item = CaseItemComponent.toListItem(c)
        if (hasFavorites) {
          item.groupKey = c.favorite ? 'favorites' : 'cases'
          item.groupLabel = c.favorite ? 'Favoris' : 'Cases'
        }
        return item
      })
    }
  }

  protected onStarToggled(item: EntityListItem): void {
    this.starToggled.emit({ id: item.id, starred: !item.favorite })
  }
```

**Step 4:** Add the star button in `#caseItemTpl` (before the delete button):

```html
    <ds-icon-button
      class="case-drawer-item__star"
      [icon]="item.favorite ? 'star' : 'star_border'"
      [variant]="item.favorite ? 'primary' : 'default'"
      [title]="item.favorite ? 'Unstar case' : 'Star case'"
      (action)="onStarToggled(item)"
    />
```

**Step 5:** SCSS — give the star the same `flex: none` treatment as delete:

```scss
  &__star {
    flex: none;
  }
```

**Step 6:** Run GREEN — `nx test agentos-ui --testFile=case-drawer.component.spec.ts` → PASS. **Commit.**

---

## Task 7: Shell — call star/unstar and refresh

**Files:**
- Modify: `libs/agentos-ui/src/lib/components/case-shell/case-shell.component.ts`
- Modify: `libs/agentos-ui/src/lib/components/case-shell/case-shell.component.html`
- Test: `libs/agentos-ui/src/lib/components/case-shell/case-shell.component.spec.ts`

**Step 1: Write failing tests** (extend the shell spec; add `starCase`/`unstarCase` to `caseControllerMock`):

```typescript
    it('stars a case then refreshes the list', () => {
      const component = makeComponent(`/agentos/${NS_ID}/cases`)
      caseControllerMock.starCase = jest.fn().mockReturnValue(of(undefined))

      component['onStarToggled']({ id: 'case-1', starred: true })

      expect(caseControllerMock.starCase).toHaveBeenCalledWith('case-1')
      expect(caseControllerMock.listByParentCase).toHaveBeenCalledTimes(2)
    })

    it('unstars a case when toggled off', () => {
      const component = makeComponent(`/agentos/${NS_ID}/cases`)
      caseControllerMock.unstarCase = jest.fn().mockReturnValue(of(undefined))

      component['onStarToggled']({ id: 'case-1', starred: false })

      expect(caseControllerMock.unstarCase).toHaveBeenCalledWith('case-1')
    })
```

Add `starCase`/`unstarCase` to the `caseControllerMock` object in `makeComponent` returning `of(undefined)` by default so other tests keep passing.

**Step 2:** Run RED → FAIL (`onStarToggled` missing).

**Step 3:** Implement in `CaseShellComponent`:

```typescript
  protected onStarToggled(event: { id: string; starred: boolean }): void {
    const request = event.starred
      ? this.caseController.starCase(event.id)
      : this.caseController.unstarCase(event.id)
    request.subscribe({
      next: () => this.refreshCases(),
      error: (err) => console.error(`[CaseShell] Failed to update star for case ${event.id}:`, err),
    })
  }
```

**Step 4:** Wire it in `case-shell.component.html`:

```html
          (deleteRequested)="onDeleteRequested($event)"
          (starToggled)="onStarToggled($event)"
```

**Step 5:** Run GREEN — `nx test agentos-ui --testFile=case-shell.component.spec.ts` → PASS. **Commit.**

---

## Task 8: Full verification + PR

**Step 1:** Backend: `cd agentos && ./gradlew :agentos-service:test` → all pass.
**Step 2:** Front: `nx test agentos-ui` and `nx test design-system` → all pass.
**Step 3:** `nx lint agentos-ui design-system agentos-api-client` → clean.
**Step 4:** `nx build-angular client --configuration=development` → compiles (strictTemplates validates the new bindings).
**Step 5:** Push and open PR to `master` referencing #1014 (scope: star/favorite). Body lists the endpoint, the persistence model, the regen, and the "Limitations" section above.

---

## Notes for the executor

- Do NOT hand-edit `libs/agentos-api-client/src/lib/**` — always regenerate (Task 4).
- The pre-commit hook runs prettier on staged files; expect it to reformat and re-stage.
- Keep `name = c.id` in `toListItem`; switching to `c.title` belongs to the rename/#1010 work, not this PR.
- If the Kotlin controller/persistence test infra differs from the assumptions here, mirror the closest existing spec rather than inventing setup.
