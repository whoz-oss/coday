# User-Level Overlays

This document describes the user-level overlay pattern for `IntegrationConfig`, `AiProvider`, and
`AiModel` in AgentOS.

## Overview

AgentOS configurations are namespace-scoped by default: every integration, provider, and model
belongs to a namespace and is shared by all members of that namespace. User-level overlays let
individual users **personalise** these configurations without modifying the shared namespace
defaults.

An overlay does not replace a namespace config — it is **merged** on top of it at runtime, field by
field, using the user-supplied values where present and falling back to the namespace-shared values
for everything else.

## 3-Tier Resolution

Resolution follows a strict priority cascade:

```
Tier 1 (lowest):  namespace-shared          (namespaceId=N, userId=null)
Tier 2 (middle):  user-global               (namespaceId=null, userId=U)
Tier 3 (highest): user × namespace          (namespaceId=N, userId=U)
```

```
       ┌────────────────────────────────────────────┐
       │               resolve(N, U, name)          │
       └────────────────────────────────────────────┘
                             │
           ┌─────────────────┼─────────────────┐
           ▼                 ▼                 ▼
   findByTriple        findByTriple       findByTriple
   (N, null, name)   (null, U, name)     (N, U, name)
   namespace-shared   user-global       user×namespace
       [base]            [L2]               [L3]
           │                 │                 │
           └────────────fold─┴────────────fold─┘
                      (L2 wins where non-null)
                      (L3 wins where non-null)
                             │
                             ▼
                      merged config
```

The fold is applied left-to-right:
1. Start with the namespace-shared config (base).
2. Apply the user-global layer on top — each field wins only when the override is non-null/non-blank.
3. Apply the user×namespace layer on top of step 2 — same semantics.

If a layer is absent (e.g. the user has no user-global override), that tier is skipped.

## Two Modes of Overlay

| Mode | `namespaceId` | `userId` | Scope |
|---|---|---|---|
| User-global | `null` | `U` | Applies to all namespaces for user U |
| User × namespace | `N` | `U` | Applies only when running in namespace N |

**Precedence**: user×namespace wins over user-global, which wins over namespace-shared.

**Dormancy**: a user×namespace overlay for namespace `N2` is silently ignored when resolving for
namespace `N1 ≠ N2`. The overlay is "dormant" — it does not cause errors, it simply does not apply.

## Merge Rules (field-by-field)

### IntegrationConfig

| Field | Merge rule |
|---|---|
| `id`, `metadata`, `namespaceId`, `userId`, `name`, `integrationType` | Always from base (immutable identity) |
| `parameters` | Override map wins (shallow replace); `null` parameters → base preserved |

### AiProvider

| Field | Merge rule |
|---|---|
| `id`, `metadata`, `namespaceId`, `userId`, `name` | Always from base |
| `apiType` | Override wins |
| `baseUrl` | Override wins when non-null and non-blank; else base |
| `apiKey` | Override wins when non-null and non-blank; else base (never blanked out accidentally) |
| `description` | Override wins when non-null; else base |

### AiModel

| Field | Merge rule |
|---|---|
| `id`, `metadata`, `namespaceId`, `userId`, `aiProviderId` | Always from base |
| `alias` | Preserved from base (identity key for reconciliation) |
| `apiModelName` | Override wins when non-blank; else base |
| `temperature` | Override wins when non-null; else base |
| `maxTokens` | Override wins when non-null; else base |
| `priority` | Override wins when `!= 0` (0 means "unset"); else base |
| `description` | Override wins when non-null; else base |

**`apiKey` safety**: if a user overlay for `AiProvider` has `apiKey = null` or `""`, the base
`apiKey` is preserved. This prevents a misconfigured overlay from silently removing authentication.

## Reconciliation Key

Each entity is identified by a **name** within its resolution tier:

| Entity | Reconciliation key |
|---|---|
| IntegrationConfig | `name` (e.g. `"jira"`, `"github"`) |
| AiProvider | `name` (e.g. `"anthropic"`, `"openai-company"`) |
| AiModel | `alias` when non-null; `apiModelName` as fallback |

**AiModel alias semantics**: the reconciliation uses `alias` as the primary key because the alias
is the identity visible to agents (`"default"`, `"fast"`, etc.). An override with a different alias
does not match the base model — it is dormant.

## Runtime Activation

### Tools (IntegrationConfig)

`ToolRegistryService.resolveToolsForRun(namespaceId, userId)` enumerates all distinct `name` values
visible to user `U` in namespace `N`:

1. `integrationConfigService.findByNamespaceShared(N)` — namespace-shared configs (`userId IS NULL`)
2. `integrationConfigService.findByUserId(U)` filtered to `namespaceId IS NULL OR namespaceId == N`

For each unique name, `configReconciliationService.resolve(N, U, name)` returns the merged config.
The resolved `parameters` are passed to `ToolPlugin.provideTools(parameters, name)` — the plugin
receives no indication that reconciliation occurred.

If `ConfigReconciliationService.resolve(...)` throws `ConfigNotFoundException` for a given name,
the error is caught, a warning is logged, and that name is skipped — the run continues with the
remaining tools.

### LLM (AiProvider + AiModel)

`AgentServiceImpl.resolveModelPair(name, namespaceId, userId)`:
1. Resolves the base `AiModel` via `aiModelService.findAiModel(namespaceId, name)` (alias-first,
   `apiModelName` fallback — Epic 4 lookup).
2. Applies `aiModelReconciliationService.resolve(namespaceId, userId, reconciliationName)` to get
   the merged model.
3. Resolves the base `AiProvider` via `aiProviderService.getById(baseModel.aiProviderId)`.
4. Applies `aiProviderReconciliationService.resolve(namespaceId, userId, baseProvider.name)` to get
   the merged provider.
5. Passes the merged pair to `ChatClientProvider.getChatClient(mergedModel, mergedProvider)`.

The `ChatClientProvider` has no knowledge of the reconciliation. The effective `apiKey` comes from
the merged provider.

## Per-Run Cache

Each agent run creates a `RunReconciliationCache` (one per run, not shared across runs).
The cache memoises resolved configs by `(name, entityType)` — at most 3 Neo4j `findByTriple` calls
per unique name per run, regardless of how many tools or LLM invocations happen.

Cache semantics:
- Scope: strictly one run; destroyed with the run context.
- Not thread-safe by design — agent runs are single-threaded.
- Not instantiated for runs without a `userId` (Epic 4 legacy path — no reconciliation needed).

## Namespace-Scope Listings

`GET /api/integration-configs/by-parentId/{N}` (and equivalent for providers and models) returns
**only** entries with `userId IS NULL`. User overlays are never visible to other namespace members
through listing endpoints.

This enforces AR8: user personalisations are private by default.

## Examples

### Example 1 — User-global AiProvider override (personal API key)

A namespace-shared `AiProvider` `"anthropic"` has a company `apiKey`. User U wants to use a
personal key:

```bash
# User-global override — no namespaceId
curl -X POST http://localhost:8080/api/user-ai-providers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"anthropic","apiType":"Anthropic","apiKey":"sk-ant-personal-..."}'
```

Result for user U: resolved `AiProvider.apiKey` = personal key. All other users still use company key.

### Example 2 — User×namespace AiProvider override (project-specific API key)

```bash
curl -X POST http://localhost:8080/api/user-ai-providers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"namespaceId":"<ns-uuid>","name":"anthropic","apiType":"Anthropic","apiKey":"sk-ant-proj-..."}'
```

Result: only when running in namespace `<ns-uuid>`, user U uses the project key.

### Example 3 — User-global AiModel override (custom temperature)

```bash
curl -X POST http://localhost:8080/api/user-ai-models \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"aiProviderId":"<provider-id>","apiModelName":"claude-opus-4-7","alias":"default","temperature":0.2}'
```

Result: when user U runs agents, the `"default"` model uses temperature 0.2 regardless of the
namespace default.

### Example 4 — User×namespace AiModel override (different model variant)

```bash
curl -X POST http://localhost:8080/api/user-ai-models \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"namespaceId":"<ns-uuid>","aiProviderId":"<provider-id>","apiModelName":"claude-haiku-4-5-20251001","alias":"default"}'
```

Result: in namespace `<ns-uuid>`, user U's `"default"` model resolves to Haiku instead of Opus.

### Example 5 — User-global IntegrationConfig override (personal JIRA token)

```bash
curl -X POST http://localhost:8080/api/user-integration-configs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"jira","integrationType":"JIRA","parameters":{"token":"my-personal-atlassian-token","userEmail":"me@example.com"}}'
```

Result: JIRA tools run with the user's personal token for all namespaces.

### Example 6 — User×namespace IntegrationConfig override (project-specific GitHub token)

```bash
NS_ID=<ns-uuid>
curl -X POST http://localhost:8080/api/user-integration-configs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"namespaceId":"'"$NS_ID"'","name":"github","integrationType":"GITHUB","parameters":{"token":"ghp_project-scoped-token"}}'
```

Result: in namespace `<ns-uuid>`, GitHub tools use the project-specific token for user U.

## REST API Summary

| Endpoint | Method | Description |
|---|---|---|
| `/api/user-integration-configs` | POST | Create user overlay |
| `/api/user-integration-configs` | GET | List user's overlays |
| `/api/user-integration-configs/{id}` | GET | Get overlay by ID |
| `/api/user-integration-configs/{id}` | PUT | Update overlay |
| `/api/user-integration-configs/{id}` | DELETE | Delete overlay |
| `/api/user-ai-providers` | POST | Create user AI provider overlay |
| `/api/user-ai-providers` | GET | List user's provider overlays |
| `/api/user-ai-providers/{id}` | GET/PUT/DELETE | CRUD |
| `/api/user-ai-models` | POST | Create user AI model overlay |
| `/api/user-ai-models` | GET | List user's model overlays |
| `/api/user-ai-models/{id}` | GET/PUT/DELETE | CRUD |

See `agentos/openapi/agentos-openapi.yaml` for the full schema.

## See Also

- [Migration from `~/.coday/user.yml`](migration-coday-ts-user-config.md) — migrate personal Coday TS config to AgentOS overlays
- [Entity schema](study/entity-schema.md) — full entity model with triple-mode invariants
