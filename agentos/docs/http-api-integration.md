# HTTP API integration (`HTTP_API`)

The `HTTP_API` integration type exposes the operations of any HTTP API described by an OpenAPI 3.x
document as agent tools, by configuration only: no connector code per service. A namespace
administrator points an integration config at the document and the base URL, binds an Auth Setting,
and every curated operation becomes one tool the bound agents can call.

The plugin lives in `agentos/agentos-http-plugin` (`Plugin-Id: agentos-http-plugin`, extension
`io.whozoss.agentos.plugins.http.HttpApiToolProvider`).

## How it relates to MCP

| | `MCP_STDIO` / `MCP_HTTP` | `HTTP_API` |
|---|---|---|
| What the agent talks to | An MCP server, which decides the tool surface | The HTTP API itself, described by its OpenAPI document |
| Who curates the tools | The MCP server author | The namespace administrator (filters, overrides) |
| Authentication | Credential of the bound Auth Setting, sent to the MCP server | Credential of the bound Auth Setting, mapped to an HTTP header or query parameter |
| When to prefer | A maintained MCP server exists for the service | No MCP server exists, or the API has a public OpenAPI document (or a short hand-written one) |

Both are `ToolPlugin`s discovered by PF4J; they share the Auth Setting and Integration Config model,
the agent binding and the tool naming convention (`<INTEGRATION>__<tool>`).

## Administrator flow

1. **Auth Setting** (namespace scope): create the credential the API expects, for example a
   `BASIC_AUTH` setting for Zendesk (`username` = `bot@corp.com/token`, `password` = the API token)
   or an `API_KEY` setting. Secrets stay in the Auth Setting; the integration config never carries one.
   A static setting (`BEARER_TOKEN`, `BASIC_AUTH`, `API_KEY`) authenticates only once the service
   prerequisite of [Authentication](#authentication) is shipped; today only an OAuth setting does.
2. **Integration Config** (namespace scope): type `HTTP_API`, `authSettingName` pointing at the Auth
   Setting, and the `parameters` documented below. The admin UI renders the config schema as a form.
3. **Agent binding**: bind the integration to the agent, ideally with an explicit tool allowlist
   (see [Mutations](#mutations)).

The integration type appears in `GET /api/integration-types` after the service has started with the
plugin jar in `plugins/` (see [Deployment](#deployment)). Create `HTTP_API` configs at namespace or
platform scope only: a user-scoped overlay could point a shared credential at another base URL. The
service does not refuse user-scoped `HTTP_API` configs yet; that refusal is part of the service
prerequisite described under [Authentication](#authentication).

## Configuration keys (`parameters`)

`spec` is required, `baseUrl` is required unless the document declares an absolute server URL, and
every other key has a default. Undeclared keys are refused by the parser at every level (the admin form
drops them before they reach it).

| Key | Type | Default | Purpose |
|---|---|---|---|
| `spec.url` | string (https URL) | – | Public URL of the OpenAPI 3.x document (JSON or YAML), fetched **without authentication**. Exactly one of `spec.url` / `spec.inline` / `spec.file`. |
| `spec.inline` | string (textarea) | – | The OpenAPI 3.x document itself, for APIs without a published document. |
| `spec.file` | string (absolute path) | – | A `.json`, `.yaml` or `.yml` document on the service host, typically `{{NAMESPACE_CONFIG_PATH}}/specs/<name>.yaml` in a filesystem config. See [Document sources](#document-sources). |
| `spec.refreshMinutes` | integer >= 0 | `60` | Re-check interval of a URL document; `0` keeps the first fetched document until the service restarts or the config changes. |
| `spec.maxBytes` | integer >= 1024 | `5242880` | Maximum document size (5 MiB). Larger documents are refused. |
| `baseUrl` | string (https URL) | – | Prepended to every operation path. Optional when the document declares an absolute `servers[0].url` (variables replaced by their defaults), which it overrides. Private and local addresses, a query string and a fragment are refused. |
| `includeTags` | string[] | `[]` | Keep only operations carrying at least one of these tags. |
| `includePathPrefixes` | string[] | `[]` | Keep only operations whose path starts with one of these prefixes, e.g. `/api/v2/tickets`. |
| `includeOperations` | string[] | `[]` | Keep only operations whose `operationId` matches one of these globs (`*`, `?`). |
| `excludeOperations` | string[] | `[]` | Drop operations whose `operationId` matches one of these globs, e.g. `*Bulk*`. |
| `maxTools` | integer 1..128 | `64` | More operations than this after filtering is a configuration error: **no tool is exposed** and the namespace description says why. Narrow the selection instead. |
| `operations[]` | object[] | `[]` | Per-operation overrides, identified by `operationId` (unique). |
| `operations[].description` | string | – | Replaces the description derived from the document. |
| `operations[].keepPaths` | string[] | `[]` | Dot-notation paths kept in the response; everything else is dropped. Wins over `ignorePaths`. |
| `operations[].ignorePaths` | string[] | `[]` | Dot-notation paths removed from the response. |
| `operations[].responseFormat` | `json` \| `yaml` | integration value | Rendering of this operation's responses. |
| `operations[].maxResponseChars` | integer >= 500 | integration value | Cap on this operation's rendered response. |
| `auth.apiKeyIn` | `header` \| `query` \| `bearer` | `header` | Where an `API_KEY` credential goes. Ignored for other credential types. When the whole `auth` block is left at its defaults, the document's `apiKey` security scheme decides (see [Authentication](#authentication)). |
| `auth.apiKeyName` | string | `X-API-Key` | Header or query parameter name carrying the API key. |
| `defaultHeaders` | map string -> string | `{}` | Static, non-secret headers added to every call. `Authorization`, `Proxy-Authorization`, `Cookie` and `Host` are refused when the config is saved; a name equal to the effective API key header (config or document scheme) fails the document load. A default `Accept` replaces the plugin's `Accept: application/json`. The agent cannot change them: a document header parameter of the same name is [not exposed](#header-parameters). |
| `responseFormat` | `json` \| `yaml` | `json` | Default rendering of responses: compact JSON, or YAML (more readable, fewer tokens). |
| `maxResponseChars` | integer >= 500 | `20000` | Default cap on the rendered response. |
| `timeoutSeconds` | integer >= 1 | `30` | Timeout of one whole call (connect, send, wait for the answer, read it) once it holds a concurrency slot; the wait for a slot is bounded by the same value, so a call takes at most twice this duration. The TCP connection alone is bounded at 10 s. |
| `maxConcurrentCalls` | integer >= 1 | `4` | Simultaneous calls of this integration config (namespace + name) across all agents and runs of the service; one plugin-wide semaphore per config. A call waits for a free slot up to `timeoutSeconds`, then fails with `TRANSPORT_ERROR`. |
| `allowMutations` | boolean | `false` | Also expose `POST`/`PUT`/`PATCH`/`DELETE` operations as `[WRITE]` tools. Read the [Mutations](#mutations) section before enabling it. |

The plugin `configSchema` is the source of truth for titles, descriptions and defaults; a unit test keeps
the schema defaults equal to the Kotlin defaults.

## Document sources

Exactly one of the three sources is set:

- **`spec.url`**: a public https URL, fetched without authentication and refreshed every
  `spec.refreshMinutes` (conditional `If-None-Match` request).
- **`spec.inline`**: the document text itself, for APIs without a published document; it never expires.
- **`spec.file`**: an absolute path on the service host ending with `.json`, `.yaml` or `.yml`, naming a
  regular readable file of at most `spec.maxBytes`, read as UTF-8. It is reloaded when the file's last
  modification time or size changes (checked on every run, the file is read only when it changed);
  `spec.refreshMinutes` does not apply. A missing, oversize, unreadable or unparsable file is a load
  failure: no tool is exposed and the namespace description says `document file cannot be loaded, see the
  service logs` until the file is loadable again (a file document is never served from a stale entry,
  unlike a URL document). The detail (missing, size, parse error) is logged at ERROR only: the file lives
  on the service host, and what it contains or whether a path exists there is not for the agent prompt.

In a filesystem integration config (`<configPath>/integrations/<NAME>.yaml`) write the path with the
`{{NAMESPACE_CONFIG_PATH}}` token, which the service substitutes before the plugin sees the config (see
[plugin-system.md](plugin-system.md)); configs saved through the API or the UI must carry the absolute path
themselves, a relative path is refused at parse time.

**Trust boundary.** Whoever can write the namespace config directory can point the plugin at any file the
service process can read, the same boundary as the `rootPath` of a `FILE_ACCESS` integration. Beyond the
extension allowlist, no path restriction is applied or promised (no escaping guard, symbolic links are
followed). The document content never carries a credential, but it shapes the tool surface and the URLs
called and, within the [outbound URL policy](#outbound-url-policy), decides where the bound credential is
sent: `servers[0].url` when `baseUrl` is absent, the `securitySchemes` header or query parameter name when
`auth` is untouched. Treat write access to that directory as administrator access. The same boundary applies
to a `spec.file` saved through the API or the UI: any absolute path the service can read is accepted, and it
is confined to administrators only once the service refuses user-scoped `HTTP_API` configs (see the service
prerequisite under [Authentication](#authentication)).

### Base URL

`baseUrl` wins when set. Otherwise the plugin uses `servers[0].url` of the document with every `{variable}`
replaced by its declared `default`, provided the result is absolute and passes the
[outbound URL policy](#outbound-url-policy); a document without such a server URL fails with
`'baseUrl' is required because the OpenAPI document declares no absolute server URL`, a server URL that
keeps a `{variable}` without `default` fails naming it (`... server URL keeps an unresolved variable
'{region}'`), and a server URL the policy refuses fails with the policy reason (`... server URL must use
the https scheme, got 'http'`).

## Authentication

**Service prerequisite.** On the current service only an OAuth Auth Setting yields a credential: a
`Credential` is stored only at the end of an OAuth flow, and the non-OAuth branch of the credential provider
merely looks a stored credential up, which finds nothing for a `BEARER_TOKEN`, `BASIC_AUTH` or `API_KEY` Auth
Setting. The plugin then sees a provider that yields no credential and every call fails with `AUTH_MISSING`
(third rule below). The static rows of the table below, and the Zendesk and STATUS [examples](#examples),
work once the service synthesises the credential from the Auth Setting itself and refuses user-scoped
`HTTP_API` configs: plan Phase 0 / PR A (`StaticCredentialFactory`, `agentos.integrations.user-scope-denied-types`),
which is not part of this plugin. Until it ships, bind an OAuth Auth Setting or expect `AUTH_MISSING`.

The service resolves the credential of the Auth Setting named by `authSettingName` for the user
running the agent, and hands the plugin a credential provider. The plugin invokes it **once per
run** (`provideTools`) and maps the credential by its `CredentialType`:

| Auth Setting type | Credential type | What the plugin sends |
|---|---|---|
| `OAUTH_DISCOVERABLE`, `OAUTH_REGISTERED`, `OAUTH_CUSTOM`, `OAUTH_MCP_DISCOVERABLE` | `OAUTH_TOKENS` | `Authorization: Bearer <accessToken>` |
| `BEARER_TOKEN` | `BEARER_TOKEN` | `Authorization: Bearer <token>` |
| `BASIC_AUTH` | `BASIC_AUTH` | `Authorization: Basic base64(username:password)` (RFC 7617, UTF-8; the username may contain `/` or `@`) |
| `API_KEY` | `API_KEY` | Per `auth.apiKeyIn`: `header` -> `<apiKeyName>: <key>`; `query` -> `?<apiKeyName>=<key>` appended last; `bearer` -> `Authorization: Bearer <key>` |

Rules that follow from the service behaviour:

- **An authenticated config needs a run with a user identity.** The service injects a credential
  provider only when `authSettingName` is set and the run has a user; the credential is the one of that
  user for that Auth Setting.
- **No provider means unauthenticated calls, whatever the config says.** When the service builds no
  credential provider for the bound Auth Setting (no user identity on the run), the plugin receives no
  provider and cannot tell that apart from a config without `authSettingName`: the same tools are listed
  and every call is sent without credentials, typically ending in `UNAUTHORIZED`. The plugin logs a WARN when an API key placement is in effect (customised
  `auth`, or one taken from the document) and no provider is bound.
- **A provider that yields nothing usable is `AUTH_MISSING` before any request.** When the provider is
  present but returns no credential or a credential without material (blank token, key or password), the
  tools are still listed (so the definition preview shows them) but every call fails fast with
  `AUTH_MISSING` and no request is sent. Fix the Auth Setting or the user's credential.
- The token is frozen for the run, like `MCP_HTTP`. A `401` is reported as `UNAUTHORIZED` with an
  instruction not to retry; the agent cannot refresh a token mid-run.
- API key placement is a property of the called API, hence configured on the integration (`auth`), not
  on the Auth Setting.
- **The document can supply the placement.** When the `auth` block is absent or left at its defaults and
  the document declares a `components.securitySchemes` entry of `type: apiKey` in a `header` or a `query`
  parameter, that scheme provides the placement and the name: the first usable one named by the top-level
  `security` requirements, else the first declared one (`cookie` schemes and schemes without a `name` are
  ignored). A customised `auth` block always wins. The chosen placement is logged at DEBUG; the namespace
  description says `api key` whenever a placement is in effect (customised or from the document), never
  the header or parameter name. The header of the effective placement is [reserved](#header-parameters).

## Curation and tool naming

Which operations become tools, in this order:

1. deprecated operations are dropped;
2. operations the reader cannot expose are dropped and reported: request body without
   `application/json` or `application/x-www-form-urlencoded` content (multipart, binary),
   a required header parameter that is not a valid HTTP header name, is [reserved](#header-parameters),
   is set by `defaultHeaders` or shares its name with a path/query parameter, required cookie parameters,
   a path/query/header parameter named `body` next to a
   request body, unresolvable `$ref` (only local `#/components/...` references are inlined; external
   references are never fetched);
3. non-`GET` operations are dropped unless `allowMutations` is `true`;
4. `includeTags`, `includePathPrefixes`, `includeOperations`, `excludeOperations` are applied;
5. operations are sorted by `(path, method)`;
6. more than `maxTools` remaining is a failure: no tool at all, and the reason is recorded;
7. optional header parameters that are not valid HTTP header names, are reserved, are set by
   `defaultHeaders` or share their name with a path/query parameter are removed from the remaining
   operations, with a WARN naming them (the operation is kept).

Swagger 2.0 documents are refused (the reader requires `openapi: 3.x`).

Tool names are `<INTEGRATION>__<suffix>`:

- suffix = `operationId`, else `<method>_<path>` with `{x}` segments rendered `by_x`
  (`GET /tickets/{ticketId}` -> `get_tickets_by_ticketId`);
- characters outside `[A-Za-z0-9_-]` become `_`, `_` runs are collapsed, so the service can split the
  integration prefix on the first `__`;
- the full name is at most 64 characters; a suffix that does not fit is truncated and ends with `_`
  plus 4 hex characters of its hash, so two long ids stay distinct; a repeated suffix gets `_2`, `_3`;
- the integration name itself is not rewritten: it must match `[A-Za-z0-9_-]+`, must not contain `__`
  and must leave room for a suffix (at most 56 characters). The service only requires a non-blank name,
  so a name such as `Zendesk Prod` or `A__B` is refused by the plugin (no tool exposed, reason recorded
  and shown in the namespace description).

Tool descriptions read `<METHOD> <path> — <summary>. <description>` (markdown collapsed, capped at
800 characters), then ` Returns: <2xx description>` and ` Returns only: a, b` when `keepPaths` is set.
Non-`GET` tools are prefixed `[WRITE] ` so the model and an allowlist tell them apart at a glance.

Input schemas are JSON Schema objects with `additionalProperties: false`: path, query and header
parameters become properties (path and explicitly required parameters required), the request body becomes
one `body` property (an object, never flattened), `nullable` becomes `type: [T, "null"]`, `allOf` is
merged, OpenAPI-only keywords are removed and very deep schemas are reduced (reported as a warning).

### Header parameters

A header parameter of the document becomes a tool argument named after the header and described as
`HTTP header <Name>` (followed by the document description), required when the document says so. Its
value must be a single line of printable ASCII characters: CR, LF, control or non-ASCII characters are
`INVALID_INPUT` and no request is sent. The header name must be an HTTP token (RFC 9110: letters, digits
and `!#$%&'*+-.^_`|~`, no space): a document header named otherwise (`X Tenant`) is ignored with a WARN,
or makes the operation unusable when required, since the HTTP client would refuse it at call time.
Reserved headers are never exposed: `Authorization`,
`Proxy-Authorization`, `Cookie`, `Host`, `Content-Length`, `Content-Type`, `Accept`, `Transfer-Encoding`,
`User-Agent` and the effective API key header (`auth.apiKeyName`, or the document scheme). An optional
reserved header parameter is ignored with a WARN; a required one makes the operation unusable. The same
applies to a header whose name a path or query parameter already uses, and to a header the config pins in
`defaultHeaders` (compared case-insensitively): the administrator value is the one sent, the agent cannot
change it, and a header argument of that name is ignored at call time even if a stale catalogue still
exposes it. The auth header always wins.

## Calls and response shaping

For each call the plugin:

- builds the URL from the normalised `baseUrl` with `HttpUrl.Builder`: each `{param}` is substituted
  and added as one path segment, so `/`, `..` and reserved characters are percent-encoded rather than
  spliced (`a/b` -> `a%2Fb`); query parameters follow (arrays repeated, `null` skipped), then the
  query-placed API key. The final URL must start with `baseUrl` and pass the URL policy;
- sends `defaultHeaders`, the [header arguments](#header-parameters), the auth header,
  `Accept: application/json` (unless a default header overrides it) and `User-Agent: AgentOS-HTTP_API/1`;
- serialises `args.body` as `application/json` or as `application/x-www-form-urlencoded` (flat
  object -> form fields, nested values JSON-encoded); a missing required body is `INVALID_INPUT`
  without any request; `GET` and `DELETE` never carry a body;
- never follows a redirect and never retries (a write must not be replayed);
- reads the response body with a hard cap of 4 x `maxResponseChars` bytes.

A `2xx` body is shaped before it reaches the model:

1. a JSON body (declared, or text that parses as JSON) is filtered by `keepPaths` / `ignorePaths`;
2. it is rendered as compact JSON or YAML (`responseFormat`);
3. the text is capped at `maxResponseChars` with the marker
   `... [truncated: showing N of M chars; narrow with keepPaths or paginate]` (`of more than M chars`
   when the body was already cut at the byte cap above: M is then a lower bound).

Non-JSON text is returned as is under the same cap; binary content types are not returned
(`binary response (<content-type>, N bytes) not returned`). `201`, `202` and `204` are rendered
`Created`, `Accepted`, `No content`, followed by the shaped body when there is one.

Path semantics (the model of the Coday TypeScript HTTP integration): dot notation, `*` expands to every
key of an object and to every element of an array, arrays are transparent (`results.id` applies to each
element), a repeated head (`ticket.id`, `ticket.status`, or `results.id`, `results.subject` through an
array) merges into the kept object, `keepPaths` wins over `ignorePaths`. Paths are grouped by key before
recursing, so arrays and objects behave alike (the TypeScript code lets the last path win on arrays).

The tool metadata (`ToolResponseEvent.toolMetadata`) is `{status, contentType, bytes, truncated, path}`
where `path` is the request path only: never the host, the query string (a query-placed API key would
leak) nor a header.

## Outbound URL policy

Applied to `spec.url` and `baseUrl` at parse time (a violation is a configuration error) and to every
request URL. A URL must be absolute, `https`, without userinfo, accepted by the HTTP client (a port above
65535 passes `java.net.URI` but is refused at parse time), with a host that is neither
`localhost` nor a literal address of a loopback, unspecified, link-local (`169.254.169.254`),
site-local (RFC 1918), carrier-grade NAT (`100.64/10`), IPv6 unique-local or IPv4-mapped range. Host
names are additionally checked when resolved: the plugin's DNS resolver keeps only addresses the policy
allows, so a public name rebinding to a private range is refused with `UnknownHostException`.

**Consequence: intranet APIs on private networks are refused.** Phase 1 targets public SaaS APIs only;
there is no allowlist.

## Mutations

Three locks, in series, before an agent can write to an external system:

1. `allowMutations: true` on the integration config (default `false`: only `GET` operations exist).
2. `excludeOperations` on the config and, above all, a **per-agent tool allowlist** naming exactly the
   `[WRITE]` tools the agent may use.
3. `ConfirmationMode.EVERY_TIME` on every non-`GET` tool: the advanced agent asks the user to confirm
   each write, with the instruction "mutations are never implicit and require explicit user consent".

**The simple agent does not honour confirmation modes**: an `AgentSimple` bound to a config with
`allowMutations: true` and no allowlist executes writes without asking. The allowlist is therefore the
recommended guard, and `allowMutations` must be enabled knowingly. No write is ever retried, so a
transport failure cannot create a resource twice.

## Caching and refresh

The curated catalogue (title, version, effective base URL and API key placement, operation descriptors)
is cached plugin-wide, keyed by a SHA-256 of the config fields that determine it (`spec.url`, hash of
`spec.inline`, `spec.file` path with the file's last modification time and size, `baseUrl`, filters,
`maxTools`, `allowMutations`, `auth`, `operations[]`, `responseFormat`, `maxResponseChars`, the
`defaultHeaders` names), never by credential material. At most 200 entries are kept (least recently used out); loads are serialised by a
64-stripe lock so concurrent runs do not stampede the source (two distinct configs can share a stripe and
then wait for each other's fetch, at most 15 s).

- An inline document never expires: its content is part of the key.
- A URL document is re-fetched conditionally (`If-None-Match`) once `spec.refreshMinutes` have elapsed;
  a `304` only restarts the interval.
- A file document never expires either: its stamp (a `stat` on every run) is part of the key, so a changed
  file is a miss that reads it again under a new key, and a missing or unreadable file is a miss that
  fails; the entry of the previous version stays until evicted and is never served for the new one.
  Between the change and the next run, `describeNamespace` has no entry for the new key and no recorded
  failure, so it says nothing about that integration; the line is back after the next run.
- A load fails, with nothing cached, when a `defaultHeaders` name equals the effective API key header
  (from the config or from the document security scheme): the credential would silently overwrite it.
- A URL refresh that fails keeps serving the previous catalogue (WARN log; the failure is recorded and
  appended to the namespace description as `(document refresh failing: <reason>)` until a refresh
  succeeds). A failure with nothing cached exposes no tools and is retried on the next run; it is never
  cached as a success.
- The document fetch is unauthenticated, bounded by `spec.maxBytes`, 15 s timeout, no redirect.

The first run after a service restart or a config change blocks until the document is fetched and
curated (a few hundred milliseconds for multi-megabyte documents once downloaded).

## What the agent sees

`describeNamespace` gives the agent one line per integration, from the cache and without any network
or credential access, for example:

- `Integration ZENDESK (HTTP_API): Zendesk Support API v2 — 9 operations exposed (read-only)`
- `Integration STATUS (HTTP_API): Status API 1.0.0 — 2 operations exposed (read-only, api key)` (an API
  key placement is in effect, customised or taken from the document security scheme; its header or
  parameter name is never shown)
- `Integration ZENDESK (HTTP_API): Zendesk Support API v2 — 9 operations exposed (read-only) (document refresh failing: HTTP 503 fetching the OpenAPI document)`
- `Integration ZENDESK (HTTP_API): not available (HTTP 404 fetching the OpenAPI document): no tools exposed`
  (the same shape reports a configuration error, e.g. `not available (unknown key 'x')`)

Failures are recorded per namespace and integration name: the same name in another namespace never sees
nor clears them. Unexpected errors (a failing credential provider, for instance) are described with a
fixed sentence; the exception text stays in the service logs.

Error types returned by the tools (`errorType`), stable so prompts can rely on them:

| `errorType` | When | Agent guidance in the message |
|---|---|---|
| `INVALID_INPUT` | Arguments are not a JSON object, a required path or header parameter or body is missing, a header value is not a single printable line, a form body is not an object | Fix the arguments; no request was sent |
| `AUTH_MISSING` | An Auth Setting is bound but no usable credential was resolved | Ask an administrator; no request was sent |
| `URL_POLICY_REJECTED` | The final URL would leave `baseUrl` or violates the policy | No request was sent |
| `HTTP_CLIENT_ERROR` | `400`, `404`, `409`, `422` and other `4xx` (body capped at 2000 characters) | Correct the call |
| `UNAUTHORIZED` | `401` | Do not retry; ask an administrator to check the Auth Setting |
| `FORBIDDEN` | `403` | Do not retry; ask an administrator to check the permissions |
| `RATE_LIMITED` | `429` | Wait `Retry-After` seconds (or until the given date) before calling again, and do not hammer the target with retries |
| `REDIRECT_NOT_FOLLOWED` | `3xx` (the `Location` is not echoed) | Nothing to retry |
| `HTTP_SERVER_ERROR` | `5xx` | The target failed |
| `TRANSPORT_ERROR` | Timeout, connection failure, or too many concurrent calls to this integration | Retry later |

## Logs and secrets

One INFO line per call: `HTTP_API '<config>': <METHOD> <host> <path> -> <status> in <ms>ms (<size>)`,
WARN with the same shape on failure. Headers, query strings and bodies are never logged, at any level:
an answer body may carry a token or personal data. No secret ever appears in a tool output, in the
metadata, in a log line or in an exception message; the auth spec's `toString` is redacted. A unit test
asserts it with known secrets across every credential type and placement, and with a secret in the
answer body. A document parse error is reported on one line, without the offending source.

## Proxy and TLS

The plugin uses the JVM default `ProxySelector`, so the standard system properties apply to the service
process: `-Dhttps.proxyHost`, `-Dhttps.proxyPort`, `-Dhttp.nonProxyHosts`. A corporate CA is added to the
JVM trust store (`-Djavax.net.ssl.trustStore`). Nothing is configured in `application.yml`.

With a proxy configured, host names are resolved by the proxy, not by the service: the DNS-based part of
the [outbound URL policy](#outbound-url-policy) does not apply (only literal private addresses are still
refused). The proxy must then enforce the private-range policy itself, or `http.nonProxyHosts` must keep
internal hosts away from it.

## Examples

### Zendesk from its published document

Needs the service prerequisite of [Authentication](#authentication): a `BASIC_AUTH` Auth Setting yields no
credential on the current service, so every call ends in `AUTH_MISSING` until it ships.

`<configPath>/integrations/ZENDESK.yaml`:

```yaml
name: "ZENDESK"
integrationType: "HTTP_API"
description: "Zendesk Support tickets (read-only). Use ListSearchResults to find tickets, ShowTicket for details."
authSettingName: "ZENDESK_AUTH"          # BASIC_AUTH: username "bot@corp.com/token", password = API token
parameters:
  spec: { url: "https://developer.zendesk.com/zendesk/oas.yaml", refreshMinutes: 1440 }
  baseUrl: "https://corp.zendesk.com"
  includePathPrefixes: ["/api/v2/tickets", "/api/v2/search", "/api/v2/users"]
  excludeOperations: ["*Bulk*", "*Merge*", "*Count*"]
  maxTools: 40
  responseFormat: "yaml"
  operations:
    - operationId: ListSearchResults
      keepPaths: ["results.*.id", "results.*.subject", "results.*.status", "results.*.priority", "count", "next_page"]
    - operationId: ShowTicket
      ignorePaths: ["ticket.custom_fields", "ticket.fields", "ticket.via"]
```

The agent then has `ZENDESK__ListSearchResults`, `ZENDESK__ShowTicket`, ... To let it update tickets,
set `allowMutations: true` and extend the agent's allowlist with `ZENDESK__UpdateTicket`, which the
definition preview shows as `[WRITE] PUT /api/v2/tickets/{ticket_id} ...` with a `body` property.

### API without a published document (inline spec, API key)

Needs the service prerequisite of [Authentication](#authentication): an `API_KEY` Auth Setting yields no
credential on the current service, so every call ends in `AUTH_MISSING` until it ships.

The document itself carries the server URL and the API key placement, so neither `baseUrl` nor `auth` is
needed; an explicit `auth: { apiKeyIn: header, apiKeyName: X-Api-Key }` would override the scheme.

```yaml
name: "STATUS"
integrationType: "HTTP_API"
description: "Internal status board of the corp SaaS (read-only)."
authSettingName: "STATUS_KEY"            # API_KEY Auth Setting
parameters:
  spec:
    inline: |
      openapi: 3.0.3
      info: { title: Status API, version: 1.0.0 }
      servers: [{ url: "https://status.example.com" }]
      components:
        securitySchemes:
          ApiKey: { type: apiKey, in: header, name: X-Api-Key }
      paths:
        /api/incidents:
          get:
            operationId: listIncidents
            summary: List incidents
            parameters:
              - { name: status, in: query, schema: { type: string, enum: [open, resolved] } }
              - { name: limit, in: query, schema: { type: integer, default: 20 } }
            responses: { '200': { description: Incidents } }
        /api/incidents/{id}:
          get:
            operationId: showIncident
            summary: Show one incident
            parameters:
              - { name: id, in: path, required: true, schema: { type: string } }
            responses: { '200': { description: The incident } }
```

### Document kept next to the config (file spec)

`<configPath>/integrations/CALENDAR.yaml`, with the document in `<configPath>/specs/calendar.yaml`:

```yaml
name: "CALENDAR"
integrationType: "HTTP_API"
authSettingName: "GOOGLE_OAUTH"
parameters:
  spec: { file: "{{NAMESPACE_CONFIG_PATH}}/specs/calendar.yaml" }
  responseFormat: "yaml"
```

Editing `specs/calendar.yaml` is picked up on the next run without restarting the service.

## Migrating a Coday TypeScript HTTP integration

The declarative `integration.<NAME>.http.{baseUrl, endpoints[]}` block of a Coday project YAML
(`libs/integrations/http`) maps one-to-one onto a hand-written OpenAPI document: each endpoint becomes an
operation whose `operationId` is the endpoint name (so the tool keeps its `<NAME>__<endpoint>` name),
`path`/`query` params become parameters, `body` params are grouped into a JSON request body, and
`keepPaths` / `ignorePaths` / `responseFormat` become an `operations[]` override. The script
`scripts/http-integration-to-openapi.ts` does the conversion:

Run it from the repository root with the workspace dependencies installed (`pnpm install`): it uses the
`yaml` package of the workspace and nothing else.

```bash
# the OpenAPI 3.0.3 document, to save as <configPath>/specs/my-calendar.yaml or to inline
npx tsx scripts/http-integration-to-openapi.ts coday.yaml MY_CALENDAR > my-calendar.yaml

# the IntegrationConfig skeleton embedding the document as spec.inline
npx tsx scripts/http-integration-to-openapi.ts coday.yaml MY_CALENDAR --config > MY_CALENDAR.yaml
```

For the `MY_CALENDAR` example of `http.tools.ts` the first command prints a document with
`servers[0].url: https://www.googleapis.com/calendar/v3` and a `GET /calendars/{calendarId}/events`
operation `getEvents` (path parameter `calendarId` required, query parameters `timeMin`, `maxResults`);
the second prints:

```yaml
name: MY_CALENDAR
integrationType: HTTP_API
authSettingName: <AUTH_SETTING_NAME>     # replace with the OAuth Auth Setting of the namespace
parameters:
  spec:
    inline: |
      openapi: 3.0.3
      ...
  operations:
    - operationId: getEvents
      keepPaths: [items.*.summary, items.*.start, items.*.end]
      responseFormat: yaml
```

What changes for the agent: the OAuth2 flow of the TypeScript integration becomes an Auth Setting bound
by `authSettingName`; `allowMutations: true` is emitted when a non-`GET` endpoint exists (the TypeScript
integration executed writes without a guard); body params on `GET`/`DELETE` endpoints become query
parameters, with a warning on stderr, since `HTTP_API` never sends a body on those methods; two endpoints
sharing a method and a path keep the first one, the other is dropped with a warning. The converter
has a unit test (`scripts/utils/http-integration-to-openapi.spec.ts`) run by `pnpm exec nx test scripts`
(the Jest target of the `scripts` project, executed by CI when the project is affected).
The plugin test suite reads the document it produces for that example (`converted-my-calendar.yaml`).

## Overlay caveats

- Config overlays (see [user-level-overlays.md](user-level-overlays.md)) merge objects key by key but
  **replace arrays wholesale**: an `operations` or `includeTags` list in a higher layer replaces the lower
  one entirely.
- The admin form re-emits only the keys the schema declares: editing a filesystem config in the UI
  drops any undeclared key. A mistyped key (`allowMutation`, `excludeOperation`) is therefore dropped by
  the form, the intended setting keeping its default, while a config coming from a file or the API is
  refused by the parser (`unknown key 'allowMutation'`): the integration exposes no tools and
  `describeNamespace` says why. Check the saved config after editing it in the form.
- A config that fails validation (for example two `spec` sources set, a relative `spec.file`, `http://`
  base URL, a `baseUrl` with a query string, `Authorization` in `defaultHeaders`, duplicate `operationId`, an integration name outside
  `[A-Za-z0-9_-]` or containing `__`) exposes no tools; the message is logged at ERROR and shown in the
  namespace description.

## Deployment

The plugin is built and deployed like the other bundled plugins:

```bash
cd agentos
./gradlew deployPlugins      # builds every plugin and copies the jars into agentos/plugins/
# restart the service: plugins are scanned once at startup
```

The release workflow publishes `agentos-http-plugin-<version>.jar` next to the other plugin jars, and
the embedded Coday runtime lists it in `AGENTOS_ARTIFACT_IDS`. Jackson (including
`jackson-dataformat-yaml`), OkHttp and the Kotlin runtime are provided by the service classloader and are
not bundled in the plugin jar.
