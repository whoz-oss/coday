# Milestone D Wave 3 Step 1: admin and case links

## What changed

The Factory Cockpit now has an `/admin` view for artifact governance and a shared, SSRF-safe component for workflow case/thread identities.

- `factory/dashboard/js/views/artifact-admin.mjs` adds the `#view-admin` mountable view. It calls the existing admin API with `POST` requests for garbage collection, artifact purge, and legal-hold changes. GC supports forwarding `dryRun`; purge sends an optional `reason`; legal-hold sends `legalHold` and an optional `reason`. The view renders GC counts/report data, purge status and freed metadata size, and legal-hold state. Purge uses a native-dialog confirmation flow, and the returned handle provides `mount`/`unmount` lifecycle behavior, rendering, state access, and teardown registration.
- Admin entitlement handling is deliberately only a UI gate: it uses an optional profile/trust-context signal and never reads request headers. Unknown profiles fail open to the server. A `403` or `FORBIDDEN_ADMIN_REQUIRED` response becomes an escaped, visible French error message containing the machine code and disables the admin controls.
- `factory/dashboard/js/app.mjs` registers `mountArtifactAdminView` for the `/admin` view route.
- `factory/dashboard/js/components/case-link.mjs` centralizes rendering of `controllerExecution` identities. AgentOS cases become links only when `agentosUrl` parses as a credential-free HTTP(S) base; case IDs and thread IDs are encoded as path segments. Coday Express threads remain readable `Thread <id>` text unless a similarly trusted `codayExpressUrl` is supplied. Invalid/missing bases fall back to inert spans. Both escaped HTML rendering and DOM-element creation are provided.
- `factory/dashboard/js/components/workflow-card.mjs` delegates identity rendering to the new component while preserving its existing AgentOS URL exports. `factory/dashboard/js/views/projection.mjs` resolves and caches both configured bases from `/api/config` (with compatibility getters) and passes them to cards. `factory/dashboard/js/views/run-detail.mjs` displays the controller identity through the same component.
- `factory/dashboard/openapi.json` updates the API version to `6.0.0-b6`, documents the three admin POST routes and their request/error responses, adds an `admin` tag, describes the B5/B6 entitlement behavior, and records the unprefixed forge aliases as TODO/legacy documentation.
- `factory/tests/test-cockpit-admin-and-case-link.mjs` adds an offline Node test suite covering safe link construction and escaping, workflow/projection/run-detail integration, admin rendering and API payloads, dry-run, confirmation, 403 handling, and mount/unmount/router behavior.
- `specs/d2b4706d_admin_and_case_link.md` records the implementation plan and verification checklist for this change.

## How to verify/use

Run the focused offline suite from the repository root:

```bash
node factory/tests/test-cockpit-admin-and-case-link.mjs
```

The view is mounted by the cockpit router at `/admin`. Hosts should provide the normal `apiClient`; optional `profile`, `user`, or `trustContext` values can provide a UI entitlement hint. Destructive purge operations require the view's confirmation flow (or an injected `confirm` function in tests/hosts). Case links should receive only the server-configured `agentosUrl` and, when available, `codayExpressUrl` from `/api/config`; arbitrary IDs are never treated as URL bases.

The added test file also exercises the relevant existing cockpit integrations. The diff does not include a recorded test-run result, so the command above is the verification step rather than a claimed execution result.
