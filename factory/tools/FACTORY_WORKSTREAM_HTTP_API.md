# FACTORY_WORKSTREAM via HTTP_API (local trial)

Second AgentOS `HTTP_API` integration, next to the worker `FACTORY_WORKER` one. It replaces the eight tools of
the former bridge `FACTORY_WORKSTREAM` integration. **Nothing is run, built or configured by this document;
it lists what you do yourself.** Both integrations call Factory directly.

## Two integrations, two credentials

| Integration | Purpose | Credential | Attached to |
|---|---|---|---|
| `FACTORY_WORKER` | Worker submits one step result | `FACTORY_WORKER_API_SUBMISSION_BEARER_TOKEN` | Factory workers |
| `FACTORY_WORKSTREAM` | Read workflows, start a workflow, request a retry | `FACTORY_WORKSTREAM_SERVICE_BEARER_TOKEN` | **Only the steering agents you choose** |

Attaching an integration to an agent is explicit in the AgentOS UI. Creating `FACTORY_WORKSTREAM` makes it
available; it does not add it to any agent.

## Old tool -> new tool

| Former bridge tool | HTTP_API tool (integration `FACTORY_WORKSTREAM`) | Route | Permission |
|---|---|---|---|
| `get_workflow` | `FACTORY_WORKSTREAM__get_workflow` | `GET /api/factory/workstream-service/workflows/{workflowId}` | `read` |
| `get_workstream` | `FACTORY_WORKSTREAM__get_workstream` | `GET /api/factory/workstream-service/workstreams/{workstreamId}` | `read` |
| `list_workflows` | `FACTORY_WORKSTREAM__list_workflows` | `GET /api/factory/workstream-service/workflows` | `read` |
| `get_step_attempts` | `FACTORY_WORKSTREAM__get_step_attempts` | `GET .../workflows/{workflowId}/attempts` | `read` |
| `get_blockers` | `FACTORY_WORKSTREAM__get_blockers` | `GET .../workflows/{workflowId}/blockers` | `read` |
| `get_required_human_actions` | `FACTORY_WORKSTREAM__get_required_human_actions` | `GET .../workflows/{workflowId}/human-actions` | `read` |
| `start_workflow` | `FACTORY_WORKSTREAM__start_workflow` | `POST .../workflows` | `start_workflow` |
| `request_agent_retry` | `FACTORY_WORKSTREAM__request_agent_retry` | `POST .../workflows/{workflowId}/retries` | `request_retry` |

The OpenAPI document is `factory/factory-service/openapi/factory-workstream-http-api.yaml`.

## What changed in behaviour

- **Namespace is owned by the registered workstream.** Factory resolves the single AgentOS namespace from
  `WorkstreamNode.namespaceId`; the model neither sees nor chooses it. A missing, inactive or invalid mapping
  fails closed with `WORKSTREAM_NAMESPACE_MAPPING_INVALID`.
- **`start_workflow`** creates a normal Factory run. It is not attributed to an AgentOS case. The caller supplies
  an `Idempotency-Key`; Factory namespaces it by the service identity.
- **`request_agent_retry`** opens at most one retry request per step: a repeated call returns the existing one
  (`200`, `idempotent: true`). The model-authored `idempotencyKey` of the old tool is gone.
- **`get_required_human_actions`** is information only (`answerableFromThisChannel: false`). Human validations
  stay in Factory.
- **`get_workflow`** advertises a retry action only when the credential actually holds the retry permission.
- The tool output is the Factory payload (bounded, secret-free), not the bridge's reshaped output. Prompts that
  depended on the exact old output shape must be reviewed.

## Authorization model and its limits

- The credential is a **service identity**. Its organization/workstream and permissions are **Factory server
  configuration**, validated at startup (an invalid enabled configuration refuses to start).
- **All agents the integration is attached to share these service rights.** The credential does not carry the
  identity or rights of the person talking to the agent, and it does **not** prove which case or agent made the
  call. Attach it only to agents you trust with read, start and (if granted) retry on that workstream.
- Workflow, step and workstream identifiers supplied by the model are never permissions. The namespace is
  not model input: it comes exclusively from the configured workstream registry entry.
- The credential can reach **only** `/api/factory/workstream-service/**`; a Factory servlet filter enforces
  the method and route allow-list before any controller runs. It cannot submit a step result, and the worker
  credential cannot reach the facade.
- `get_workstream` only accepts the workstream configured for the service.

## Factory configuration

Generate a token **different from the worker token**, then export (same terminal as `bootRun`):

```bash
export FACTORY_WORKSTREAM_SERVICE_BEARER_TOKEN="$(openssl rand -hex 32)"
export FACTORY_WORKSTREAM_SERVICE_ENABLED=true
export FACTORY_WORKSTREAM_SERVICE_IDENTITY=agentos-workstream
export FACTORY_WORKSTREAM_SERVICE_ORGANIZATION_ID="<your organization id>"
export FACTORY_WORKSTREAM_SERVICE_WORKSTREAM_ID="<your workstream id>"
export FACTORY_WORKSTREAM_SERVICE_PERMISSIONS="read,start_workflow,request_retry"   # grant only what you need
```

Keep the worker variables (`FACTORY_WORKER_API_SUBMISSION_*`) exported as well: they are independent.
For the local dev tenant the defaults are `org-local-dev` / `ws-default`.

## AgentOS

Restart AgentOS with its generic HTTP_API loopback exception enabled:

```bash
export AGENTOS_HTTP_API_ALLOW_LOCAL_HTTP=true
# start AgentOS with your usual command
```

Factory exposes these two read-only spec URLs without a bearer:

- `FACTORY_WORKER`: `http://127.0.0.1:8141/api/factory/openapi/factory-worker.yaml`
- `FACTORY_WORKSTREAM`: `http://127.0.0.1:8141/api/factory/openapi/factory-workstream.yaml`

They serve the versioned YAML resources packaged by Factory. Business operations still require their separate
bearers and are protected directly by Factory. Both integrations remain standard AgentOS `HTTP_API`
integrations; AgentOS has no Factory-specific routing or policy.

In the AgentOS UI:

1. **Auth Setting**: new `BEARER_TOKEN` setting holding the **workstream** token (no `Bearer ` prefix). Do not
   reuse the worker setting.
2. **Integration**: Name `FACTORY_WORKSTREAM` (exactly), Type `http api`, Auth Setting = the one above,
   Document URL = `http://127.0.0.1:8141/api/factory/openapi/factory-workstream.yaml`, inline document and
   spec file empty, Base URL `http://127.0.0.1:8141`, **Allow mutations** checked, every filter list empty
   (no empty row).
3. Configure `FACTORY_WORKER` the same way with Document URL
   `http://127.0.0.1:8141/api/factory/openapi/factory-worker.yaml`, Base URL `http://127.0.0.1:8141`, and its
   own worker bearer setting.
4. **Agents**: add `FACTORY_WORKSTREAM` explicitly to the steering agents only. Expected log:
   `HTTP_API integration 'FACTORY_WORKSTREAM': providing 8 tool(s)`.

## Source tests you run yourself

```bash
# Factory (focused)
./factory/gradlew -p factory :factory-service:test \
  --tests '*WorkstreamServiceAccessRulesTest' \
  --tests '*StaticServiceRouteGuardFilterTest' \
  --tests '*TrustContextExtractorTest' \
  --tests '*WorkstreamServiceControllerHttpTest' \
  --tests '*AgentStepResultControllerHttpTest' \
  --tests '*FactoryOpenApiControllerHttpTest' \
  --console=plain
```

## Not done / to verify

- Nothing here has been compiled or executed. Treat every file as unverified until your test run.
- `factory-openapi.yaml` (the generated Factory spec) is not regenerated; run
  `nx run factory-service:generate-openapi-spec` if your CI checks it.
- A non-assigned integration exposing no tools is AgentOS agent-binding behaviour; it is not covered by a
  source test here. Verify it with one agent that has the integration and one that does not.
- `FACTORY_WORKER` and `FACTORY_WORKSTREAM` remain ordinary HTTP_API integrations; no Factory-specific
  AgentOS behavior is part of this change.
- Direct local HTTP is loopback-only and must be explicitly enabled in AgentOS with
  `AGENTOS_HTTP_API_ALLOW_LOCAL_HTTP=true`.
