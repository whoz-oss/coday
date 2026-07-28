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

Storage and user-facing limits, bound from the `agentos.exchange` prefix (Spring relaxed binding):

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `agentos.exchange.mount-root` | `AGENTOS_EXCHANGE_MOUNT_ROOT` | `data/exchange/` | Root directory for all exchange files. Relative paths resolve against the JVM working directory. |
| `agentos.exchange.allowed-upload-extensions` | `AGENTOS_EXCHANGE_ALLOWED_UPLOAD_EXTENSIONS` | text / doc / data / image / code set | Extensions allowed for user uploads (lowercase, no dot; empty = allow any). A disallowed type is rejected with 400. |
| `agentos.exchange.read-max-size-bytes` | `AGENTOS_EXCHANGE_READ_MAX_SIZE_BYTES` | `104857600` (100 MB) | Max size the read/download endpoints load into memory (larger is rejected with 422). Also caps the agent read tools on the same directories, in whole megabytes with a floor of 1. |

Upload size limits are global Spring multipart settings (they also bound plugin-jar uploads),
raised from Spring's 1 MB default:

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `spring.servlet.multipart.max-file-size` | `AGENTOS_MULTIPART_MAX_FILE_SIZE` | `25MB` | Max size of a single uploaded file. |
| `spring.servlet.multipart.max-request-size` | `AGENTOS_MULTIPART_MAX_REQUEST_SIZE` | `30MB` | Max size of the whole multipart request. |

The multipart pair uses explicit `${ENV_VAR:default}` placeholders because it lives under the
`spring.*` namespace; the `agentos.*` keys rely on Spring relaxed binding alone. Both follow the
`AGENTOS_*` prefix convention.

Agent-facing tool policy, bound from the `agentos.exchange.tools` prefix. **One set of values is
shared by both scopes**: an agent sees the same limits whether a file sits in the case or the
namespace exchange. The plugin's three other keys are absent by design — `rootPath` and `readOnly`
are computed per run, and `readMaxSizeMb` derives from `agentos.exchange.read-max-size-bytes` so the
agent read cap can never drift from the one the REST path enforces.

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `agentos.exchange.tools.case-enabled-by-default` | `AGENTOS_EXCHANGE_TOOLS_CASE_ENABLED_BY_DEFAULT` | `false` | Grants `CASE_FILE_EXCHANGE` to every agent that does not mention it. |
| `agentos.exchange.tools.namespace-enabled-by-default` | `AGENTOS_EXCHANGE_TOOLS_NAMESPACE_ENABLED_BY_DEFAULT` | `false` | Same for `NAMESPACE_FILE_EXCHANGE`; the invoking user must also hold Namespace `READ`, and write follows Namespace `WRITE`. |
| `agentos.exchange.tools.extra-deny-patterns` | `AGENTOS_EXCHANGE_TOOLS_EXTRA_DENY_PATTERNS` | empty | Extra patterns blocked on top of the built-in sensitive-file list (additive only). Matched against file names only, see below. |
| `agentos.exchange.tools.image-max-dimension` | `AGENTOS_EXCHANGE_TOOLS_IMAGE_MAX_DIMENSION` | `1024` | Longest edge (px) of images `readAsImage` / `readDocument` send to the LLM. |
| `agentos.exchange.tools.image-jpeg-quality` | `AGENTOS_EXCHANGE_TOOLS_IMAGE_JPEG_QUALITY` | `0.80` | JPEG re-encoding quality, clamped to `[0, 1]`. |
| `agentos.exchange.tools.image-max-source-pixels` | `AGENTOS_EXCHANGE_TOOLS_IMAGE_MAX_SOURCE_PIXELS` | `50000000` | Decode-bomb guard. |
| `agentos.exchange.tools.image-pass-through-max-bytes` | `AGENTOS_EXCHANGE_TOOLS_IMAGE_PASS_THROUGH_MAX_BYTES` | `1048576` | Small originals already fitting the max dimension are sent untouched. |
| `agentos.exchange.tools.document-max-output-chars` | `AGENTOS_EXCHANGE_TOOLS_DOCUMENT_MAX_OUTPUT_CHARS` | `100000` | Markdown budget per `readDocument` call. |
| `agentos.exchange.tools.document-max-attached-images` | `AGENTOS_EXCHANGE_TOOLS_DOCUMENT_MAX_ATTACHED_IMAGES` | `10` | Max embedded pictures attached per call. |
| `agentos.exchange.tools.document-max-table-columns` | `AGENTOS_EXCHANGE_TOOLS_DOCUMENT_MAX_TABLE_COLUMNS` | `64` | Columns dropped beyond this when rendering a `.docx` table. |
| `agentos.exchange.tools.document-max-cell-chars` | `AGENTOS_EXCHANGE_TOOLS_DOCUMENT_MAX_CELL_CHARS` | `5000` | A longer table cell is truncated. |

`extra-deny-patterns` matches the **final path segment only** (the file or directory name), never
the full relative path, and supports only `*suffix`, `prefix*`, `*contains*` and exact names (the
file-plugin's `matchesPattern`, applied by `BoundaryPathResolver` to the resolved name). A
path-shaped pattern such as `secrets/*` therefore matches nothing, and a prefix pattern such as
`internal-*` denies the directory entry `internal-reports` while leaving
`internal-reports/summary.md` readable through its direct path. To fence off content, use name
patterns that hold at every depth, like `*.bak` or `*confidential*`.

These reach the file plugin as the `provideTools` configuration node: the SDK and the plugins carry
no Spring dependency, so `ExchangeToolGrantService` is what turns the bound properties into that
node. The defaults duplicate the plugin's own constants — a plugin jar predating one of these keys
simply ignores it and falls back to its compiled default, so tuning a value without redeploying the
plugin has no visible effect.

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
| `NAMESPACE_FILE_EXCHANGE` | namespace shared | requires the invoking user to hold Namespace `READ`; read / write when they hold Namespace `WRITE` (admin) |

They appear in `GET /api/integration-types` with `builtIn = true`, but only when the `FILE_ACCESS`
plugin is loaded. Enablement is per agent, through the agent's `integrations` map (no persisted
boolean flags), with a platform-level fallback for an agent that says nothing:

| `integrations` entry | Result |
|---|---|
| key absent | the platform default (`agentos.exchange.tools.*-enabled-by-default`, off by default) decides; when on, every tool is granted |
| `CASE_FILE_EXCHANGE:` (null) | granted, every tool |
| `CASE_FILE_EXCHANGE: [readFile, listFiles]` | granted, restricted to those names (bare or `case-exchange__readFile`) |
| `CASE_FILE_EXCHANGE: []` | **opt-out** — nothing granted, and no scope directory is created |

The empty list is a genuine opt-out rather than an empty allow-list. `ToolResolverService.isToolAllowed`
would already reject every tool, but the grant creates the scope directory *before* that filter runs,
so the decision short-circuits earlier in `ExchangeToolGrantService.resolveCaseGrant` /
`resolveNamespaceGrant`. Resolution also short-circuits when the `FILE_ACCESS` plugin is not loaded:
no grant, hence no permission query and no directory (`grantTools` re-checks the plugin as defence in
depth).

The namespace scope carries one more gate. However the grant arises (platform default or the agent's
own declaration), it stands only when the invoking user holds Namespace `READ`, and a run without an
identified user is denied outright (fail-closed): the agent reads the shared files on behalf of a
user, so no user means no access. One visible consequence: the definition-preview endpoint
(`getDefinition` with `withUserOverlay=false`) resolves no user and therefore does not report the
namespace exchange tools.

The three states are what the agent form in `agentos-ui` renders, per built-in type: *Platform
default*, *Enabled*, *Disabled*. "Platform default" is a state the user can see and keep, not a
synonym for off — that is what stops an agent nobody ever configured from being silently switched off
the first time someone saves an unrelated change on a default-on instance. To label which way it
resolves, `GET /api/integration-types` publishes `enabledByDefault` on each descriptor.

A persisted per-tool allowlist survives the form too: when the stored value is a non-empty tool list
and the user leaves the row on *Enabled*, the original list is written back unchanged rather than
silently widened to "all tools", and the restriction is surfaced as read-only text under the row (the
form edits enablement, not the list).

Per-run tools are built by `AgentServiceImpl.buildExchangeTools`, which keeps what only it knows (the
run's `caseId`, the invoking user's Namespace `READ` and `WRITE` rights) and delegates the decision,
the plugin configuration and the `ToolResolverService.isToolAllowed` filtering to
`ExchangeToolGrantService`. The platform default is a decision local to that service and never a
mutation of `AgentConfig.integrations`, which would otherwise leak into the persisted config, the
YAML export and the peer descriptions the redirect tool puts in every agent's prompt. The YAML export
in turn round-trips the agent's own choice in both directions: a built-in key mapped to null
(enabled, all tools) and one mapped to `[]` (opt-out) are both preserved, so exporting and
re-importing an agent keeps its exchange stance. Granted tool names follow `<configName>__<tool>`
(for example `case-exchange__editFiles`).

## Safety

- **Create-only writes**: written straight to the target with an atomic create-new open. A concurrent
  writer loses the create-new race with a conflict, never a silent overwrite.
- **Path containment**: the resolved path must stay within the scope root. Traversal (`../`) and
  symlink escapes are rejected.
- **No phantom directories**: reads and deletes on a never-written scope return 404 without
  materializing empty shard directories. The agent grant is the one exception — the file plugin
  canonicalizes `rootPath` at construction, so granting a scope creates its directory. Turning
  `case-enabled-by-default` on therefore materializes a case exchange directory for every case a
  granted agent runs in; an agent that opts out with `[]` creates nothing.
- **Upload allow-list**: user uploads are restricted to a configurable set of file extensions
  (`agentos.exchange.allowed-upload-extensions`); a disallowed type is rejected with 400.

## Related

- Built-in integrations and tool resolution: [plugin-system.md](plugin-system.md)
- Persistence roots and the `data/` layout: [../AGENTOS.md](../AGENTOS.md)
