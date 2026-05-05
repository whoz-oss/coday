# Migration: Coday TS `~/.coday/user.yml` → AgentOS REST API

This document describes how to migrate personal configuration from the Coday TypeScript
`~/.coday/user.yml` file to AgentOS user-level overlays.

## Overview

Coday TS stores per-user configuration in `~/.coday/user.yml` (or `~/.coday/users/{username}/user.yaml`).
AgentOS replaces this with REST-managed user-level overlays scoped to the authenticated user.

The migration covers two layers:

| Layer | Coday TS | AgentOS mode |
|---|---|---|
| User-global AI providers | `ai[*]` | `POST /api/user-ai-providers` (no `namespaceId`) |
| User-global AI models | `ai[*].models[*]` | `POST /api/user-ai-models` (no `namespaceId`) |
| Per-project integrations | `projects.{name}.integration.{type}` | `POST /api/user-integration-configs` (with `namespaceId`) |

## Mapping Table

```
~/.coday/user.yml block                       AgentOS endpoint                mode
─────────────────────────────────────────────────────────────────────────────────
ai[*]                                         POST /api/user-ai-providers     user-global
ai[*].models[*]                               POST /api/user-ai-models        user-global
projects.{ns}.integration.{type}             POST /api/user-integration-configs  user×namespace
```

### Field mapping: AI Provider

| `user.yml` field | AgentOS field | Notes |
|---|---|---|
| `name` | `name` | Provider identifier |
| `apiKey` | `apiKey` | Stored encrypted |
| `url` | `baseUrl` | Optional custom endpoint |
| *(derived)* | `apiType` | Inferred from name: `Anthropic`, `OpenAI`, `Gemini` |

### Field mapping: AI Model

| `user.yml` field | AgentOS field | Notes |
|---|---|---|
| `name` | `apiModelName` | Model API name |
| `alias` | `alias` | Human-readable alias |
| `temperature` | `temperature` | Optional float |
| `contextWindow` | `maxTokens` | Optional integer |
| *(parent provider)* | `aiProviderId` | ID from provider migration |

### Field mapping: Integration Config

| `user.yml` field | AgentOS field | Notes |
|---|---|---|
| `projects.{ns}.integration.{type}` | `name` + `integrationType` | `name = type`, `integrationType = type.toUpperCase()` |
| `projects.{ns}.integration.{type}.*` | `parameters` | Full parameter object |
| *(namespace lookup)* | `namespaceId` | Resolved via `GET /api/namespaces?name={ns}` |

## curl Examples

### 1. Create a user-global AI provider (Anthropic)

```bash
curl -X POST http://localhost:8080/api/user-ai-providers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "anthropic",
    "apiType": "Anthropic",
    "apiKey": "sk-ant-..."
  }'
```

### 2. Create a user-global AI model

```bash
PROVIDER_ID=<id-from-step-1>
curl -X POST http://localhost:8080/api/user-ai-models \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "aiProviderId": "'"$PROVIDER_ID"'",
    "apiModelName": "claude-opus-4-7",
    "alias": "default",
    "temperature": 0.7
  }'
```

### 3. Create a user-global AI provider (OpenAI)

```bash
curl -X POST http://localhost:8080/api/user-ai-providers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "openai",
    "apiType": "OpenAI",
    "apiKey": "sk-..."
  }'
```

### 4. Create a user-global AI model under OpenAI provider

```bash
OPENAI_PROVIDER_ID=<id-from-step-3>
curl -X POST http://localhost:8080/api/user-ai-models \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "aiProviderId": "'"$OPENAI_PROVIDER_ID"'",
    "apiModelName": "gpt-4o",
    "alias": "gpt4"
  }'
```

### 5. Create a user×namespace integration config (JIRA)

First, resolve the namespace ID:
```bash
NS_ID=$(curl -s "http://localhost:8080/api/namespaces?name=my-project" \
  -H "Authorization: Bearer $TOKEN" | jq -r '.[0].id')
```

Then create the override:
```bash
curl -X POST http://localhost:8080/api/user-integration-configs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "namespaceId": "'"$NS_ID"'",
    "name": "jira",
    "integrationType": "JIRA",
    "parameters": {
      "url": "https://my-company.atlassian.net",
      "token": "my-personal-token",
      "userEmail": "me@my-company.com"
    }
  }'
```

### 6. Create a user×namespace integration config (GitHub)

```bash
curl -X POST http://localhost:8080/api/user-integration-configs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "namespaceId": "'"$NS_ID"'",
    "name": "github",
    "integrationType": "GITHUB",
    "parameters": {
      "token": "ghp_my-personal-token"
    }
  }'
```

## Migration Script

The script `agentos/scripts/migrate-user-yml.ts` automates the full migration:

```bash
npx ts-node agentos/scripts/migrate-user-yml.ts \
  --user-yml ~/.coday/user.yml \
  --base-url http://localhost:8080 \
  --token <bearer-token>
```

### Namespace mapping

If your Coday TS project names differ from the AgentOS namespace UUIDs (or if the namespace
cannot be auto-discovered via `GET /api/namespaces?name=...`), map them explicitly:

```bash
npx ts-node agentos/scripts/migrate-user-yml.ts \
  --user-yml ~/.coday/user.yml \
  --base-url http://localhost:8080 \
  --token <bearer-token> \
  --namespace-mapping my-project=550e8400-e29b-41d4-a716-446655440000 \
  --namespace-mapping other-project=6ba7b810-9dad-11d1-80b4-00c04fd430c8
```

`--namespace-mapping` can be repeated for each project.

### Obtaining a bearer token

```bash
# Example: obtain a token via the AgentOS login endpoint
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"me@example.com","password":"..."}' \
  | jq -r '.token')
```

### Idempotency

The script is idempotent: entries that already exist return HTTP 409 and are skipped with a
warning. Running it twice is safe.

### Prerequisites

The script requires `js-yaml` to parse YAML:

```bash
# From the repo root
pnpm add -D js-yaml @types/js-yaml
# Or ad-hoc
npm install js-yaml
```

## Known Limitations

- **MCP server configurations** (`projects.{ns}.mcp` in `user.yml`) are **not migrated**. AgentOS
  MCP support is out of scope for this migration and must be configured separately when available.
- **Namespace auto-discovery** requires that the namespace `name` in `user.yml` matches the AgentOS
  namespace `name` exactly. Use `--namespace-mapping` when names differ.
- **Provider API type inference** is heuristic (based on provider `name` containing "anthropic",
  "openai", or "gemini"). If your provider name is non-standard, set `apiType` manually after
  migration via `PATCH /api/user-ai-providers/{id}`.
- **Encrypted secrets**: `apiKey` and token values are stored encrypted at rest in AgentOS.
  The plaintext values in `user.yml` are sent over HTTPS and never logged.

## See Also

- [User-level overlays pattern](user-level-overlays.md) — 3-tier resolution, merge semantics, REST API
- [Entity schema](study/entity-schema.md) — full entity model including triple-mode invariants
