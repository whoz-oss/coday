# Reconnaissance architecturale & proposition d'intégration
## Authentification et groupes d'utilisateurs : Coday / AgentOS / Factory TrustContext

> **Statut :** document de reconnaissance — **aucun code applicatif n'est modifié par ce document**.
> Il prépare la tâche **B6** (modèle d'identité et d'autorisation réel de la Factory) en établissant
> les faits observés dans le code, les points de jonction, une proposition de mapping vers les rôles
> Factory V1 (`po/pm`, `dev`, `admin`) et les décisions à trancher.

**Périmètre analysé**

| Composant | Emplacement | Rôle |
|---|---|---|
| Serveur Coday (Express/TS) | `apps/server/src/` | Terminaison HTTP, résolution d'identité |
| Cœur Coday (TS) | `libs/service/`, `libs/model/`, `libs/utils/` | Config utilisateur, groupes locaux, rôles |
| Backend AgentOS (Kotlin/Spring) + SDK | `agentos/agentos-service/`, `agentos/agentos-sdk/`, `libs/agentos-api-client/` | Groupes d'utilisateurs *namespace-scoped* |
| Frontière Factory | `factory/dashboard/`, `factory/src/domain/identity/` | `TrustContext`, Fake IdP, résolution de memberships |

---

## Section 1 — Authentification Coday (couche d'authentification & résolution d'identité)

### 1.1 Le flag `--auth`

Le flag est déclaré et parsé dans `apps/server/src/lib/coday-options-utils.ts` :

```ts
.option('auth', {
  type: 'boolean',
  description: 'Enables web auth check (expects x-forwarded-email header from auth proxy)',
})
```

puis réduit en booléen et propagé dans les options :

```ts
const auth: boolean = !!argv.auth
// ...
return {
  oneshot, debug, project: projectName, prompts, fileReadOnly,
  configDir, auth, agentFolders, noLog, logFolder, forcedProject, baseUrl: argv.base_url,
}
```

Le répertoire de configuration par défaut est `~/.coday` :

```ts
const defaultConfigDir = path.join(os.homedir(), '.coday')
const configDir: string = argv.coday_config_dir || defaultConfigDir
```

**Fait marquant :** `--auth` est un **interrupteur binaire**. Il n'y a aucune notion d'émetteur
(issuer), d'audience, de JWKS, ni de fournisseur OIDC configurable. Tout est délégué au proxy.

### 1.2 Ordre de résolution de l'identité

La logique complète est isolée dans `apps/server/src/lib/resolve-username.ts` :

```ts
const CF_JWT_HEADER = 'cf-access-jwt-assertion'
const EMAIL_HEADER = 'x-forwarded-email'

export function resolveUsername(headers: Record<string, string | string[] | undefined>, authEnabled: boolean): string {
  if (!authEnabled) {
    return os.userInfo().username
  }

  // 1. Cloudflare Access JWT
  const cfToken = headers[CF_JWT_HEADER] as string | undefined
  const cfEmail = extractEmailFromCfJwt(cfToken)
  if (cfEmail) return cfEmail

  // 2. Standard reverse-proxy header
  const fwdEmail = headers[EMAIL_HEADER] as string | undefined
  if (fwdEmail && fwdEmail.length > 0) return fwdEmail

  // 3. No valid identity found
  throw new Error(
    'Authentication required but no valid identity header found. ' +
      'Expected either a CF_Authorization JWT (Cloudflare Access) or ' +
      'an x-forwarded-email header from the upstream auth proxy.'
  )
}
```

Ordre exact :

1. **`cf-access-jwt-assertion`** (Cloudflare Access) — le payload JWT est décodé, sans vérification
   de signature :

```ts
export function extractEmailFromCfJwt(token: string | undefined): string | null {
  if (!token) return null
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    // base64url -> base64 -> JSON
    const payload = parts[1]!.replace(/-/g, '+').replace(/_/g, '/')
    const json = Buffer.from(payload, 'base64').toString('utf8')
    const claims = JSON.parse(json)
    const email = claims?.email
    return typeof email === 'string' && email.length > 0 ? email : null
  } catch {
    return null
  }
}
```

   Le commentaire d'en-tête du fichier est explicite : *« No signature verification is performed here:
   Cloudflare already validated the token at the edge before forwarding the request. »* Le contrôle
   cryptographique est donc **entièrement délégué au bord** (Cloudflare Access). Côté Coday,
   un attaquant qui atteint directement le port du serveur peut forger ce segment base64.

2. **`x-forwarded-email`** — en-tête de proxy inverse, accepté tel quel dès qu'il est non vide.

3. **Repli local (pas de `--auth`)** — `os.userInfo().username`. C'est le mode mono-utilisateur de
   développement.

4. **Échec** — si `--auth` est actif et qu'aucun des deux en-têtes n'est exploitable, une exception
   est levée (`Authentication required but no valid identity header found`).

### 1.3 Garde-fou : comptes système interdits

`apps/server/src/server.ts` (ligne ~277) définit une liste noire et un wrapper `getUsername()` qui
applique la résolution puis rejette les comptes de service :

```ts
const FORBIDDEN_USERNAMES = [
  'root', 'admin', 'administrator', 'system', 'daemon', 'nobody', 'node',
  'app', 'service', 'docker', 'www-data', 'nginx', 'apache', 'ansible',
] as const

function getUsername(req: express.Request): string {
  const username = resolveUsername(req.headers as Record<string, string | string[] | undefined>, !!codayOptions.auth)

  if (FORBIDDEN_USERNAMES.includes(username.toLowerCase() as any)) {
    throw new Error(
      `Security error: Cannot run with username "${username}". ` +
        'This appears to be a system or service account. ' + /* ... */
    )
  }
  return username
}
```

Ce `getUsername` est injecté dans les routes (`server.ts:328`, `server.ts:394`) :

```ts
registerUserRoutes(app, getUsername, codayOptions.configDir, !!codayOptions.auth)
// ...
registerTokenUsageRoutes(app, logger, getUsername, codayOptions.auth)
```

Il n'existe **aucun middleware de session ni cookie** : l'identité est recalculée **à chaque requête**
à partir des en-têtes. Le commentaire `server.ts:566-568` le confirme par la négative :

```ts
// In --auth mode we cannot determine the user here, so we skip auto-close.
if (codayOptions.auth) {
  debugLog('CLEANUP', 'autoCloseStaleThreads: skipped in --auth mode (no server-side user identity at startup)')
}
```

### 1.4 Persistance de la configuration utilisateur

L'identité résolue devient la clé de rangement de la configuration via `UserService`
(`libs/service/src/lib/user.service.ts`) :

```ts
const usersFolder = 'users'
const USER_FILENAME = 'user.yaml'

export class UserService {
  public userConfigPath: string
  readonly sanitizedUsername: string
  // ...
  constructor(codayConfigPath: string | undefined, public readonly username: string, private readonly interactor: Interactor) {
    this.sanitizedUsername = sanitizeUsername(username)

    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    const usersPath = path.join(codayConfigPath ?? defaultConfigPath, usersFolder)
    this.userConfigPath = path.join(usersPath, this.sanitizedUsername)

    mkdirSync(this.userConfigPath, { recursive: true })

    const filePath = path.join(this.userConfigPath, USER_FILENAME)
    if (!existsSync(filePath)) {
      const defaultConfig = { ...DEFAULT_USER_CONFIG, version: 1, username }
      writeYamlFile(filePath, defaultConfig)
    }
    // ... lecture, migrations (userConfigMigrations), backfill du champ username
  }
}
```

La sanitisation est centralisée dans `libs/utils/src/lib/username-utils.ts` :

```ts
export function sanitizeUsername(username: string): string {
  return username.replace(/[^a-zA-Z0-9]/g, '_')
}
```

Conséquences pratiques :

- Chemin effectif : `~/.coday/users/<username-sanitized>/user.yaml`.
  Ex. `user@example.com` → `~/.coday/users/user_example_com/user.yaml`.
- Le champ brut `username` (email) est conservé **dans** le fichier pour permettre à `GET /api/users`
  de restituer de vrais emails (`apps/server/src/lib/user.routes.ts`).
- La config est **par nœud/par utilisateur** et vit sur le système de fichiers local : pas de
  révocation centralisée, pas d'audit, pas de synchronisation multi-instances.
- `libs/service/src/lib/user-groups.ts` **réimplémente** la même sanitisation (duplication à noter).

### 1.5 Ce qui est délégué vs ce qui est local

| Concern | Où c'est traité | Détail |
|---|---|---|
| Émission/vérification du jeton | **Proxy inverse externe** (Cloudflare Access, OAuth proxy) | Aucun code dans Coday ne valide une signature (ni `cf-access-jwt-assertion`, ni `x-forwarded-email`). |
| Transport de l'identité | En-têtes HTTP | `cf-access-jwt-assertion` ou `x-forwarded-email`. |
| Extraction de l'identité | `apps/server/src/lib/resolve-username.ts` | Décodage naïf du payload CF + lecture d'en-tête. |
| Contrôle des comptes système | `apps/server/src/server.ts` (`FORBIDDEN_USERNAMES`) | Filtre applicatif local. |
| Session / cookie | — | **Absent** : l'identité est recalculée à chaque requête. |
| Rôles applicatifs | Fichier `user.yaml` (`groups`) | Voir Section 2. |
| Authentification AgentOS | **Hors du serveur Coday** | AgentOS possède son propre modèle de sécurité Spring (`@PreAuthorize`) ; Coday ne fait que consommer son API. |

> **Point clé :** l'authentification de Coday est **entièrement déléguée au champ d'inversion**
> (reverse proxy). Le serveur ne fait aucune confiance cryptographique aux en-têtes qu'il reçoit :
> il suppose qu'ils ont été posés par un proxy de confiance et que le réseau empêche tout accès direct.
> AgentOS est totalement étranger à cette chaîne.

---

## Section 2 — Groupes d'utilisateurs et rôles : Coday vs AgentOS

Deux modèles coexistent et ne se recouvrent pas sémantiquement.

### 2.1 Modèle local Coday (simple, plat, fichier)

**Stockage** — `libs/model/src/lib/user-config.ts` :

```ts
export interface UserConfig {
  version: number
  username?: string   // email brut, pour GET /api/users
  groups?: string[]   // ex. ["CODAY_ADMIN"]
  // ...
}

export const DEFAULT_USER_CONFIG: UserConfig = {
  version: 1,
  // bio is undefined by default
}
```

`groups` est un **tableau de chaînes plat**, sans scope, sans hiérarchie.

**Lecture & décision** — `libs/service/src/lib/user-groups.ts` :

```ts
function sanitizeUsername(username: string): string {
  return username.replace(/[^a-zA-Z0-9]/g, '_')
}

export function isUserAdmin(username: string, configDir?: string): boolean {
  try {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    const sanitizedUsername = sanitizeUsername(username)
    const userConfigPath = path.join(configDir ?? defaultConfigPath, 'users', sanitizedUsername, 'user.yaml')

    const userConfig = readYamlFile<{ groups?: string[] }>(userConfigPath)
    if (!userConfig) return false
    return userConfig.groups?.includes('CODAY_ADMIN') ?? false
  } catch (error) {
    return false // fail-closed
  }
}

export function canAccessWebhook(webhookCreatedBy: string, requestingUser: string, configDir?: string): boolean {
  if (webhookCreatedBy === requestingUser) return true
  return isUserAdmin(requestingUser, configDir)
}
```

**Sémantique :** il n'existe **qu'un seul rôle applicatif reconnu, `CODAY_ADMIN`**, évalué par
appartenance littérale de chaîne. Toute autre valeur de `groups` est ignorée par le code métier actuel.

**Sites d'appel observés :**

| Fichier | Usage |
|---|---|
| `libs/integrations/http/src/lib/http-config.tools.ts:50` | `if (!isUserAdmin(context.username))` → outils de config HTTP réservés admin |
| `libs/service/src/lib/prompt.service.ts:314` | Seul `CODAY_ADMIN` peut modifier `webhookEnabled` |
| `libs/service/src/lib/scheduler.service.ts:389` | Accès aux schedulers (propriétaire **ou** admin) |
| `libs/service/src/lib/user-groups.ts:58` | `canAccessWebhook` |
| `apps/client/.../sidenav.component.ts:140` | `this.isAdmin = config.groups?.includes('CODAY_ADMIN') ?? false` |
| `apps/client/.../prompt-form|prompt-list` | Même test côté front |

**Exposition vers le client :** `GET /api/config/user` renvoie la config masquée
(`apps/server/src/lib/config.routes.ts`), qui inclut `groups`. La décision admin est donc aussi prise
côté navigateur pour l'affichage, mais **reste ré-évaluée côté serveur** par `isUserAdmin`.

**Caractéristiques :**
- Source de vérité = **fichier local par utilisateur** (`~/.coday/users/*/user.yaml`).
- Aucun scope organisation/namespace/espace de travail.
- Aucune notion d'appartenance à un groupe (seulement un nom de groupe présent).
- Rôle « admin » **global au serveur Coday**, pas à une ressource.
- Non centralisé, non auditable, non révocable à distance.

### 2.2 Modèle AgentOS (namespace-scoped, relationnel, OpenAPI)

AgentOS expose des **UserGroups** rattachés à un **Namespace**, avec des **rôles de membre**.

**Modèles générés (TypeScript)** dans `libs/agentos-api-client/src/lib/model/` :

`user-group-create-request.ts`
```ts
export interface UserGroupCreateRequest {
  adminExternalIds: Set<string>      // sous-ensemble de userExternalIdsToAdd recevant ADMIN
  agentIds: Set<string>              // agents déployés sur le groupe
  name: string
  namespaceId: string
  userExternalIdsToAdd: Set<string>  // clés IdP (externalId)
}
```

`user-group-member.ts`
```ts
export interface UserGroupMember {
  email?: string
  externalId: string  // « Identity-provider key used to add or remove this member »
  firstname?: string
  lastname?: string
  role: UserGroupMemberRoleEnum   // ADMIN | MEMBER
  userId: string
}
export enum UserGroupMemberRoleEnum {
  ADMIN = 'ADMIN',
  MEMBER = 'MEMBER',
}
```

`user-group-search-result.ts`
```ts
export interface UserGroupSearchResult {
  agentIds: Array<string>
  name: string
  namespaceExternalId: string
  namespaceId: string
  userCount: number
  userGroupId: string
}
```

`user-group-update-request.ts` — sémantique **remplacement** pour `adminExternalIds` et `agentIds`,
delta pour `userExternalIdsToAdd` / `userExternalIdsToRemove` :

```ts
export interface UserGroupUpdateRequest {
  adminExternalIds: Set<string>
  agentIds: Set<string>
  name: string
  userExternalIdsToAdd: Set<string>
  userExternalIdsToRemove: Set<string>
}
```

`user-group-summary.ts` : `{ id, name }`.

**Modèles Java / backend** dans `agentos/` :

`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/userGroup/UserGroup.kt`
```kotlin
data class UserGroup(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val name: String,
) : Entity
```

`.../userGroup/UserGroupMember.kt`
```kotlin
/**
 * A user linked to a UserGroup by a `[:MEMBER]` or `[:ADMIN]` relationship.
 * [externalId] is the key the create/update requests use ...;
 * [role] is the user's relation to the group (`ADMIN` = can manage the group, `MEMBER` = read) ...
 */
data class UserGroupMember(
    val userId: UUID,
    val externalId: String,
    val role: String,
    val email: String?,
    val firstname: String?,
    val lastname: String?,
)
```

`.../userGroup/UserGroupController.kt` — l'autorisation est portée par Spring Security et dépend
**du namespace / du groupe**, pas d'un rôle global :

```kotlin
@GetMapping
@PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
override fun findByNamespaceId(@RequestParam namespaceId: UUID): List<UserGroupSearchResult>

@PostMapping
@PreAuthorize("hasPermission(#request.namespaceId, 'Namespace', 'WRITE')")
override fun create(...): UserGroupSearchResult

@PostMapping("/{userGroupId}")
@PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'WRITE')")
override fun update(...): UserGroupSearchResult
```

Le SDK (`agentos/agentos-sdk/.../sdk/api/userGroup/UserGroupApi.kt`) documente explicitement la
matrice d'autorisation : READ namespace/UserGroup, WRITE namespace, WRITE/DELETE UserGroup.

**Rôles ailleurs dans AgentOS** — `.../sdk/api/user/UserMembershipRole.kt` et
`.../sdk/api/membership/MemberItem.kt` :

```kotlin
@field:Pattern(regexp = "ADMIN|MEMBER", message = "role must be ADMIN, MEMBER, or null")
val role: String? = null   // null = révocation de toutes les relations
```

Et `.../sdk/api/namespace/NamespaceRoleEntry.kt` fixe explicitement :
```kotlin
/** [role] must be either `"ADMIN"` or `"MEMBER"`. */
```

**Caractéristiques AgentOS :**
- Un UserGroup appartient à **un** `namespaceId` (multi-tenant).
- Les membres sont identifiés par **`externalId`** (clé IdP) et, en interne, par `userId: UUID`.
- Rôle de membre : **`ADMIN`** (gère le groupe) ou **`MEMBER`** (lecture) — ce sont des rôles
  **sur le groupe**, pas un rôle applicatif global.
- Un groupe peut porter un ensemble d'`agentIds` (agents déployés).
- La sécurité passe par des relations de permissions Spring (`hasPermission(..., 'Namespace'|'UserGroup', ACTION)`),
  fail-closed (`EntityType.fromLabel` renvoie `null` et le code log un WARN).

### 2.3 Comparaison synthétique

| Critère | Coday local | AgentOS |
|---|---|---|
| Unité | `groups: string[]` global | `UserGroup` dans un `Namespace` |
| Stockage | Fichier `~/.coday/users/*/user.yaml` | Graphe/Neo4j via API REST |
| Scope | Global au serveur | `namespaceId` |
| Identifiant utilisateur | email / OS username | `externalId` (IdP) + `userId` UUID |
| Rôle | Chaîne unique reconnue : `CODAY_ADMIN` | `ADMIN` / `MEMBER` **sur le groupe** (+ rôles namespace) |
| Hiérarchie | Aucune | Groupe → agents (`agentIds`), namespace → groupes |
| Autorisation | `isUserAdmin()` lit un fichier | `@PreAuthorize` Spring sur namespace/groupe |
| Audit / révocation | Faible | Modèle relationnel centralisé |

---

## Section 3 — Point de jonction avec le `TrustContext` de la Factory

La Factory possède, elle, une notion d'identité **vérifiée cryptographiquement** et un vocabulaire
de rôles distinct. C'est là que doit s'ancrer le pont Coday/AgentOS.

### 3.1 Vocabulaire : `factory/src/domain/identity/trust-context.ts`

```ts
export const PRINCIPAL_TYPES = Object.freeze(['human', 'service'] as const)
export const AUTHENTICATION_METHODS = Object.freeze(['jwt', 'proxy-signature', 'loopback-dev', 'anonymous'] as const)
export const LOOPBACK_DEV_PRINCIPAL_ID = 'local-dev-user'
```

Deux familles de champs coexistent dans `TrustContext` :

```ts
export interface TrustContext {
  // --- Legacy fields (strict backward compatibility) -----------------------
  namespaceId: string | null
  caseId: string | null
  actorId: string | null
  authorityId: string | null
  runtimeId: string | null
  agentId: string | null
  threadId: string | null
  trustMode: string
  loopback: boolean

  // --- Enriched identity fields --------------------------------------------
  principalId: string | null
  principalType: PrincipalType
  organizationId: string | null
  workstreamId: string | null
  squadId: string | null
  roles: string[]
  scopes: string[]
  correlationId: string | null
  authenticationMethod: AuthenticationMethod
  serviceIdentityId: string | null

  // --- Impersonation / delegation guard (strictly null by default) ---------
  impersonatedBy: string | null
  delegationChain: string[] | null
}
```

Constructeurs et validateur :

```ts
export function createBaseTrustContext(overrides: Partial<TrustContext> = {}): TrustContext { /* ... défauts sûrs ... */ }
export function createAnonymousTrustContext(overrides = {}): TrustContext { /* authenticationMethod: 'anonymous' */ }
export function createLoopbackDevTrustContext(overrides = {}): TrustContext {
  return createBaseTrustContext({
    ...overrides,
    authenticationMethod: 'loopback-dev',
    principalId: overrides.principalId ?? LOOPBACK_DEV_PRINCIPAL_ID,
    scopes: overrides.scopes ?? ['*'],
    loopback: true,
  })
}
export function validateTrustContext(context: unknown): { valid: boolean; errors: string[] } { /* ... */ }
```

`validateTrustContext` **interdit** toute impersonation : `impersonatedBy` et `delegationChain`
doivent rester `null`.

### 3.2 Extraction au bord : `factory/dashboard/http-utils.mjs`

L'extraction est **synchrone, explicite et fail-closed** :

```ts
export function extractTrustContext(req, bindPolicy = {}) {
  // ...
  // 1. JWT — `Authorization: Bearer <token>` verified against the Fake IdP.
  // 2. Signed proxy headers — only trusted when the signature verifies.
  // 3. Fallback — loopback development vs unauthenticated anonymous.
  // 4. Memberships are resolved server-side from the authenticated principal.
  //    Client headers (x-organization-id, x-workstream-id, x-roles, ...) are
  //    deliberately never consulted here.
}
```

Détail des étapes :

```ts
// 1. JWT vérifié
const authorization = header('authorization')
const bearerMatch = authorization ? /^Bearer\s+(.+)$/i.exec(authorization) : null
const jwt = bearerMatch ? bearerMatch[1].trim() : null
if (jwt) {
  const verification = verifyJwt(jwt, fakeIdpSecret)
  if (verification.valid && verification.claims) {
    authenticationMethod = 'jwt'
    principalId = pickPrincipalId(verification.claims)
    principalType = isPrincipalType(verification.claims.principalType) ? verification.claims.principalType : 'human'
    serviceIdentityId = typeof verification.claims.serviceIdentityId === 'string' ? verification.claims.serviceIdentityId : null
    scopes = Array.isArray(verification.claims.scopes) ? verification.claims.scopes.filter(s => typeof s === 'string') : []
  }
}
```

```ts
// 2. En-têtes proxy signés HMAC
if (authenticationMethod === 'anonymous' && hasProxySignature(req?.headers)) {
  const verification = verifyProxyHeaders(req.headers, fakeIdpSecret)
  if (verification.valid && verification.claims) {
    authenticationMethod = 'proxy-signature'
    principalId = verification.claims.principalId
    // ...
  }
}
```

```ts
// 3. Repli
if (authenticationMethod === 'anonymous') {
  if (loopback) {
    authenticationMethod = 'loopback-dev'
    principalId = header('x-factory-actor-id') ?? LOOPBACK_DEV_PRINCIPAL_ID
    scopes = ['*']
  } else {
    authenticationMethod = 'anonymous'
    principalId = null
    scopes = []
  }
}

// 4. Memberships côté serveur uniquement
const membership = resolveMembershipSync(membershipResolver, principalId, principalType)
return { ...legacy, principalId, principalType,
  organizationId: membership.organizationId ?? null,
  workstreamId: membership.workstreamId ?? null,
  squadId: membership.squadId ?? null,
  roles: membership.roles ?? [],
  scopes, correlationId: resolveCorrelationId(req), authenticationMethod, serviceIdentityId,
  impersonatedBy: null, delegationChain: null }
```

**Principe d'immuabilité (essentiel pour B6) :** les en-têtes client `x-organization-id`,
`x-workstream-id`, `x-roles`, `x-proxy-*` **ne sont jamais lus** pour établir l'autorisation. Toute
identité non signée est ignorée. Les memberships sont résolues **après** authentification.

### 3.3 Résolution serveur des memberships : `factory/src/domain/identity/membership-resolver.ts`

```ts
export interface MembershipInfo {
  organizationId: string | null
  workstreamId: string | null
  squadId: string | null
  roles: string[]
}

export const EMPTY_MEMBERSHIP: MembershipInfo = Object.freeze({
  organizationId: null, workstreamId: null, squadId: null, roles: [],
})

export function defaultMembershipFor(principalType: PrincipalType): MembershipInfo {
  return {
    organizationId: 'org-local-dev',
    workstreamId: 'ws-default',
    squadId: null,
    roles: principalType === 'service' ? ['service-runner'] : ['developer'],
  }
}
```

- `LocalDevMembershipResolver` : annuaire en mémoire ; une entrée explicite gagne, sinon le défaut
  synthétique est accordé ; un principal absent ne reçoit rien.
- `MockMembershipResolver` = alias de vocabulaire.
- `resolveMembershipSync` **fail-closed** : si le resolver renvoie une promesse ou une forme invalide,
  on retourne `EMPTY_MEMBERSHIP` (aucun privilège) plutôt que de bloquer.

```ts
export function resolveMembershipSync(resolver, principalId, principalType): MembershipInfo {
  if (!resolver || typeof resolver.resolveMembership !== 'function') return { ...EMPTY_MEMBERSHIP, roles: [] }
  const result = resolver.resolveMembership(principalId, principalType)
  if (isThenable(result) || !isMembershipInfo(result)) return { ...EMPTY_MEMBERSHIP, roles: [] }
  // ... normalisation stricte ...
}
```

### 3.4 Fake IdP : `factory/src/domain/identity/fake-idp.ts`

- **Aucune dépendance externe** : `node:crypto` uniquement, HMAC-SHA256 (HS256).
- JWT OIDC-shaped : `issueJwt` / `verifyJwt` (structure, signature, `exp`, `nbf`, `aud`).
- En-têtes proxy signés : `x-proxy-signature`, `x-proxy-timestamp`, `x-proxy-principal-id`,
  `x-proxy-principal-type`, `x-proxy-service-identity-id`, `x-proxy-scopes` ; fenêtre anti-rejeu
  de 5 minutes (`DEFAULT_PROXY_SIGNATURE_TTL_MS`), TTL JWT 300 s.
- Secret par défaut : `DEFAULT_FAKE_IDP_SECRET = 'coday-fake-idp-dev-secret'`.
- **Seule l'identité du principal est signée** ; les memberships ne transitent jamais dans ces
  en-têtes et sont résolues côté serveur. C'est une garantie structurelle à préserver.

### 3.5 Garde d'administration : le seam B6

`factory/dashboard/http-utils.mjs` :

```ts
/**
 * B5-T2b intentionally does NOT implement a real IdP / role model — that is
 * reserved for B6. This function is the explicit, replaceable seam: B6 will
 * swap the body for an entitlement lookup while every call site stays put.
 */
export function checkAdminAuthorization(trustContext) {
  if (!trustContext || typeof trustContext !== 'object') {
    return { authorized: false, reason: 'MISSING_TRUST_CONTEXT' }
  }
  const roles = Array.isArray(trustContext.roles) ? trustContext.roles : []
  const scopes = Array.isArray(trustContext.scopes) ? trustContext.scopes : []
  const isAdmin = roles.includes('admin') || scopes.includes('admin:*') || scopes.includes('*')
  return isAdmin
    ? { authorized: true, reason: null }
    : { authorized: false, reason: 'INSUFFICIENT_ADMIN_PERMISSIONS' }
}

export function requireAdminRole(trustContext) {
  const check = checkAdminAuthorization(trustContext)
  if (!check.authorized) {
    const error = new Error(`Admin authorization required (${check.reason})`)
    error.statusCode = 403
    error.code = 'FORBIDDEN_ADMIN_REQUIRED'
    error.reason = check.reason
    throw error
  }
  return true
}
```

C'est **le point exact** que B6 doit remplacer par une résolution d'entitlements réelle.
`factory/dashboard/artifact-admin-routes.mjs` rajoute : *« B6 will replace the guard's body with a
real entitlement lookup. »*

### 3.6 Points de contact entre les deux mondes

| Sujet | Coday / AgentOS | Factory |
|---|---|---|
| Identité | email (`cf-access`) / `externalId` (AgentOS) | `principalId` (+ `principalType`) |
| Preuve d'identité | En-tête de proxy non vérifié | JWT HMAC vérifiée ou en-têtes proxy signés |
| Rôles | `CODAY_ADMIN` (global) / `ADMIN｜MEMBER` (groupe) | `roles: string[]` (`admin`, `developer`, `service-runner`, …) |
| Scopes | — | `scopes: string[]` (`admin:*`, `*`, …) |
| Scope/tenant | `namespaceId` (AgentOS) | `organizationId` + `workstreamId` + `squadId` |
| Résolution des droits | lecture fichier / requête AgentOS | `MembershipResolver` côté serveur, fail-closed |
| Impersonation | — | strictement `null` |

---

## Section 4 — Proposition d'intégration & mapping vers les rôles Factory V1

### 4.1 Principe : un « Identity Bridge » au bord de la Factory

La Factory ne doit **jamais** faire confiance à des en-têtes non signés (cf. §3.2). L'intégration
consiste donc à introduire un **pont d'identité** qui traduit une identité Coday/AgentOS en une
identité Factory **signée**, que `extractTrustContext` pourra vérifier.

Deux variantes, la seconde étant cible :

- **V1 (immédiat, sans nouvel IdP) :** un petit service/adaptateur qui, pour une requête Coday,
  lit l'identité déjà résolue (`cf-access-jwt-assertion` / `x-forwarded-email`) puis **émet un JWT
  via le Fake IdP** (`issueJwt`) portant `principalId`, `principalType` et `scopes` — en utilisant
  le même `fakeIdpSecret` que la Factory. Le `MembershipResolver` de la Factory reste la seule
  source des `roles`/`organizationId`.
- **Cible (B6+) :** remplacer progressivement le Fake IdP par un vrai IdP/OIDC, et l'adaptateur par
  une vérification JWKS, sans changer la signature de `extractTrustContext` ni les sites d'appel de
  `checkAdminAuthorization`.

Le composant charnière à implémenter côté Factory est un **`AgentOsMembershipResolver`** (ou
`CodayMembershipResolver`) : une implémentation de `MembershipResolver` qui, à partir du
`principalId` (externalId idP), interroge l'annuaire (AgentOS UserGroups + Namespaces) et retourne
`MembershipInfo` (`organizationId`, `workstreamId`, `squadId`, `roles`).

### 4.2 Mapping vers les rôles Factory V1 (`po/pm`, `dev`, `admin`)

La Factory V1 distingue trois profils fonctionnels. Proposition de table de correspondance :

| Rôle Factory V1 | Signal Coday | Signal AgentOS | Scopes associés proposés | TrustContext résultant |
|---|---|---|---|---|
| **`admin`** | `userConfig.groups` contient `CODAY_ADMIN` | rôle `ADMIN` sur le **Namespace** (ou scope admin global) | `admin:*` | `roles: ['admin']`, `scopes: ['admin:*']` |
| **`dev`** | tout utilisateur authentifié standard (`cf-access`/`x-forwarded-email`) | `MEMBER` sur le namespace concerné | `factory:run`, `factory:read`, … | `roles: ['developer']` |
| **`po/pm`** | groupe dédié dans `groups` (ex. `FACTORY_PO` — nouveau) | appartenance à un UserGroup dédié (ex. groupe « Product ») | `factory:stories:*`, `factory:acceptance` | `roles: ['po']` ou `['pm']` |

**Règles de résolution proposées (ordre) :**

1. Si le principal est `service` → `roles: ['service-runner']` (comportement actuel de
   `defaultMembershipFor`), jamais admin.
2. Sinon, si `CODAY_ADMIN` (Coday) **ou** `ADMIN` namespace (AgentOS) → `roles: ['admin']`.
3. Sinon, si le principal est rattaché à un groupe PO/PM → `roles: ['po']` (ou `['pm']`).
4. Sinon → `roles: ['developer']`.
5. Loopback-dev (développement local non authentifié) → `scopes: ['*']`, `roles: ['developer']`
   par défaut via `LocalDevMembershipResolver` ; **jamais en production distante** (`authenticationMethod`
   doit être `loopback-dev`, `loopback: true`, adresse loopback uniquement).

> `checkAdminAuthorization` accorde déjà l'accès si `roles.includes('admin')` **ou**
> `scopes.includes('admin:*')` **ou** `scopes.includes('*')`. Le mapping `admin` doit donc
> **sur-alimenter volontairement** `roles` (pas seulement un scope) pour rester lisible et pour
> que B6 puisse remplacer la garde sans casser les appelants.

### 4.3 Schéma de flux (texte)

```
                 ┌─────────────────────────────────────────────┐
   Navigateur →  │ Reverse proxy / Cloudflare Access (borde)   │
                 │  - émet cf-access-jwt-assertion (email)     │
                 │  - ou x-forwarded-email                     │
                 └───────────────┬─────────────────────────────┘
                                 │ (1) identité en clair
                                 ▼
                 ┌─────────────────────────────────────────────┐
                 │ Coday server (apps/server)                  │
                 │  resolveUsername() → email / externalId     │
                 │  UserService(~/.coday/users/…/user.yaml)    │
                 │  isUserAdmin() → CODAY_ADMIN                │
                 └───────────────┬─────────────────────────────┘
                                 │ (2) identité + droit Coday
                                 ▼
                 ┌─────────────────────────────────────────────┐
                 │ Identity Bridge (adaptateur)                │
                 │  - émet un JWT signé (Fake IdP / OIDC)      │
                 │  - principalId = externalId stable          │
                 │  - scopes dérivés de CODAY_ADMIN            │
                 └───────────────┬─────────────────────────────┘
                                 │ (3) Authorization: Bearer <jwt>
                                 ▼
                 ┌─────────────────────────────────────────────┐
                 │ Factory HTTP boundary                       │
                 │  extractTrustContext()                      │
                 │    verifyJwt() → principalId, scopes        │
                 │    resolveMembershipSync(resolver, …)       │
                 │      AgentOsMembershipResolver → roles      │
                 │  checkAdminAuthorization() / requireAdminRole│
                 └─────────────────────────────────────────────┘
```

### 4.4 Contraintes de conception à respecter

- **Ne pas lire** `x-forwarded-email` / `cf-access-jwt-assertion` directement dans la Factory :
  ces en-têtes ne sont pas signés du point de vue Factory. Le pont doit produire un JWT signé.
- **`principalId` stable** : utiliser un identifiant pérenne (externalId IdP), jamais l'email nu
  (mutable, réassignable). Aujourd'hui Coday ne stocke que l'email ; ce point doit être tranché (cf. Gap 2).
- **Memberships côté serveur** : `AgentOsMembershipResolver` doit résoudre les rôles à partir de
  l'annuaire de confiance, jamais depuis les en-têtes entrants.
- **Fail-closed** : toute erreur de résolution → `EMPTY_MEMBERSHIP` (aucun rôle), comme
  `resolveMembershipSync` le fait déjà.
- **`serviceIdentityId`** : pour un principal `service`, conserver le contrat Fake IdP existant.

---

## Section 5 — Gaps identifiés, risques et décisions ouvertes (avant B6)

### 5.1 Gaps

**Gap 1 — Vérification de signature JWT/identité**
- *Coday* : `extractEmailFromCfJwt` décode le payload `cf-access-jwt-assertion` **sans vérifier la
  signature** ; `x-forwarded-email` est accepté tel quel. Confiance = bord réseau uniquement.
- *Factory* : exige une preuve cryptographique (`verifyJwt` HS256 ou `verifyProxyHeaders` HMAC) et
  **ignore** tout en-tête non signé.
- ⇒ Un pont signé est **obligatoire** ; sinon toute identité peut être usurpée directement contre la Factory.

**Gap 2 — Identifiants utilisateurs hétérogènes**
- *Coday* : email (`user@example.com`) ou nom d'OS (`alice`).
- *AgentOS* : `externalId` (clé IdP) + `userId: UUID`.
- *Factory* : `principalId` (UUID/identifiant externe stable).
- ⇒ Il manque une table de correspondance stable email/externalId ↔ `principalId`. Risque d'usurpation
  si l'email sert d'identité (réutilisation d'adresse, changement de domaine).

**Gap 3 — Sémantique « groupes » vs « rôles »**
- *Coday* : `groups: string[]` plat, un seul nom significatif (`CODAY_ADMIN`), global.
- *AgentOS* : `UserGroup` namespace-scoped, rôles `ADMIN`/`MEMBER` **sur le groupe**, pas un rôle applicatif.
- *Factory* : `roles: string[]` + `scopes: string[]` + `organizationId/workstreamId/squadId`.
- ⇒ Pas de correspondance 1-1 ; il faut définir une **politique de dérivation** (Section 4.2) et
  distinguer clairement « administrateur du groupe » (AgentOS) de « rôle applicatif Factory ».
- Collision de casse à normaliser : AgentOS `ADMIN`/`MEMBER` (majuscules) vs Factory `admin`/`developer` (minuscules).

**Gap 4 — Multi-tenant / scope**
- *AgentOS* : `namespaceId` (UUID) + `namespaceExternalId`.
- *Factory* : `organizationId` + `workstreamId` + `squadId`.
- ⇒ Aucune équivalence native ; il faut une convention de projection `namespace → organization/workstream`.

**Gap 5 — Source de vérité des rôles Coday**
- Les `groups` vivent dans un **fichier local** (`~/.coday/users/<sanitized>/user.yaml`), non centralisé,
  non auditable, sans révocation. Un utilisateur avec accès au système de fichiers peut s'auto-promouvoir.
- `libs/service/src/lib/user-groups.ts` duplique la logique de `sanitizeUsername` (risque de divergence).

**Gap 6 — Absence d'IdP réel**
- `checkAdminAuthorization` est explicitement un placeholder (`B5-T2b`), remplacé en B6.
- Le Fake IdP (secret partagé en clair `coday-fake-idp-dev-secret`) est un outil de développement,
  inadapté tel quel à la production.

**Gap 7 — Accès anonyme vs loopback**
- Côté Factory, `loopback-dev` accorde `scopes: ['*']` (donc admin via `checkAdminAuthorization`).
- Côté Coday, `--auth` absent ⇒ nom d'OS local, sans notion de « distant ». En l'absence de `--auth`,
  une exposition réseau donnerait un accès mono-utilisateur implicite.
- ⇒ En production, `loopback-dev` doit rester strictement local et `anonymous` ne doit jamais hériter
  de privilèges.

### 5.2 Risques

| # | Risque | Impact | Mitigation proposée |
|---|---|---|---|
| R1 | Usurpation d'identité via en-têtes non signés si le serveur Coday/Factory est joignable directement | Élevé | Network policy (loopback/bind) + pont signé obligatoire |
| R2 | Escalade admin via `groups` éditable localement | Élevé | Source de rôles centralisée (annuaire AgentOS/IdP) |
| R3 | `scopes: ['*']` accordé par défaut à un principal mal classé | Élevé | N'accorder `*` qu'à `loopback-dev` sur adresse loopback ; fail-closed ailleurs |
| R4 | Identité basée sur l'email (mutable) | Moyen | Utiliser un `externalId`/`principalId` stable |
| R5 | Dérive de la duplication de `sanitizeUsername` | Faible | Réutiliser `@coday/utils` partout |
| R6 | Rejeu de credentials | Moyen | Fenêtre TTL déjà présente pour les en-têtes signés ; étendre au JWT |
| R7 | Ambiguïté ADMIN groupe vs admin global | Moyen | Documenter la règle de dérivation et la tester |

### 5.3 Décisions ouvertes à trancher avant codage B6

1. **Identifiant principal canonique** : email, `externalId` AgentOS, ou UUID interne ? Où est stockée
   la correspondance ?
2. **Mécanisme du pont V1** : JWT signé Fake IdP réutilisé, ou nouveau format de header proxy signé ?
   Quel secret/rotation ?
3. **Modèle de rôles Factory V1** : figer le vocabulaire exact (`admin`, `developer`, `po`, `pm`,
   `service-runner`) et la règle de dérivation (§4.2), y compris la casse.
4. **Projection du tenant** : comment `namespaceId`/`namespaceExternalId` (AgentOS) se projette sur
   `organizationId`/`workstreamId`/`squadId` (Factory) ? Convention de nommage ?
5. **Source d'autorité des groupes** : maintient-on `groups` dans `user.yaml` (V1) ou bascule-t-on
   immédiatement sur AgentOS UserGroups / IdP comme source unique ?
6. **Traitement des groupes PO/PM** : nouveau nom de groupe Coday (`FACTORY_PO`/`FACTORY_PM`) ou
   UserGroup AgentOS dédié ? Qui administre ces groupes ?
7. **Politique loopback/anonyme en production** : faut-il désactiver `loopback-dev` hors dev
   (variable d'environnement explicite) et quel comportement pour `anonymous` ?
8. **Sécurité `checkAdminAuthorization`** : faut-il retirer la reconnaissance du scope wildcard `*`
   en dehors du contexte loopback-dev pour éviter une escalade par scope ?
9. **Émission des services** (`principalType: 'service'`) : quels scopes/`serviceIdentityId` pour les
   workers Factory, et comment le pont les distingue-t-il des humains ?
10. **Audit & révocation** : où tracer les décisions de mapping et comment révoquer un utilisateur
    (fichier local non propageable) ?

### 5.4 Ce que B6 devra probablement livrer (non implémenté ici)

- Une implémentation réelle de `MembershipResolver` (ex. `AgentOsMembershipResolver`) alimentée par
  l'annuaire de confiance (AgentOS UserGroups + Namespaces).
- Un remplacement du corps de `checkAdminAuthorization` par une résolution d'entitlements, **sans
  changer les sites d'appel** (`requireAdminRole`).
- Un pont d'identité Coday/AgentOS → Factory produisant un credential **signé**, et la définition
  des identifiants stables (`principalId`).
- Des tests couvrant : dérivation des rôles, fail-closed, non-lecture des en-têtes client,
  loopback vs anonyme, et la non-escalade via scopes.

---

## Annexe A — Index des sources citées

| Thème | Fichier |
|---|---|
| Flag `--auth`, options | `apps/server/src/lib/coday-options-utils.ts` |
| Résolution identité (CF / x-forwarded-email / OS) | `apps/server/src/lib/resolve-username.ts` |
| Tests résolution | `apps/server/src/lib/resolve-username.spec.ts` |
| Garde comptes système, routes | `apps/server/src/server.ts` |
| Routes user | `apps/server/src/lib/user.routes.ts` |
| Config routes (GET/PUT user) | `apps/server/src/lib/config.routes.ts` |
| `UserService`, persistance | `libs/service/src/lib/user.service.ts` |
| Sanitisation | `libs/utils/src/lib/username-utils.ts` |
| Modèle `UserConfig.groups` | `libs/model/src/lib/user-config.ts` |
| `isUserAdmin`, `canAccessWebhook` | `libs/service/src/lib/user-groups.ts` |
| Usages admin (prompt/scheduler/http) | `libs/service/src/lib/prompt.service.ts`, `libs/service/src/lib/scheduler.service.ts`, `libs/integrations/http/src/lib/http-config.tools.ts` |
| Modèles AgentOS (TS générés) | `libs/agentos-api-client/src/lib/model/user-group-*.ts` |
| Modèles AgentOS (Kotlin/SDK) | `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/userGroup/*.kt`, `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/api/userGroup/*.kt` |
| Permissions AgentOS | `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/permissions/EntityType.kt` |
| TrustContext | `factory/src/domain/identity/trust-context.ts` |
| Extraction au bord, garde admin | `factory/dashboard/http-utils.mjs` |
| Résolution memberships | `factory/src/domain/identity/membership-resolver.ts` |
| Fake IdP | `factory/src/domain/identity/fake-idp.ts` |
| Barrel identité | `factory/src/domain/identity/index.ts` |
| Routes admin artefacts (seam B6) | `factory/dashboard/artifact-admin-routes.mjs` |
| Tests identité | `factory/tests/test-identity-trust-context.mjs` |
| Doc de lancement `--auth` | `docs/02-getting-started/launching.md` |

## Annexe B — Glossaire

- **`principalId`** : identifiant du principal authentifié dans le `TrustContext` Factory.
- **`externalId`** : clé fournie par l'IdP, utilisée par AgentOS pour ajouter/retirer un membre.
- **`namespaceId`** : tenant AgentOS ; les UserGroups y sont rattachés.
- **`CODAY_ADMIN`** : unique nom de groupe reconnu par Coday pour le rôle admin applicatif.
- **`MembershipResolver`** : composant Factory résolvant `organizationId/workstreamId/squadId/roles`
  côté serveur à partir du `principalId`.
- **`loopback-dev`** : méthode d'authentification de développement local (scopes `['*']`).
- **`checkAdminAuthorization` / `requireAdminRole`** : point d'autorisation admin unique, seam remplacé en B6.
