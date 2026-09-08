# User-Level Overlays

User-level overlays let individual users personalise `IntegrationConfig` and `AiProvider`
without modifying namespace defaults.

Configs are resolved via a 4-tier field-by-field merge (lowest → highest):
1. **Platform** — `(namespaceId=null, userId=null)`
2. **User-global** — `(namespaceId=null, userId=U)`
3. **Namespace-shared** — `(namespaceId=N, userId=null)`
4. **User × namespace** — `(namespaceId=N, userId=U)`

Namespace-shared deliberately overrides user-global so that a namespace admin can enforce a config
over user preferences; user × namespace lets the user restore a personal override for that namespace.
Missing layers are skipped; if none exist, `ConfigNotFoundException` is thrown.

Integration types listed in `agentos.integrations.user-scope-denied-types` (default `HTTP_API`,
`MCP_STDIO`, `MCP_HTTP`) cannot be created or updated in the two user scopes: an overlay inherits the
shared `authSettingName`, and for a network-reaching type that would let a user redirect the shared
credential to a host they control. See `docs/plugin-system.md`.

Scope is inferred from `(body.namespaceId, body.userId)` on creation and from
`?namespaceId=&userId=me` query params on list. `userId` only accepts `me`.
