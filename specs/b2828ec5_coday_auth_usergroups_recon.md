# Plan: Coday Auth & User Groups Reconnaissance and Proposal Document

## Goal
Produce `docs/coday-auth-usergroups-recon.md` as an architectural reconnaissance and integration proposal document without modifying any application code or existing codebase files.

## Summary of Analysis & Strategy

The document must thoroughly cover 5 key sections with concrete file citations, code snippets, and structural comparison between Coday (Node.js/TS Express server), AgentOS (Java/Spring Boot OpenAPI spec), and Factory TrustContext (Node.js HTTP boundary).

### Key Architectural Findings:
1. **Coday Authentication Layer**:
   - `apps/server/src/server.ts` handles auth options via `--auth` flag parsed by `parseCodayOptions()`.
   - Resolution order in `apps/server/src/lib/resolve-username.ts`:
     1. Cloudflare Access JWT via `cf-access-jwt-assertion` header (decodes base64 payload to extract `email` claim; skips cryptographic signature check as edge proxy validates).
     2. `x-forwarded-email` header set by upstream reverse proxy.
     3. Fallback when `--auth` is false: local OS username (`os.userInfo().username`).
     4. Throws error if `--auth` is enabled and neither header is present, or if resolved user is in `FORBIDDEN_USERNAMES`.
   - User config persistence: `~/.coday/users/<sanitized_username>/user.yaml` managed by `UserService` (`libs/service/src/lib/user.service.ts`). Note sanitization (`username.replace(/[^a-zA-Z0-9]/g, '_')`).
   - Delegation: Authentication is delegated entirely to reverse proxies (Cloudflare Access / OAuth Proxy). Token verification is edge-only.

2. **Coday User Groups & Roles**:
   - Local simple model (`libs/service/src/lib/user-groups.ts`): Reads `userConfig.groups` array from `user.yaml`. Checks if `CODAY_ADMIN` is present (`isUserAdmin()`). Used for webhook access and prompt admin features.
   - AgentOS User Groups (`libs/agentos-api-client/src/lib/model/user-group-*`):
     - OpenAPI models: `UserGroupCreateRequest`, `UserGroupUpdateRequest`, `UserGroupSearchResult`, `UserGroupMember`, `UserGroupSummary`.
     - Scoped per Namespace (`namespaceId`).
     - Roles within group: `UserGroupMemberRoleEnum` (`ADMIN` or `MEMBER`).
     - Grants access to specific agents (`agentIds`).

3. **Point of Junction with Factory TrustContext**:
   - Factory TrustContext (`factory/src/domain/identity/trust-context.ts`, `factory/dashboard/http-utils.mjs`):
     - Enriched identity: `principalId`, `principalType` ('human'|'service'), `organizationId`, `workstreamId`, `squadId`, `roles`, `scopes`, `authenticationMethod` ('jwt'|'proxy-signature'|'loopback-dev'|'anonymous').
     - Identity extraction in `http-utils.mjs` (`extractTrustContext`):
       - First checks `Authorization: Bearer <jwt>` against Fake IdP (`factory/src/domain/identity/fake-idp.ts`).
       - Next checks proxy signatures (`x-proxy-signature`).
       - Fallback to loopback-dev (`local-dev-user`) or anonymous.
       - Server-side membership resolution via `MembershipResolver` (`membership-resolver.ts`). Client headers for org/roles/workstreams are strictly ignored.
   - Governance check: `checkAdminAuthorization(trustContext)` / `requireAdminRole(trustContext)` for admin operations.

4. **Integration Proposal & Mapping to Factory V1 Roles**:
   - Mapping Coday / AgentOS identity to Factory TrustContext roles:
     - `po/pm` -> Product Manager / Product Owner role (drives stories, acceptance).
     - `dev` -> Developer role (runs workflows, code generation, story execution).
     - `admin` -> Admin role (purges, legal hold, system operations).
   - Mapping strategy:
     - Coday `CODAY_ADMIN` / AgentOS Namespace ADMIN -> Factory `admin` role.
     - Regular Coday users / AgentOS namespace MEMBER -> Factory `dev` or `po/pm` based on group memberships or workstream assignment.
     - Unauthenticated loopback dev -> Factory `loopback-dev` with wildcard `*` scope (or default `developer` role via `LocalDevMembershipResolver`).

5. **Identified Gaps, Risks, and Open Decisions**:
   - Signature verification gap: Coday trusts `cf-access-jwt-assertion` payload without verifying signature (assumes trusted reverse proxy). Factory requires HMAC-SHA256 signature or Fake IdP JWT.
   - User identity identifier discrepancy: Coday uses emails (`user@example.com`) or OS usernames (`alice`), while Factory TrustContext uses principal UUIDs / external IDs (`principalId`).
   - Group vs Role semantics: Coday `user.yaml` has flat string `groups: ["CODAY_ADMIN"]`. AgentOS has namespace-scoped user groups with member roles (`ADMIN`, `MEMBER`). Factory uses `organizationId`, `workstreamId`, `squadId`, `roles` (`admin`, `developer`, `service-runner`).
   - Multi-tenant / Namespace alignment: AgentOS uses `namespaceId`. Factory uses `organizationId` / `workstreamId` / `squadId`.

---

## Plan Steps

### Step 1: Draft Structure for `docs/coday-auth-usergroups-recon.md`
Create the document at `docs/coday-auth-usergroups-recon.md` in French (as per prompt preference / prompt context).

The document structure will be:
- **Title**: Document de Reconnaissance Architecturelle et Proposition d'Intégration : Authentification et Groupes d'Utilisateurs Coday / AgentOS / Factory TrustContext
- **Section 1 : Authentification Coday (Auth Layer & Identity Resolution)**
  - Analyse détaillée de `apps/server/src/server.ts` et `apps/server/src/lib/resolve-username.ts`.
  - Analyse du flag `--auth` dans `coday-options-utils.ts`.
  - Ordre de résolution de l'identité :
    1. Cloudflare Access JWT (`cf-access-jwt-assertion`). Extraction du claim `email` via `extractEmailFromCfJwt`. Explication de la non-vérification de la signature à ce niveau.
    2. En-tête `x-forwarded-email`.
    3. Fallback OS username (`os.userInfo().username`).
  - Validation de sécurité : `FORBIDDEN_USERNAMES` dans `server.ts`.
  - Persistance de la configuration utilisateur : `~/.coday/users/<sanitized>/user.yaml` géré par `UserService` (`libs/service/src/lib/user.service.ts`). Mécanisme de sanitisation (`sanitizeUsername`).
  - Délégation de l'authentification : proxy inverse vs AgentOS vs serveur Coday Express.
- **Section 2 : Groupes d'Utilisateurs et Rôles dans Coday et AgentOS**
  - Modèle local simple Coday (`libs/service/src/lib/user-groups.ts`) : `isUserAdmin`, `canAccessWebhook`, rôle système `CODAY_ADMIN`.
  - Modèle AgentOS OpenAPI / Java backend (`libs/agentos-api-client/src/lib/model/user-group-*`) :
    - Structuration par Namespace (`namespaceId`).
    - DTOs : `UserGroupCreateRequest`, `UserGroupUpdateRequest`, `UserGroupSearchResult`, `UserGroupMember`.
    - Rôles de membres dans un groupe : `UserGroupMemberRoleEnum` (`ADMIN`, `MEMBER`).
    - Association avec les agents (`agentIds`).
  - Comparaison synthétique entre le modèle local Coday et le modèle AgentOS.
- **Section 3 : Point de Jonction avec le TrustContext de la Factory**
  - Analyse de `factory/src/domain/identity/trust-context.ts`, `factory/dashboard/http-utils.mjs`, `factory/src/domain/identity/membership-resolver.ts`, `factory/src/domain/identity/fake-idp.ts`.
  - Structure de `TrustContext` : Champs hérités (legacy) vs champs enrichis (`principalId`, `principalType`, `organizationId`, `workstreamId`, `squadId`, `roles`, `scopes`, `authenticationMethod`).
  - Logique d'extraction (`extractTrustContext`) : vérification JWT / Proxy-signature HMAC, fallback loopback/anonymous.
  - Principe d'immuabilité et résolution côté serveur (`resolveMembershipSync`) : interdiction stricte de lire les en-têtes d'organisation/rôle envoyés par le client.
  - Contrôle d'accès administrateur : `checkAdminAuthorization` et `requireAdminRole`.
- **Section 4 : Proposition d'Intégration & Mapping vers les Rôles Factory V1**
  - Stratégie de pont d'identité (Identity Bridge) entre Coday/AgentOS et Factory TrustContext.
  - Mapping vers les rôles Factory V1 (`po/pm`, `dev`, `admin`) :
    - `admin` <- `CODAY_ADMIN` (Coday) / AgentOS Namespace ADMIN / Scopes `admin:*`.
    - `dev` <- Utilisateur authentifié standard Coday / AgentOS Namespace MEMBER / Dev local loopback.
    - `po/pm` <- Rôle spécifique assigné via `MembershipResolver` ou groupe dédié AgentOS.
  - Schéma de flux d'authentification et de propagation de contexte de confiance.
- **Section 5 : Gaps Identifiés, Risques et Décisions Ouvertes (avant codage B6)**
  - Gap 1 : Vérification de signature JWT (Cloudflare unverified vs HMAC Fake IdP / OIDC verification in Factory).
  - Gap 2 : Identification des utilisateurs (Email / OS Name vs Principal UUID).
  - Gap 3 : Sémantique des modèles de données (Flat `groups` array vs Namespace User Groups vs Org/Workstream/Squad/Roles).
  - Gap 4 : Traitement des accès anonymes vs loopback en environnement de production / multi-tenant.
  - Liste des décisions ouvertes à trancher avant l'étape B6.

### Step 2: Write `docs/coday-auth-usergroups-recon.md`
Generate the comprehensive document with code snippets and citations.

### Step 3: Verify execution and document status
Ensure no build or test scripts are broken and the file is created cleanly.

---

## Verification
- Confirm `docs/coday-auth-usergroups-recon.md` exists and contains all required 5 sections with concrete code paths and snippets.
- Verify `git status` shows no untracked/modified code files outside of `docs/coday-auth-usergroups-recon.md` and `specs/`.
