# AgentOS membership resolver

## What changed

Milestone B / wave B6 / task B6-T1 adds an AgentOS directory-backed implementation of Factory’s `MembershipResolver`. The resolver is deliberately transport-agnostic: callers inject an `AgentOsDirectoryClient`, so the implementation performs no network I/O itself. Directory entries are projected into Factory membership data as follows:

- `ADMIN` (case-insensitive) becomes `admin`.
- `MEMBER`, `DEV`, and `DEVELOPER` become `dev`; other role/group tokens are trimmed, lowercased, and deduplicated.
- `namespaceId` takes precedence over `namespaceExternalId` and becomes `workstreamId`.
- `organizationId` comes from the constructor, then `FACTORY_ORGANIZATION_ID`, then `org-local-dev`.
- `squadId` is always `null`.
- `service` principals bypass the directory and receive only `service-runner`, with the configured organization and no workstream by default; directory-reported admin membership cannot affect them.

Invalid, missing, unknown, or unavailable directory responses fail closed to `EMPTY_MEMBERSHIP`; resolution does not throw on these paths.

## Caching and integration

`AgentOsMembershipResolver` caches memberships by principal ID in memory. The TTL is configurable through `cacheTtlMs` or `FACTORY_MEMBERSHIP_CACHE_TTL_MS`, defaulting to 60 seconds; zero or a negative value disables caching. Expired entries are re-fetched. `primeCache`, `getCachedMembership`, `clearCache`, and `cacheSize` support warming and invalidation, and cached values are cloned to avoid mutable-state leakage.

The resolver supports both synchronous and promise-returning directory clients. Cache hits, service principals, and synchronous client results return immediately. For an asynchronous client, an uncached `resolveMembership` returns a promise; `resolveMembershipSync` (and the existing boundary helper) fails closed until the result has been pre-warmed, after which the cache hit is synchronous.

## Files

- `factory/src/domain/identity/agentos-membership-resolver.ts` contains the directory-user/client contracts, role normalization helpers, options, cache, fail-closed projection, and resolver class.
- `factory/src/domain/identity/index.ts` re-exports the new resolver and its public types/helpers.
- `factory/tests/test-agentos-membership-resolver.mjs` provides an offline in-memory fake directory client and covers role mapping/casing, namespace and organization projection, service isolation, sync/async behavior, cache hits and TTL expiry, cache helpers, and failure handling.
- `specs/d0512027_agentos_membership_resolver.md` records the implementation plan, requirements, scope boundaries, and verification commands.

## Verification

Run the dedicated offline suite from the repository root:

```sh
node factory/tests/test-agentos-membership-resolver.mjs
```

The test file also exercises the synchronous boundary behavior: async directory lookups are expected to be resolved once before a synchronous request path relies on the cache.
