# User-Level Overlays

User-level overlays let individual users personalise `IntegrationConfig`, `AiProvider`, and `AiModel`
configurations without modifying the shared namespace defaults.

## 3-Tier Precedence

Resolution follows a strict priority cascade applied left-to-right via field-by-field merge:

| Tier | Layer              | Lookup triple                 | Precedence |
|------|--------------------|-------------------------------|------------|
| 1    | Namespace-shared   | `(namespaceId=N, userId=null)` | lowest     |
| 2    | User-global        | `(namespaceId=null, userId=U)` | middle     |
| 3    | User × namespace   | `(namespaceId=N, userId=U)`    | highest    |

Each present layer is folded onto the accumulator; missing layers are skipped.
If all three layers are absent, `ConfigNotFoundException` is thrown (fail-closed).

## Endpoint Layout

| Resource           | Base path                    | Supported methods          |
|--------------------|------------------------------|----------------------------|
| IntegrationConfig  | `/api/integration-configs`   | GET, POST, PUT, DELETE     |
| AiProvider         | `/api/ai-providers`          | GET, POST, PUT, DELETE     |
| AiModel            | `/api/ai-models`             | GET, POST, PUT, DELETE     |

Scope is inferred from `(body.namespaceId, body.userId)` on POST (Decision 15) and from
`?namespaceId=&userId=me` query params on GET list. `userId` only accepts the `me` sentinel.
