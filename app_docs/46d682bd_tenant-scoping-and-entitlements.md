# Scoping tenant & entitlements (B6-T3)

La tâche B6-T3 rend le multi-tenant (`organizationId` + `workstreamId`) et l’autorisation par entitlement **effectifs de bout en bout**, sans changer les signatures publiques ni les call sites existants.

## Ce qui a changé

- `factory/src/domain/identity/tenant-scope.ts` (nouveau)
  - `resolveTenantScope(trustContext)` dérive le scope tenant **exclusivement** du `TrustContext` vérifié, en fail-closed : contexte absent, `authenticationMethod: 'anonymous'`, `organizationId` ou `workstreamId` manquant/vide ⇒ `scope: null` avec une raison stable (`MISSING_TRUST_CONTEXT`, `UNAUTHENTICATED`, `MISSING_ORGANIZATION_ID`, `MISSING_WORKSTREAM_ID`).
  - `requireTenantScope()` jette `TenantScopeError` (code `TENANT_SCOPE_REQUIRED`) pour forcer un refus avant toute lecture/écriture scoppée.
  - `tenantScopeKey()`, `sameTenantScope()`, `isTenantScopeWithin()` fournissent l’identité composite canonique aux caches et aux contrôles d’appartenance.
  - Aucune valeur par défaut implicite, aucun header client : le scope ne peut venir que de l’identité authentifiée.

- `factory/src/domain/identity/entitlements.ts` (nouveau)
  - `resolvePrincipalEntitlements(trustContext)` normalise rôles/scopes via le vocabulaire AgentOS (`ADMIN` → `admin`, `MEMBER` → `dev`) et calcule `isAdmin` de façon fail-closed.
  - `authorizeAdminAccess(trustContext, target?)` rend le verdict `{ authorized, reason }`. La cible optionnelle (`organizationId`/`workstreamId`) ajoute un contrôle de namespace : un admin d’un workstream n’est **pas** admin d’un autre (`OUT_OF_NAMESPACE`).
  - Un contexte `anonymous` a zéro privilège même s’il porte `roles: ['admin']` / `scopes: ['*']`.
  - Les headers client (`x-roles`, `x-organization-id`, `x-workstream-id`, …) ne sont jamais consultés.

- `factory/dashboard/http-utils.mjs`
  - Le **corps** de `checkAdminAuthorization(trustContext)` délègue désormais à `authorizeAdminAccess` : la résolution d’entitlement réelle remplace le seam de B5. La signature et `requireAdminRole(trustContext)` sont inchangées, tout comme les call sites (`artifact-admin-routes.mjs`).
  - `extractTrustContext` n’a pas été modifié : il continue de résoudre les memberships côté serveur et d’ignorer les headers client.

- `factory/src/adapters/persistence/sql/sql-agent-step-result-repository.ts`
  - `#findByToken` (résolution d’un capability token) était le seul scan SQL non tenant-scopé ; il filtre désormais `organization_id` **et** `workstream_id`. Un token émis dans un autre workstream n’est plus rachetable.

- `factory/src/domain/identity/index.ts`
  - Le barrel ré-exporte `tenant-scope.ts` et `entitlements.ts`.

- `factory/runtime/factory-operational.mjs` régénéré **une fois** via `node factory/toolchain/build.mjs` (jamais édité à la main).

## Modèle d’autorisation

| Source AgentOS | Rôle Factory | Privilège admin |
| --- | --- | --- |
| `ADMIN` | `admin` | oui (dans son propre namespace) |
| `MEMBER` / `DEV` / `DEVELOPER` | `dev` | non |
| scope `admin:*` | — | oui |
| scope `*` (loopback-dev explicite uniquement) | — | oui |

Toute incertitude (contexte absent, `anonymous`, rôle inconnu, namespace cible différent) ⇒ aucun privilège. Les commandes admin d’artefacts (purge, legal hold, GC) renvoient `403 FORBIDDEN_ADMIN_REQUIRED` pour un appelant non authentifié ou non-admin.

## Vérification et compatibilité

- `factory/tests/test-tenant-isolation.mjs` (nouveau) couvre hors ligne : résolution de scope fail-closed, rejet de lecture **et** d’écriture cross-workstream et cross-organisation (instances de workflow, évidence, work units, capability tokens de résultats, métadonnées d’artefacts), mapping AgentOS `ADMIN`/`MEMBER`, admin de namespace (un admin d’un workstream ne l’est pas d’un autre), ignorage des headers client falsifiés, et passage réel des commandes admin d’artefacts (admin/loopback-dev acceptés, anonyme/member refusés en 403).
- Aucune signature publique modifiée : les suites existantes restent vertes (`test-boundary-hardening.mjs`, `test-coday-identity-bridge.mjs`, `test-agentos-membership-resolver.mjs`, `test-artifact-admin-commands.mjs`, `test-identity-trust-context.mjs`, `test-sql-repository-ports-adapters.mjs`).

## Utilisation / contrôle manuel

```sh
node factory/tests/test-tenant-isolation.mjs
node factory/tests/test-boundary-hardening.mjs
node factory/tests/test-coday-identity-bridge.mjs
node factory/tests/test-agentos-membership-resolver.mjs
node factory/tests/test-artifact-admin-commands.mjs
node factory/tests/test-identity-trust-context.mjs
node factory/tests/test-sql-repository-ports-adapters.mjs
```

Pour modifier les sources TypeScript, régénérer le bundle hors ligne :

```sh
node factory/toolchain/build.mjs
```
