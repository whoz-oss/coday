# Platform connection policies

ConnectionPolicy is server-owned configuration, not a tenant CRUD entity or a prompt restriction. AuthSetting storage, DTOs, encrypted data and settings forms remain unchanged. Stable policy IDs live in server configuration; exact names and persistent UUIDs bind settings to policy IDs outside the overlay. This avoids a tenant-writable `policyId` that could be cleared or replaced. There is no policy-editing API.

## Configuration

```yaml
agentos:
  connections:
    mode: STRICT
    auth-setting-names:
      ACME_READ: [acme-read]
    # Optional UUID binding; use the base setting ID preserved by overlay resolution.
    auth-setting-ids:
      "00000000-0000-0000-0000-000000000001": [acme-read]
    policies:
      acme-read:
        allowed-auth-types: [OAUTH_REGISTERED]
        authorization-urls: ["https://auth.example.test/authorize"]
        token-urls: ["https://auth.example.test/token"]
        discovery-urls: []
        registration-urls: []
        allowed-scopes: [read]
        allowed-integration-types: [HTTP_API]
        resource-origins: ["https://api.example.test"]
```

Bind the existing AuthSetting `ACME_READ` to an integration with `authSettingName: ACME_READ`. Its OAuth endpoints and scopes must fit this policy. For API_KEY, BEARER_TOKEN or BASIC_AUTH, bind by name/UUID and allow the exact auth type; endpoint fields and scopes are irrelevant. Resource origins are HTTPS scheme/host/port tuples, not path grants. HTTP_API's own path/SSRF/redirect policy still applies independently.

All matching policy bindings intersect. An OAuth setting using **any** configured authorization, token or discovery endpoint is also classified into that endpoint's policy. This follows renamed aliases and denies a setting that redirects just one endpoint. Explicit name/UUID bindings are required to prevent escape by replacing *all* endpoints between runs; strict mode denies unclassified settings. Names are exact and case-sensitive. UUID bindings refer to persisted base identities. Unknown IDs, empty bindings, malformed URLs and unknown configuration properties fail startup. Tenant JSON cannot add policy ceilings.

Authentication endpoints are exact normalized HTTPS URLs (host case and default port normalize). Path and query remain part of the match. No wildcards, suffix matching, userinfo, fragments, backslashes, dot segments or encoded path separators. Governed OAuth requests disable redirects and retries. Standard OIDC discovery checks the discovery URL before fetch and returned authorization/token URLs before use. Governed MCP dynamic discovery/registration is refused in this release; allowlist fields reserve the endpoint contract without claiming dynamic MCP support.

## Effective overlays and lifecycle

The platform-owned binding never participates in tenant overlay merging. A governed lower layer freezes auth type and endpoint fields; overlays may only narrow scopes. Effective settings are validated again before credential use. Actual saved/new/refreshed OAuth scopes must be known, nonempty, within both requested scopes and every policy ceiling. Editing requested scopes does not downgrade an old broad grant: reconnect with narrow consent. Only a validated same-refresh-grant may preserve scope evidence when refresh **omits** scope; explicit blank/null fails. Credential user, base setting ID and credential type are checked.

ToolResolverService checks the runtime-resolved integration type before calling the credential factory or plugin initialization. A Google-only policy rejects HTTP_API, MCP_HTTP, MCP_STDIO and BASH using that connection, including integration aliases, effective overlays and `/api/tools/invoke`. Direct execution has no credential supplier today: a governed HTTP call refuses a missing credential rather than sending anonymously. Bound anonymous runs fail closed. Existing Google grant checks remain an additional provider-specific ceiling.

Tools capture a host-installed SDK ConnectionCapability, bound to namespace/user/setting/integration type. Caller execution contexts and tool arguments cannot replace that captured binding. HTTP_API uses the final request URL to obtain a validated credential and checks again after waiting for its transport permit. Governed HTTP credentials do not use RunAuth's cached auth header. Other plugins are trusted and must adopt the capability for per-request resource enforcement; integration usage denial still happens centrally. Google's existing plugin maintains its independent fixed-origin and object policy.

Native account connection retains the requested resolution namespace and initiating user through pending consent, token exchange and credential storage. A null-namespace base may therefore use a permitted user×namespace scope-narrowing overlay without losing its preserved base UUID binding. Requests genuinely lacking a resolution namespace intentionally use raw-row validation instead; the base row's namespace is never inferred as the effective resolution context. Changed effective data or base identity while consent is pending is refused before exchange; a change during exchange is refused before storage.

HTTP_API converts and validates the freshly retrieved credential again after acquiring the transport permit. Missing or blank OAuth access tokens, bearer tokens, either Basic field, and API keys return `AUTH_MISSING` locally, without dispatching a request, and release the permit. The final request uses the fresh material rather than cached headers.

Server policy configuration is an immutable startup snapshot: changing config requires restart and stopping old instances/runs. AuthSettings are re-read at active tool invocation and before OAuth side effects/storage; changing pinned endpoints or base identity invalidates active tools. OAuth checks effective data again after waiting for consent and before storing refresh/new credentials. These checks reduce races; they do not revoke an already-dispatched request atomically. Stop active runs for immediate administrative revocation.

## Migration

The shipped default is `COMPATIBILITY` (`AGENTOS_CONNECTIONS_MODE`). Unbound legacy connections retain old behavior, **not** a governance guarantee. Configured endpoint policies still apply in compatibility mode. The shipped `google-workspace-readonly` endpoint policy limits registered Google OAuth to the three read-only Workspace scopes and GOOGLE_WORKSPACE usage, even through aliases; installations using generic broad Google OAuth must migrate or explicitly review/replace that policy.

1. Inventory settings, overlays and integration types without exporting secrets.
2. Add server-owned name and base-UUID bindings and exact endpoint/resource ceilings.
3. Reconnect broad or unknown grants; narrow overlay scopes and remove shadow consumers.
4. Exercise tools against intercepted synthetic transports, including direct invocation.
5. Set STRICT and restart all instances. Keep the config outside namespace/user filesystem configuration.

No database/schema or public DTO migration is needed. Deploy/rebuild SDK-dependent plugins together: the new ToolContext capability changes the compiled SDK contract.

## Trusted-runtime limits

This is a cooperative in-process authorization boundary, not a hostile-plugin sandbox or universal network firewall. It does not block anonymous independent token exchange, tokens copied into HTTP headers, proxy services, BASH curl, browser traffic or malicious plugins opening their own sockets. A token already leaked cannot be recalled by an endpoint allowlist. Compatibility permits wholly unclassified aliases. The future strong broker must retain secrets, bind each credential to destination/purpose, and exclusively own egress (including process/browser routes), with runtime isolation and network denial outside the broker. Do not describe this release as full exfiltration prevention.
