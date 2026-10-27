# FACTORY_WORKER and FACTORY_WORKSTREAM via HTTP_API (local trial)

Two standard AgentOS `HTTP_API` integrations call Factory directly: `FACTORY_WORKER` (a worker submits one step
result) and `FACTORY_WORKSTREAM` (read workflows, start a workflow, request a retry). **Nothing is run, built or
configured by this document; it lists what you do yourself.**

## One credential for both

Factory resolves the caller of every `/api/**` call according to its security mode, the same `FACTORY_SECURITY_MODE`
variable (`local` by default, or `auth`) that AgentOS reads. In `auth` mode the credential is
`Authorization: Bearer <HS256 JWT>`, signed with `FACTORY_FAKE_IDP_SECRET` (`factory.security.fake-idp-secret`). The
two integrations therefore need no dedicated token or server setting: the AgentOS Auth Setting of each simply holds
a Factory JWT. There is no authorization on top: any authenticated user can use every route.

| Integration | Purpose | Credential | Attached to |
|---|---|---|---|
| `FACTORY_WORKER` | Worker submits one step result | a Factory JWT | Factory workers |
| `FACTORY_WORKSTREAM` | Read workflows, start a workflow, request a retry | a Factory JWT | **Only the steering agents you choose** |

**Security modes** (`FACTORY_SECURITY_MODE`, default `local`):

- **`local`**: no credential is needed. Every header and bearer is ignored, so an Auth Setting that still holds an old
  static token keeps working, and the caller is always the OS user running Factory (never a `401`). A system account
  (`root`, `daemon`, `www-data`, ...) is refused with `403 FORBIDDEN_SYSTEM_ACCOUNT`: start Factory under a personal
  user. Meant for a single-user machine; Factory logs a warning when it is bound to a non-loopback address.
- **`auth`**: the caller comes from, in order, a valid Factory JWT (the Auth Setting of these integrations),
  `X-External-User-Id`, the `email` of `Cf-Access-Jwt-Assertion`, the `preferred_username` of any other bearer, or
  `x-forwarded-email`. The last four are decoded, not signature-checked (as in AgentOS), so run Factory behind a proxy
  that authenticates callers and strips these headers. Without any of them the answer is
  `401 UNAUTHENTICATED`, before any controller runs.

`FACTORY_INSECURE_MODE` / `factory.security.insecure-mode` no longer exist: if either is set, Factory refuses to
start.

Attaching an integration to an agent is explicit in the AgentOS UI. Creating `FACTORY_WORKSTREAM` makes it
available; it does not add it to any agent.

## Minting a token

Use the same secret as the Factory server, then paste the printed token (no `Bearer ` prefix) into the AgentOS
Auth Setting:

```bash
export FACTORY_FAKE_IDP_SECRET="<the secret the Factory server uses>"
./factory/gradlew -p factory :factory-service:mintToken -Psub=factory-worker -PexpiryDays=365 --quiet
```

`-Psub` is the user id the token acts as (it ends up as the actor of audit fields); `-PexpiryDays` defaults to 365.
The same token can serve both integrations, or mint one per integration to tell them apart in the audit trail.

## Worker contract

`POST /api/factory/agent-step-results` with `{ "attemptId": ..., "result": { ... } }` and an `Idempotency-Key`
header (`factory/factory-service/openapi/factory-worker-http-api-submit-step-result.yaml`). Factory resolves the
workflow, step and namespace from the durable attempt, within the organization of the caller, so the worker passes
nothing else. The brief given to the worker contains its `attemptId` and `Idempotency-Key`.

## Old tool -> new tool

| Former bridge tool | HTTP_API tool (integration `FACTORY_WORKSTREAM`) | Route |
|---|---|---|
| `get_workflow` | `FACTORY_WORKSTREAM__get_workflow` | `GET /api/factory/workstream-service/workflows/{workflowId}` |
| `get_workstream` | `FACTORY_WORKSTREAM__get_workstream` | `GET /api/factory/workstream-service/workstreams/{workstreamId}` |
| `list_workflows` | `FACTORY_WORKSTREAM__list_workflows` | `GET /api/factory/workstream-service/workflows` |
| `get_step_attempts` | `FACTORY_WORKSTREAM__get_step_attempts` | `GET .../workflows/{workflowId}/attempts` |
| `get_blockers` | `FACTORY_WORKSTREAM__get_blockers` | `GET .../workflows/{workflowId}/blockers` |
| `get_required_human_actions` | `FACTORY_WORKSTREAM__get_required_human_actions` | `GET .../workflows/{workflowId}/human-actions` |
| `start_workflow` | `FACTORY_WORKSTREAM__start_workflow` | `POST .../workflows` |
| `request_agent_retry` | `FACTORY_WORKSTREAM__request_agent_retry` | `POST .../workflows/{workflowId}/retries` |

The OpenAPI document is `factory/factory-service/openapi/factory-workstream-http-api.yaml`.

## Behaviour

- **Namespace is owned by the registered workstream.** Factory resolves the single AgentOS namespace from
  `WorkstreamNode.namespaceId` of the caller's tenant; the model neither sees nor chooses it. A missing, inactive
  or invalid mapping fails closed with `WORKSTREAM_NAMESPACE_MAPPING_INVALID`.
- **`start_workflow`** creates a normal Factory run. It is not attributed to an AgentOS case. The caller supplies
  an `Idempotency-Key`; Factory namespaces it by the user.
- **`request_agent_retry`** opens at most one retry request per step: a repeated call returns the existing one
  (`200`, `idempotent: true`).
- **`get_required_human_actions`** is information only (`answerableFromThisChannel: false`). Human validations
  stay in Factory.
- All users of a deployment share one tenant (`factory.tenant.*` / the `MembershipResolver`), so every agent the
  integration is attached to can read, start and retry on that workstream. Attach it only to agents you trust.
- The tool output is the Factory payload (bounded, secret-free), not the bridge's reshaped output.

## AgentOS

AgentOS allows plain HTTP and loopback destinations for `HTTP_API` by default, so the `http://127.0.0.1:8141`
URLs below need no environment variable. They will not work if AgentOS runs with
`AGENTOS_HTTP_API_DISALLOW_UNSECURE=true` (strict HTTPS-only policy): unset it for a local trial.

Factory exposes these two read-only spec URLs without a bearer (they are on the authentication filter's public
allow-list):

- `FACTORY_WORKER`: `http://127.0.0.1:8141/api/factory/openapi/factory-worker.yaml`
- `FACTORY_WORKSTREAM`: `http://127.0.0.1:8141/api/factory/openapi/factory-workstream.yaml`

In the AgentOS UI:

1. **Auth Setting**: new `BEARER_TOKEN` setting holding the Factory JWT (no `Bearer ` prefix). In `auth` mode it is
   what identifies the worker. In `local` mode it may be left empty or hold anything: the caller is the OS user.
2. **Integration**: Name `FACTORY_WORKSTREAM` (exactly), Type `http api`, Auth Setting = the one above,
   Document URL = `http://127.0.0.1:8141/api/factory/openapi/factory-workstream.yaml`, inline document and
   spec file empty, Base URL `http://127.0.0.1:8141`, **Allow mutations** checked, every filter list empty
   (no empty row).
3. Configure `FACTORY_WORKER` the same way with Document URL
   `http://127.0.0.1:8141/api/factory/openapi/factory-worker.yaml` and Base URL `http://127.0.0.1:8141`.
4. **Agents**: add `FACTORY_WORKSTREAM` explicitly to the steering agents only. Expected log:
   `HTTP_API integration 'FACTORY_WORKSTREAM': providing 8 tool(s)`.

## Source tests you run yourself

```bash
./factory/gradlew -p factory :factory-service:test \
  --tests '*AuthenticationFilterTest' \
  --tests '*SecurityModeBannerTest' \
  --tests '*WorkstreamServiceControllerHttpTest' \
  --tests '*AgentStepResultControllerHttpTest' \
  --tests '*FactoryOpenApiControllerHttpTest' \
  --console=plain
```

## Not done / to verify

- `factory-openapi.yaml` (the generated Factory spec) is regenerated with
  `nx run factory-service:generate-openapi-spec` (needs a forked boot, so it is not part of the unit tests).
- A non-assigned integration exposing no tools is AgentOS agent-binding behaviour; verify it with one agent that
  has the integration and one that does not.
- Direct local HTTP works by default in AgentOS; it is refused when `AGENTOS_HTTP_API_DISALLOW_UNSECURE=true`.
