# User-Level Overlays

User-level overlays let individual users personalise `IntegrationConfig` and `AiProvider`
without modifying namespace defaults.

Configs are resolved via a 3-tier field-by-field merge (lowest → highest):
1. **Namespace-shared** — `(namespaceId=N, userId=null)`
2. **User-global** — `(namespaceId=null, userId=U)`
3. **User × namespace** — `(namespaceId=N, userId=U)`

Missing layers are skipped; if none exist, `ConfigNotFoundException` is thrown.

Scope is inferred from `(body.namespaceId, body.userId)` on creation and from
`?namespaceId=&userId=me` query params on list. `userId` only accepts `me`.
