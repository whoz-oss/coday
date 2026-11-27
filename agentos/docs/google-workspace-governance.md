# Google Workspace governance (`GOOGLE_WORKSPACE`)

A PF4J plugin exposing **nine curated read-only tools**, not a general Google proxy. No OpenAPI document, origin, method, request body, headers, token endpoint, fields mask or pagination cursor can be supplied by an agent. Empty policy denies all objects; invalid configuration exposes no tools and is reported as a load failure of the integration. The server log names the refusal code and the path of the offending field, built from schema keys and array indexes only (e.g. `INVALID_INPUT at calendar.allowedCalendarIds[0]`), never a configuration value. `INVALID_INPUT` on an ID path means that value is not a bare ID (`[A-Za-z0-9_@.+-]`, 1–254 chars, not `primary`/`me`); `INVALID_CONFIG` means a structural problem (unknown key, wrong type, limit out of range, duplicate, allow/block overlap, missing `gmail.mailbox`).

## Tool catalog

Tools are named `<configName>__<suffix>`; configuration names must match `[A-Za-z0-9_-]{1,36}` without `__`.

| Suffix | Arguments | Capability |
|---|---|---|
| `drive_list` | optional `query` | Search file names by literal substring, list projected metadata |
| `drive_metadata` | `fileId` | Verify object membership, return id/name/MIME type |
| `drive_read` | `fileId` | Read only `text/plain` files, bounded UTF-8 text |
| `drive_export` | `fileId` | Export only native Google Docs as `text/plain` |
| `calendar_events` | `calendarId`, `timeMin`, `timeMax` | Read events in an explicit RFC3339 window of at most 31 days |
| `calendar_event` | `calendarId`, `eventId` | Read an event on an explicitly allowed calendar |
| `gmail_search` | optional `query` | Google Gmail search, then intersect with fixed allowed message IDs |
| `gmail_metadata` | `messageId` | Selected message ID and Subject/From/To/Date headers |
| `gmail_read` | `messageId` | Selected message's inline single-part `text/plain` body only |

Calendar returns only event id, summary, description, location, status and projected start/end date/time/timezone. Attendees, organizer, attachments, conference links and raw calendar response data are excluded. Drive list never returns parents, permissions, shortcut target IDs or raw Drive data. Gmail never returns snippets, thread IDs, arbitrary headers, counts or unrelated label IDs.

Unsupported: writes of any kind, permissions, shared-drive enumeration, changes/history feeds, recursive folder traversal, binary downloads, Sheets/Slides exports, shortcut content dereferencing, Calendar ACL/free-busy/calendar-list, Gmail threads/attachments/drafts/send/settings, multipart/HTML/raw Gmail bodies. Adding unsupported arguments such as `method`, `path`, `fields`, `pageToken`, `body` or `mimeType` is refused.

## Save-time validation

The service validates a `GOOGLE_WORKSPACE` configuration when it is created or updated (`PluginConfigValidationPolicy`), by asking the plugin to build its tools from the saved parameters, without network or credentials. An invalid configuration is refused with HTTP 422 and the integration form shows the reason, e.g. `INVALID_CONFIG at drive.blockedSharedDriveIds: a shared drive ID cannot be both allowed and blocked`. The rules stay owned by the plugin. Each row is validated alone: a namespace row that is valid alone but conflicts once merged with the platform row is still reported at run time as a load failure.

## Services are opt-in; what `gmail.mailbox` is for

Drive, Calendar and Gmail are each denied until their section lists what may be read; Drive and Calendar never need a mailbox. `gmail.mailbox` grants nothing: it is a safety check that the connected Google account is the expected one, required only once Gmail message IDs are listed. To guarantee that no email can be read, leave the `gmail` section empty **and** leave `https://www.googleapis.com/auth/gmail.readonly` out of the bound Auth Setting scopes, so Google itself refuses Gmail calls.

## Policy semantics

- `drive.allowedSharedDriveIds` is a positive membership allowlist; every returned object must carry an allowed `driveId`. `blockedSharedDriveIds` explicitly denies membership. Overlapping allow/block sets are invalid rather than ambiguous. IDs are not path prefixes or transport filters.
- `drive.allowMyDrive` defaults to false. When true, only objects with no `driveId` **and `ownedByMe: true`** pass. This intentionally excludes files shared from another person's My Drive, including files placed in shared folders. It is ownership scope, not folder-path scope.
- Shortcut metadata requires both shortcut and target to be governed, with a freshly fetched target. Denied targets are filtered from lists and refuse direct reads. Recursive shortcuts and shortcut content reads are unsupported. Target lookup errors/missing policy facts fail the entire list rather than implying denial was fully checked.
- `calendar.allowedCalendarIds` names exact IDs; aliases `primary` and `me` are refused. IDs are verified before event requests; direct event reads verify the returned event ID. No cross-calendar links are followed.
- Gmail requires an exact `gmail.mailbox` account email and a fixed `allowedMessageIds` allowlist. `users/me/profile` must match the configured mailbox on every invocation before fetching messages. All calls use `users/me`; the agent cannot select another account.
- `gmail.requiredLabelIds` is an optional **all-of narrowing constraint** on allowed messages, never an alternative grant. Labels alone cannot authorize any message. Labels are user-mutable organization attributes, not a confidentiality boundary. A newly labeled message remains denied until an administrator adds its message ID. Search candidates outside the fixed ID list are discarded before metadata fetch; direct paths check selection and live labels. No thread expansion.
- Drive content and Gmail text reads re-fetch policy facts after fetching content and discard output if metadata changes or selection no longer passes. This reduces races but is not a Google-side transactional snapshot: a move away and back between checks cannot be detected. Administrator-enforced Google ACLs, account separation and read-only OAuth grants remain necessary.
- Configuration is copied into immutable sets when tools are constructed and revalidated at every execution. A mutable source JsonNode cannot widen a running tool. A configuration change applies when tools are resolved for a new run; it does not revoke an already-resolved run mid-turn. Stop active runs for immediate revocation.

## Bounds and fail-closed behavior

Calendar event windows must be positive and no longer than exactly `Duration.ofDays(31)` (744 hours). A window exceeding the limit by one nanosecond is refused before any event request; timezone offsets are compared as instants, without day truncation.

`maxPages` defaults to 3 (1–10), `maxItems` to 50 (1–100), `maxOutputChars` to 16384 (1024–65536). Raw bodies are capped at 262144 bytes by the reused bounded reader; text content must fit half the output character budget to leave room for JSON escaping and metadata. Oversized output is refused, never truncated into a partial authorization claim.

Lists consume pagination internally and buffer only within item/page budgets. No raw cursors, total counts, result-size estimates, links or denied entries are returned. A remaining page after the budget, repeated/malformed cursor, malformed entry, `incompleteSearch`, truncated body, or failed later request discards **all** earlier results. `maxItems` counts upstream candidates, including denied candidates; no partial result is presented as complete. Narrow searches if a list hits a limit. The error/latency can still reveal that a request hit a limit or failed; this is not a noninterference system.

Only stable refusal codes are emitted: `POLICY_DENIED`, `INVALID_CONFIG`, `INVALID_INPUT`, `UNSUPPORTED`, `AUTH_MISSING`, `LIMIT_EXCEEDED`, `UPSTREAM_ERROR`. Raw upstream errors, locations, URLs, credentials and exception text are not returned or logged by this plugin. This intentionally sacrifices detailed Google diagnostics. Credential values are checked against successful outputs to prevent accidental echoes; transformed/encoded secrets are not claimed to be universally detectable.

## Native authentication and origins

Bind `authSettingName` to a native **`OAUTH_REGISTERED`** Auth Setting with:

- authorization URL exactly `https://accounts.google.com/o/oauth2/v2/auth`;
- token URL exactly `https://oauth2.googleapis.com/token`;
- nonempty scopes drawn only from `https://www.googleapis.com/auth/drive.readonly`, `https://www.googleapis.com/auth/calendar.readonly`, `https://www.googleapis.com/auth/gmail.readonly`.

Actual scope evidence in the native credential's `scope` field must be nonempty and contain only approved read-only scopes, and the service additionally requires a subset of the bound setting's requested scopes. The service rejects broad, unknown, blank or missing saved grant evidence **before refresh**, including when the setting was narrowed after consent; it does not silently refresh a rejected grant. Reconnect/reconsent with least privilege (revoke the old provider grant if necessary). Editing requested scopes does not downgrade a saved Google grant.

For governed runs only, a successful refresh of the exact native row's refresh token may preserve that row's previously validated granted scope when the response **omits** scope. This is evidence from the same user/setting/refresh grant, not an inference from requested configuration. Explicit blank, null, unknown or broad replacement scopes are rejected before storing or returning the refreshed credential. New authorization requires actual scope evidence; no original-grant evidence is invented. Generic OAuth integrations not bound to governance retain their existing behavior. The plugin independently revalidates approved actual scope evidence on every invocation before resource transport.

Use the narrowest subset needed. Registered OAuth uses AgentOS's native encrypted per-user credential lifecycle, PKCE, offline authorization and refresh; no client secret belongs in integration parameters. Discovery/custom/static credentials are refused for governed integrations by the service **before** invoking the native supplier (checking after refresh would be too late). Google token requests do not follow redirects or retry. A shared Auth Setting bound to Google governance is constrained for the entire run, including other integrations using that same setting.

Tools require a native user UUID, OAuth token whose `Credential.userId` matches that user, and the same user/namespace as tool resolution. The captured integration-specific supplier is used rather than a possibly different invocation supplier. No anonymous or static-token fallback. Account-to-native-user identity is a trusted native OAuth binding; Gmail additionally pins the actual Google account email. Drive/Calendar do not independently equate the native identity email to the Google account email.

Resource requests use only `https://www.googleapis.com:443`, GET, the built-in fixed route set and encoded path/query values. The Google plugin creates its **own strict `HttpClientHolder(OutboundUrlPolicy())`**; the HTTP plugin's permissive startup/environment flag never relaxes Google policy. No redirects or connection retries, 20-second call timeout, public-address DNS policy and JVM TLS/proxy configuration.

### Infrastructure rationale

The plugin depends on the deployed `agentos-http-plugin` through PF4J `Plugin-Dependencies` and Gradle `compileOnly`. It reuses the public `net.HttpClientHolder`, `OutboundUrlPolicy` and `BoundedBodyReader` primitives, not the OpenAPI tool/catalogue/cache/auth/response-shaping pipeline. This avoids copying the HTTP transport or introducing an unconstrained generic proxy. Jackson, OkHttp, Kotlin, SDK and PF4J remain host-provided; klogger follows existing plugin packaging. Deploy both plugin JARs together. Missing HTTP dependency is a PF4J load failure, not an insecure fallback.

A trusted corporate proxy may perform DNS resolution itself; enforce public destination policy at the proxy and preserve Google TLS trust validation. Like all plugins, this code trusts the host runtime, native credential provider and Google service responses. It is not a sandbox against a hostile JVM plugin or administrator.

## Configuration example (synthetic IDs only)

```yaml
name: GOOGLE_TEAM
integrationType: GOOGLE_WORKSPACE
authSettingName: GOOGLE_READ_ONLY
autoGrant: false
parameters:
  drive:
    allowedSharedDriveIds: [example_shared_drive_id]
    blockedSharedDriveIds: [example_blocked_drive_id]
    allowMyDrive: false
  calendar:
    allowedCalendarIds: [team_calendar_id@group.calendar.google.com]
  gmail:
    mailbox: person@example.com
    allowedMessageIds: [example_message_id]
    requiredLabelIds: [Label_example]
  maxPages: 3
  maxItems: 50
  maxOutputChars: 16384
```

Create the IntegrationConfig at platform or namespace-shared scope. `GOOGLE_WORKSPACE` is in the default user-scope-denied list because a personal overlay could widen shared object policy. If you override `AGENTOS_INTEGRATIONS_USER_SCOPE_DENIED_TYPES`, retain `GOOGLE_WORKSPACE`; list replacement otherwise disables that guard. Administrators and filesystem configuration must be trusted. Avoid allowing users to update shared governance configuration. Arrays in overlays replace rather than intersect.

Bind only the tools needed in the agent's integration allowlist. `autoGrant` is not enabled and this network-reaching type is not in the default auto-grantable list.

## Bypass limits

These rules govern **only this plugin's tools**. An agent also given HTTP_API, MCP, BASH, Git, browser tools, file access containing tokens, or another Google connector may bypass this perimeter. Do not bind the same broad credentials or give such integrations to a restricted agent. Remove alternate routes, use least-privilege read-only Google accounts/OAuth scopes and enforce Google ACLs/network policy. This plugin does not globally intercept HTTP, retrofit ACLs onto other plugins, revoke saved Google grants, or make Gmail labels a hard security boundary.

## Build and deploy

```bash
export JAVA_HOME=/data/tools/jdk-25.0.2
export PATH="$JAVA_HOME/bin:$PATH"
./agentos/gradlew -p agentos/agentos-google-workspace-plugin test jar
./agentos/gradlew -p agentos deployPlugins
# Restart AgentOS after deploying all plugin JARs and the updated service.
```

The composite build, `deployPlugins` and GitHub release artifact list include the module. Tests use synthetic injected transports and OkHttp interceptors, not Google accounts or production requests. Service tests exercise the real AgentService credential factory/native OAuth lifecycle with synthetic interceptors, saved and refreshed actual grant restrictions, scope omission preservation, and default overlay denial. Calendar tests cover exact 31-day and plus-one-nanosecond boundaries, including timezone-equivalent endpoints. Deploy the updated service as well as the two plugin JARs: the native pre-refresh guard lives in the service, not in a plugin-local OAuth implementation.
