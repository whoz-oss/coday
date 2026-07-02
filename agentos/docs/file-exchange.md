# File Exchange

A per-case and per-namespace file store that both users (via REST) and agents (via file tools)
can read and write, scoped by the existing Case / Namespace permissions.

## Storage Layout

All exchange files live under a single mount root, partitioned by namespace and scope. Case files
are **date-sharded** by the case creation date (UTC) to keep per-directory fan-out bounded:

```
<mountRoot>/<namespaceId>/cases/<YYYY>/<MM>/<DD>/<caseId>/...   # case-scoped (read/write)
<mountRoot>/<namespaceId>/shared/...                           # namespace-shared (read; write for namespace admins)
```

The shard date is the case's immutable `created` timestamp (carried on `CaseRuntime.caseCreatedAt`),
so the REST path and the agent tool path always resolve to the same directory.

## Configuration

Mount root, bound from the `agentos.exchange` prefix (Spring relaxed binding):

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `agentos.exchange.mount-root` | `AGENTOS_EXCHANGE_MOUNT_ROOT` | `data/exchange/` | Root directory for all exchange files. Relative paths resolve against the JVM working directory. |
| `agentos.exchange.allowed-upload-extensions` | `AGENTOS_EXCHANGE_ALLOWED_UPLOAD_EXTENSIONS` | text / doc / data / image / code set | Extensions allowed for user uploads (lowercase, no dot; empty = allow any). A disallowed type is rejected with 400. |

Upload size limits are global Spring multipart settings (they also bound plugin-jar uploads),
raised from Spring's 1 MB default:

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `spring.servlet.multipart.max-file-size` | `AGENTOS_MULTIPART_MAX_FILE_SIZE` | `25MB` | Max size of a single uploaded file. |
| `spring.servlet.multipart.max-request-size` | `AGENTOS_MULTIPART_MAX_REQUEST_SIZE` | `30MB` | Max size of the whole multipart request. |

All three follow the repo convention: `${ENV_VAR:default}` placeholders, the `AGENTOS_*` prefix,
and Spring relaxed binding.

## REST API

The controller is standalone (no class-level `@RequestMapping`), so the case and namespace routes
sit under their owning resource:

| Method and path | Scope | Gated on |
|---|---|---|
| `GET /api/cases/{caseId}/files/manifest` | case | Case `READ` |
| `GET /api/cases/{caseId}/files/content?path=` | case | Case `READ` |
| `GET /api/cases/{caseId}/files/download?path=` | case | Case `READ` |
| `POST /api/cases/{caseId}/files` (multipart `file`) | case | Case `WRITE` |
| `DELETE /api/cases/{caseId}/files?path=` | case | Case `WRITE` |
| `GET /api/namespaces/{namespaceId}/files/manifest` | namespace | Namespace `READ` |
| `GET /api/namespaces/{namespaceId}/files/content?path=` | namespace | Namespace `READ` |
| `GET /api/namespaces/{namespaceId}/files/download?path=` | namespace | Namespace `READ` |
| `POST /api/namespaces/{namespaceId}/files` (multipart `file`) | namespace | Namespace `WRITE` |
| `DELETE /api/namespaces/{namespaceId}/files?path=` | namespace | Namespace `WRITE` |

The manifest reports the caller's capability: `NONE`, `READ`, or `READ_WRITE`. Namespace writes
(upload/delete) require Namespace `WRITE` (namespace admin / super-admin); plain members are read-only.
Downloads emit a plain `filename="..."` for ASCII names and
add RFC 5987 `filename*=UTF-8''...` only for non-ASCII names.

## Agent Tools

Case and namespace exchange are exposed as **built-in integration types** (`ExchangeIntegrationTypes`):

| Type | Scope | Access |
|---|---|---|
| `CASE_FILE_EXCHANGE` | current case | read / write |
| `NAMESPACE_FILE_EXCHANGE` | namespace shared | read; read / write when the invoking user is a namespace admin |

They appear in `GET /api/integration-types` with `builtIn = true`, but only when the `FILE_ACCESS`
plugin is loaded. Enable them per agent through the agent's `integrations` map (no persisted boolean
flags). Per-run tools are built in `AgentServiceImpl.buildExchangeTools`, which points the file plugin
at the computed scope root and filters the tool set through the shared
`ToolResolverService.isToolAllowed` allowlist. Granted tool names follow `<configName>__<tool>`
(for example `case-exchange__editFiles`).

## Safety

- **Create-only writes**: staged to a sibling temp file then moved into place. A concurrent writer
  loses the race with a conflict, never a silent overwrite.
- **Path containment**: the resolved path must stay within the scope root. Traversal (`../`) and
  symlink escapes are rejected.
- **No phantom directories**: reads and deletes on a never-written scope return 404 without
  materializing empty shard directories.
- **Upload allow-list**: user uploads are restricted to a configurable set of file extensions
  (`agentos.exchange.allowed-upload-extensions`); a disallowed type is rejected with 400.

## Related

- Built-in integrations and tool resolution: [plugin-system.md](plugin-system.md)
- Persistence roots and the `data/` layout: [../AGENTOS.md](../AGENTOS.md)
