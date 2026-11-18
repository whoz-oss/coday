# FACTORY_WORKER and FACTORY_WORKSTREAM via HTTP_API (local trial)

Three standard AgentOS `HTTP_API` integrations call Factory directly: `FACTORY_WORKER` (a worker submits one step
result), `FACTORY_WORKSTREAM` (read workflows, start a workflow, request a retry), and `FACTORY_AUTHORING`
(validate and register immutable definitions; no execution). **Nothing is run, built or
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
| `FACTORY_WORKSTREAM` | Read workflows, start a workflow, request a retry | an operator Factory JWT | **Only the steering agents you choose** |
| `FACTORY_AUTHORING` | List/get definitions, validate, register immutable versions | an operator Factory JWT, never an attempt-result capability | **Only trusted authoring agents** |

A Factory JWT is an ordinary operator identity, even if its subject is named `factory-worker`; it is not a
narrow worker capability and currently has no role-based restriction. Do not hand such an operator credential
to untrusted workers assuming it is result-only. Opaque attempt-result capability tokens are not operator JWTs
and cannot authenticate registry requests in `auth` mode. No special admin escalation is introduced here.
In `local` mode there is no credential isolation: all calls are the personal OS user. Do not expose that mode
to untrusted agents or networks. In `auth` mode the existing trusted proxy must strip spoofable identity headers.
The default membership resolver maps operators to the local-dev organization; production tenant mapping must
come from the deployment's trusted membership resolver, not from model arguments.

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

### Explicit route choices

Refresh the existing FACTORY_AUTHORING and FACTORY_WORKER HTTP_API spec caches (or reload the integration) after deploying Factory. No AgentOS/plugin changes are required. Authoring steps optionally carry `decision: {"mode":"single|multiple","routes":[{"name":"frontend","target":"implement-front"},{"name":"backend","target":"implement-back"}]}`; the common step declares `join: "<decision-step-id>"` and depends on every target. Initial scope is one decision group, 2–16 distinct single-step branches and one explicit join, no nested/overlapping/multi-step routes. Ordinary dependencies remain AND.

Worker PASS results of decisions require `selectedRoutes`, e.g. `["frontend"]`, `["backend"]`, or `["frontend","backend"]` in multiple mode. These are names, never target IDs. Omit the field for ordinary steps, FAIL and NEEDS_RESEARCH. Invalid selections are rejected; routing is accepted only after configured validators/oracles succeed and persisted with result/attempt attribution before downstream execution. Nonselected branches remain skipped/never executed; the join waits for every selected success. The workflow authoring skill includes a full canonical example. Scheduling remains sequential.

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

- **Every operation requires an explicit `namespaceId` UUID query parameter** from the assignment. Factory resolves its existing workstream inside the authenticated organization; reads never initialize a mapping. A missing mapping returns `WORKFLOW_NOT_FOUND`; an inactive mapping is refused. `get_workstream` additionally checks that the path workstream matches this mapping.
- **`start_workflow`** creates a normal Factory run. It is not attributed to an AgentOS case. The caller supplies
  an `Idempotency-Key`; Factory namespaces it by the user.
- **`request_agent_retry`** opens at most one retry request per step: a repeated call returns the existing one
  (`200`, `idempotent: true`).
- **`get_required_human_actions`** is information only (`answerableFromThisChannel: false`). Human validations
  stay in Factory.
- Membership resolves the authenticated organization, not an implicit workstream. Agents select the assignment's namespace explicitly; resolution never crosses organizations. Attach this integration only to trusted steering agents.
- The tool output is the Factory payload (bounded, secret-free), not the bridge's reshaped output.

## AgentOS

The actual generic HTTP_API policy permits local/plain HTTP by default. For a local Factory, leave
`AGENTOS_HTTP_API_DISALLOW_UNSECURE` unset or set it to `false`; `true` restricts outbound calls to public HTTPS.
`AGENTOS_HTTP_API_ALLOW_UNSECURE` is not the current setting. Restart AgentOS when changing the policy or deploying
the rebuilt HTTP plugin. Prefer public HTTPS and strict policy for remote deployments.

Factory exposes these read-only spec URLs without a bearer (they are on the authentication filter's public
allow-list):

- `FACTORY_WORKER`: `http://127.0.0.1:8141/api/factory/openapi/factory-worker.yaml`
- `FACTORY_WORKSTREAM`: `http://127.0.0.1:8141/api/factory/openapi/factory-workstream.yaml`
- `FACTORY_AUTHORING`: `http://127.0.0.1:8141/api/factory/openapi/factory-authoring.yaml`

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

## Authoring installation (existing users must opt in)

Rebuild/redeploy Factory (the dedicated spec is packaged by `processResources`) and restart Factory when replacing
its binaries. Use the existing AgentOS `HTTP_API` plugin unchanged. No AgentOS rebuild, new plugin type or automatic
credential/agent migration is needed.

In the intended AgentOS namespace, create an Integration named **FACTORY_AUTHORING**, Type **HTTP_API**, using the
ordinary operator's **BEARER_TOKEN Auth Setting** (token only, never stored in skill/YAML/source). Set Document URL
to `http://127.0.0.1:8141/api/factory/openapi/factory-authoring.yaml`, Base URL to `http://127.0.0.1:8141`, and enable
**Allow mutations**. Keep all operation/tag/path filters empty. Use your deployed HTTPS addresses instead when remote.
The saved config payload (not a complete Integration entity, and containing no credential) is:

```json
{
  "spec": {"url": "http://127.0.0.1:8141/api/factory/openapi/factory-authoring.yaml"},
  "baseUrl": "http://127.0.0.1:8141",
  "allowMutations": true
}
```

Use the existing per-agent tool allowlist to restrict authoring tools to trusted agents. Standard HTTP_API
confirmation semantics remain unchanged: GET operations need no confirmation; non-GET operations request
confirmation on every call in the advanced agent. Simple agents do not enforce that confirmation, so restrict
write tools with the per-agent allowlist. Enabling mutations, attaching an integration or authorizing registration
does not bypass tool confirmation gates or authorize launch. The skill and OpenAPI document cannot bypass them.

Explicitly attach **FACTORY_AUTHORING** to the authoring agent through the AgentOS agent editor. Attach
**FACTORY_WORKSTREAM** separately only if the agent should be able to launch/inspect runs. Without an explicit
integration binding the runtime grants none of its tools. Expected provider log:
`HTTP_API integration 'FACTORY_AUTHORING': providing 4 tool(s)`:

| Tool | Route |
|---|---|
| `FACTORY_AUTHORING__list_definitions` | `GET /api/factory/workflow-definitions` |
| `FACTORY_AUTHORING__get_definition` | `GET /api/factory/workflow-definitions/{workflowType}/{version}` |
| `FACTORY_AUTHORING__validate_definition` | `POST /api/factory/workflow-definitions/validate` |
| `FACTORY_AUTHORING__register_definition` | `POST /api/factory/workflow-definitions` |

Install the existing `factory-workflow-authoring` skill via **one** established mechanism:

- Filesystem namespace: copy `factory/tools/skills/factory-workflow-authoring/` to
  `<namespace.configPath>/skills/factory-workflow-authoring/` (or use `factory/tools` as that namespace's configPath
  if appropriate). Add `factory-workflow-authoring` to the existing agent's `skillSelectors`; preserve other selectors
  and integration bindings. Discovery is flat `skills/<name>/SKILL.md`, cached up to five minutes.
- Persisted namespace: as a namespace admin, create/update a Skill through the AgentOS Skills UI or real
  `POST /api/skills` with `namespaceId`, `name`, `description`, `body` (Markdown body without the frontmatter).
  Select its exact name in the agent's skill picker (`skillSelectors`). No manual workflow-definition file import
  is involved; this installs reusable guidance, not a workflow.

Verify `GET /api/skills/effective?namespaceId=<uuid>` offers that name, and the agent definition shows the skill
selector and integration bindings. Recreate/reload the agent on the next invocation after config changes; existing
instantiated sessions may retain their prior tool set. New deployments should verify the four live tool names,
not merely the presence of a skill file. The `readSkill` tool loads the selected skill body on demand.

The skill contains exact structured `body` examples for validation→registration→**separate explicitly requested**
launch. An identical canonical registration replay returns the same type/version/hash (`201`); differing content
at that identity is `409`, never replacement. The optional UI `If-None-Match: *` precondition is intentionally not
exposed by the authoring tool because it rejects even identical replays. Definitions remain organization-wide;
launch requires `namespaceId` mapped in that organization, and the workstream's allowed types remain enforced.
`FACTORY_WORKSTREAM__start_workflow` now accepts `definitionVersion` plus `definitionHash` together in `body`;
use the exact registration response. Old callers without pins retain legacy behavior. Refresh the workstream spec
cache (default 60 minutes, or restart/config change) to see the new fields. Registration never creates a run.

## Source tests you run yourself

```bash
./factory/gradlew -p factory :factory-service:test \
  --tests '*AuthenticationFilterTest' \
  --tests '*SecurityModeBannerTest' \
  --tests '*WorkstreamServiceControllerHttpTest' \
  --tests '*AgentStepResultControllerHttpTest' \
  --tests '*FactoryOpenApiControllerHttpTest' \
  --tests '*WorkflowDefinitionAdminIntegrationTest' \
  --tests '*WorkflowDefinitionAuthoringAuthTest' \
  --console=plain

./agentos/gradlew -p agentos :agentos-http-plugin:test \
  --tests '*HttpApiToolProviderUnitSpec' \
  --tests '*HttpApiConfigSchemaUnitSpec' \
  --console=plain
```

## Not done / to verify

- `factory-openapi.yaml` (the generated Factory spec) is regenerated with
  `nx run factory-service:generate-openapi-spec` (needs a forked boot, so it is not part of the unit tests).
- A non-assigned integration exposing no tools is AgentOS agent-binding behaviour; verify it with one agent that
  has the integration and one that does not.
- Plain HTTP/private destinations follow `AGENTOS_HTTP_API_DISALLOW_UNSECURE` (unset/false allows them;
  true restricts to public HTTPS). This is not restricted to loopback.
