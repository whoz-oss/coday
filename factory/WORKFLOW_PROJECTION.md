# WorkflowProjection v1

Stage 1 provides a generic, Forge-independent workflow projection contract and filesystem store. It has no HTTP, AgentOS, UI, watcher, Git, or worktree integration.

## Contract

A publication contains `schemaVersion: "1"`, safe bounded `workflowId` and `workflowType`, bounded `title`, an allowed workflow `status`, and bounded steps. Each step has a safe stable `id`, bounded `name`, allowed `status`, optional bounded `description`, and optional dependencies. Validation rejects malformed or oversized values, unsafe IDs, duplicate step IDs, absent dependency targets, self-dependencies, and cycles with structured machine error codes.

`expectedRevision` is an optional non-negative integer command precondition. It is removed from normalized projection state and therefore never affects canonical equality or SHA-256 hashing. Object keys are recursively sorted; array order remains semantic.

## Public API

- `validateWorkflowProjection(input)`, `canonicalizeWorkflowProjection(projection)`, and `hashWorkflowProjection(projection)` from `lib/workflow-projection.mjs`.
- `new WorkflowProjectionStore(dataRoot)` from `lib/workflow-projection-store.mjs`.
- `store.initialize()`, `store.publish(namespaceId, command, attribution?)`, `store.read(namespaceId, workflowId)`, and `store.list(namespaceId)`.

The data root is always explicit. A later composition layer may pass `FACTORY_DATA_ROOT`; the store never infers a root from the current working directory.

## Storage and concurrency

```text
<dataRoot>/
  workflows/<namespaceId>/<sha256(namespaceId + ':' + workflowId)>/
    projection.json
    events.jsonl
    pending.json       # exists only during an incomplete publication
  trash/               # reserved for a later lifecycle stage
  tombstones/          # reserved for a later lifecycle stage
```

All operations are namespace-scoped and raw workflow IDs are never path components. Snapshot and pending writes use a same-directory temporary file, file sync, rename, and directory sync. Journal records contain only facts: event kind, revision, projection hash, changed step IDs, timestamp, and allow-listed runtime attribution (`actorId`, `agentId`, `caseId`, `runId`).

Publications for one namespace/workflow are serialized in-process. This is deliberately a mono-process-writer v1 design; multiple writer processes are unsupported.

## Write ordering and crash recovery

Changed publications use a small intent protocol:

1. atomically write and sync `pending.json` with the complete next snapshot;
2. append and sync the publication fact to `events.jsonl`;
3. atomically write and sync `projection.json`;
4. remove `pending.json`.

The method reports success only after the durable journal and snapshot exist. On the next read, list, or publication, recovery examines `pending.json`: if the matching durable journal fact exists it completes the snapshot rename; otherwise it discards the uncommitted intent. Malformed snapshots, intents, or journal lines fail closed as storage errors. This keeps recovery deterministic without pretending to provide multi-process transactions.

An identical semantic publication returns `changed: false` without a journal append or revision increment. A stale `expectedRevision` returns `REVISION_CONFLICT` before any write.

## Stage 2 HTTP API

The dashboard server composes the store using:

```text
FACTORY_DATA_ROOT=<explicit path>
```

When unset, the documented local default is `~/.coday/factory`, resolved from the operating-system home directory. It is never derived from cwd, repository roots, namespace `configPath`, or a target checkout. The selected root is printed at server startup.

### Publish

```http
PUT /api/factory/workflows/:workflowId/projection
Content-Type: application/json

{
  "projection": { "schemaVersion": "1", "workflowId": "...", "...": "..." },
  "execution": {
    "namespaceId": "UUID",
    "actorId": "optional-machine-id",
    "agentId": "optional-machine-id",
    "caseId": "optional-machine-id",
    "runId": "optional-machine-id"
  }
}
```

The path ID must equal `projection.workflowId`. `execution.namespaceId` is mandatory and is the trusted namespace attribution for this first server contract. The server accepts only the listed execution fields and bounded machine-safe values; unknown fields and free prose are rejected. The store independently allow-lists the four optional attribution fields before journaling.

Success is `201` for creation and `200` for updates or idempotent publication:

```json
{"data":{"namespaceId":"...","changed":true,"workflowId":"...","revision":1,"projectionHash":"...","projection":{}}}
```

### List and detail

```http
GET /api/factory/workflows?namespaceId=<uuid>&state=active
GET /api/factory/workflows/:workflowId?namespaceId=<uuid>
```

Only `state=active` is currently supported. Every read requires a valid namespace UUID. List responses use:

```json
{"data":{"namespaceId":"...","state":"active","items":[]}}
```

Detail responses use the same item fields as publication, without `changed`.

### Errors and trust boundary

Errors have a stable shape:

```json
{"error":{"code":"MACHINE_CODE","message":"safe public message"}}
```

Malformed input and unsupported state return `400`, stale revisions return `409`, and missing projections return `404`. Corrupt, pending/inconsistent, or unexpectedly failing persistence returns a non-leaking `500 WORKFLOW_STORAGE_FAILURE`; detailed context is written only to the server log.

For Stage 2, the HTTP caller supplying `execution` is assumed to be a trusted Factory runtime. There is not yet authentication or AgentOS identity verification in this endpoint. It must not be exposed to untrusted callers until that boundary is replaced. No SSE or notification is emitted in this stage.

## Stage 3 AgentOS tool

AgentOS exposes the built-in Spring `ToolPlugin` tool `FACTORY__publish_projection`. A PF4J plugin is intentionally not used: this is a service-internal HTTP bridge with server configuration and no independently deployed integration artifact.

Configure the server-side endpoint with:

```text
AGENTOS_FACTORY_BASE_URL=http://localhost:3141
```

The corresponding property is `agentos.factory.base-url`; localhost is the local-development default. The URL and all execution attribution are absent from the model input schema.

Access is explicit per specialized agent. Add this integration grant to its AgentConfig/YAML:

```yaml
integrations:
  FACTORY:
    - publish_projection
```

An absent `FACTORY` key or an empty list grants nothing. The resolved tool name remains exactly `FACTORY__publish_projection`.

The tool performs local deterministic v1 validation before HTTP publication. This Kotlin validation mirrors the Node limits and graph rules, but Factory remains the authoritative validator because Kotlin cannot import the dependency-free Node module.

Execution attribution is built exclusively from `ToolContext`: `namespaceId`, user external identity (falling back to user UUID), and `agentName`. The current SDK `ToolContext` has no direct `caseId`; temporarily, the tool requires all non-empty `caseEvents` to carry one consistent case ID. Empty or inconsistent histories fail closed with `CASE_CONTEXT_UNAVAILABLE`. No guessed ID is sent.

Success returns structured JSON and metadata containing `workflowId`, `revision`, `changed`, and an AgentOS-observed `updatedAt`. Factory's current Stage 2 response does not expose a persisted timestamp, so `updatedAt` is the tool response observation time rather than projection storage time. Known Factory machine errors are preserved in `ToolExecutionResult.error`; network, timeout, and malformed responses use safe codes without raw exception details.

## Stage 4 SSE notifications

```http
GET /api/factory/workflows/stream?namespaceId=<uuid>
Accept: text/event-stream
```

A valid namespace UUID is required before SSE headers are opened. Connections receive heartbeat comments every 30 seconds. On each durably successful changed publication, the server emits exactly one namespace-scoped invalidation hint:

```text
event: workflow-projection-updated
data: {"workflowId":"...","namespaceId":"...","revision":2}
```

The event is sent only after `WorkflowProjectionStore.publish()` returns `ok:true, changed:true`; idempotent, conflicting, invalid, and failed publications emit nothing. The full projection is deliberately omitted. Clients must refresh through list/detail HTTP APIs.

There is no replay or event cursor in v1. On initial connection and every reconnect, HTTP list/detail is authoritative and closes any notification gap. Delivery is best-effort and in-memory: a server restart or disconnected client may miss hints. The hub shares the Stage 1 mono-process boundary and is intentionally separate from Forge Gantt SSE. Dead writers and closed/error connections are removed without disrupting peers.

## Stage 6A lifecycle

Lifecycle is namespace-scoped: `ACTIVE -> REMOVED -> ACTIVE` through restore, and `REMOVED -> PURGED` through purge. It covers only Factory projection/history files. It never deletes BMAD, Jira, CRM, Git/worktrees, AgentOS cases, or conversations. Raw workflow IDs remain absent from paths; trash directories and tombstone filenames use the deterministic safe storage ID.

Removal is serialized with publication on the same namespace/workflow lock. It first recovers any committed pending publication, validates active state, atomically writes a `lifecycleState: "removing"` tombstone/intention, atomically renames the complete workflow directory from `workflows/` to `trash/`, syncs both parents, appends and syncs one generation-scoped facts-only `workflow_removed` record in the **trash** journal, and atomically finalizes the tombstone as `lifecycleState: "removed"`. Both roots are children of the same explicit data root; cross-device copy/delete is not used. Success and SSE occur only after finalization. The active journal therefore never claims completed removal.

Retries converge at every boundary. A `removing` tombstone with an active directory continues the rename; with a trash directory it checks the generation-scoped removal fact, appends it only when absent, then finalizes. Active+trash or neither directory fails closed as corruption. Publication is blocked from the moment any valid tombstone exists. Deterministic test-only fault seams cover post-recovery, post-tombstone, post-rename, post-fact, and post-finalization boundaries.

A tombstone is an atomic JSON file keyed by storage ID. It contains only namespace ID, workflow ID, storage ID, removal timestamp, optional machine-safe actor attribution, lifecycle generation, lifecycle state (`removing`, `removed`, or `purged`), and optional purge metadata. Normal publication checks it before creating a directory, journal, intent, or snapshot and returns `WORKFLOW_REMOVED`.

Restore requires both removed data and its tombstone. It atomically moves the directory active, syncs both parents, appends and syncs `workflow_restored`, then removes and directory-syncs the tombstone. Thus failures remain blocked until active state and its restoration fact are durable. A crash after the move can be completed by retry; duplicate restoration facts are possible in that narrow recovery window and are factual retry records, not projection revisions.

Purge applies only to removed workflows. It first atomically records `purgedAt` and optional `purgedBy` in the tombstone, then recursively deletes the Factory trash directory and syncs its parent. This ordering keeps recreation blocked if deletion is interrupted; retry completes deletion idempotently. The tombstone is the only retained purge metadata and is not claimed as an external audit system. `clearPurgedTombstone(namespaceId, workflowId)` is an explicit internal/admin store operation that permits intentional recreation only after purge; it is not exposed over HTTP.

`purgeRemovedBefore(namespaceId, cutoff, attribution?)` is the callable retention primitive. It returns structured examined/eligible/purged/already-purged counts and per-workflow purge results, remains namespace-safe, ignores newer entries, tolerates already-purged tombstones, and fails closed on corrupt tombstones or removed snapshots. A 30-day policy is the intended default for a future scheduler, but Stage 6A adds no timer or background job.

HTTP lifecycle contracts are:

```text
DELETE /api/factory/workflows/:workflowId?namespaceId=<uuid>
POST   /api/factory/workflows/:workflowId/restore?namespaceId=<uuid>
DELETE /api/factory/workflows/:workflowId/purge?namespaceId=<uuid>
GET    /api/factory/workflows?namespaceId=<uuid>&state=removed
```

Lifecycle bodies are optional and accept only `{ "actorId": "machine-safe-id" }`; unknown fields and prose are rejected. Success and error envelopes remain `{data:...}` and `{error:{code,message}}`. Missing state is `404`, invalid transitions and tombstoned publication are `409`, invalid input is `400`, and non-leaking persistence failure is `500`.

After durable success, the SSE hub emits respectively `workflow-projection-removed` with workflow/namespace IDs, `workflow-projection-restored` with workflow/namespace IDs and revision, and `workflow-projection-purged` with workflow/namespace IDs. Failures emit nothing. These remain best-effort invalidation hints without replay; HTTP state is authoritative.

### Local bind trust boundary

The dashboard's unauthenticated write and lifecycle routes bind to explicit loopback `127.0.0.1` by default. `FACTORY_BIND_HOST` may select `127.0.0.1`, `::1`, or `localhost` without weakening this boundary, preserving Angular's local proxy and AgentOS localhost calls. Any non-loopback value is rejected at startup unless `FACTORY_UNSAFE_ALLOW_REMOTE_BIND=true` is explicitly set. Startup logs both host and trust mode, and labels the remote mode as unsafe and unauthenticated. This is a technical checkpoint boundary, not full authentication.

Recovery remains deliberately local and fail-closed, not transactional across processes. The store assumes one writer process; lifecycle and publication locks do not coordinate multiple Node processes or external filesystem mutation.

## Vertical smoke: specialized AgentOS publisher

AgentOS filesystem agents are loaded from `<namespace.configPath>/agents/*.yaml`, but this smoke deliberately uses the existing AgentConfig REST API so it does not depend on a particular namespace checkout and requires no manual Neo4j change. The dependency-free, idempotent provisioner creates `workflow-projection-smoke` in namespace `0d4bd471-df37-43d8-a8f7-c989f95e71d7` and re-reads its effective configuration:

```bash
FACTORY_NAMESPACE_ID=0d4bd471-df37-43d8-a8f7-c989f95e71d7 \
node factory/provision-projection-smoke.mjs
```

It grants exactly `FACTORY: [publish_projection]` and explicitly disables query-user and both exchange defaults. It has no integrations for Git, shell, files, BMAD, delegation, or any other capability. Source inspection confirms that `FactoryToolGrantService` accepts the suffix `publish_projection`, while `FactoryPublishProjectionTool` and the Spring plugin registered by `ToolRegistryService` expose the model-facing name `FACTORY__publish_projection`.

### Manual procedure

1. Stop the existing Factory dashboard process, then restart it from the repository root with its durable root explicit:

   ```bash
   FACTORY_DATA_ROOT="$HOME/.coday/factory" node factory/dashboard/server.mjs
   ```

   Keep it running on `http://localhost:3141`; its startup log must print the same Factory data root.

2. Stop AgentOS, then restart it using the repository's Gradle run convention (from `agentos/`), preserving whichever AI-provider variables are already required by the local setup:

   ```bash
   cd agentos
   AGENTOS_FACTORY_BASE_URL=http://localhost:3141 ./gradlew :agentos-service:bootRun
   ```

   This is an operational server start, not a validation command. AgentOS listens on `http://localhost:8124` in the current repository configuration.

3. With both servers running, provision the agent from the repository root:

   ```bash
   FACTORY_NAMESPACE_ID=0d4bd471-df37-43d8-a8f7-c989f95e71d7 \
   AGENTOS_URL=http://localhost:8124 \
   FACTORY_USER=benjamin.valdes \
   node factory/provision-projection-smoke.mjs
   ```

4. Open Angular, select namespace `0d4bd471-df37-43d8-a8f7-c989f95e71d7`, create a new case, and select or mention `workflow-projection-smoke`. Open the generic Workflows view for the same namespace so its SSE connection is active.

5. First prompt, in the new case:

   > Start the demo workflow. The goal is defined and demo preparation is now running; review has not started. Publish the projection.

   Expected: the case records a real `FACTORY__publish_projection` tool request and successful response; Factory creates `projection-smoke-demo` at revision 1; Angular shows “Prepare a demo” with stable steps `define-goal`, `prepare-demo`, and `review-result`, with statuses reflecting completed/running/pending. The agent may summarize success, but must not merely print projection JSON.

6. Without creating another case, send the second prompt:

   > Demo preparation is complete and review is now running. Republish the full projection.

   Expected: the tool call includes `expectedRevision: 1`, Factory persists revision 2, and the already-open Angular view updates live through `workflow-projection-updated` SSE without a page reload. The same workflow and step IDs remain; only progress changes.

### Diagnosis

- **Tool not found**: inspect the case's agent selection and tool-request event. Confirm the selected agent is exactly `workflow-projection-smoke`, then rerun the provisioner and confirm AgentOS was restarted from source containing the Spring `FactoryToolPlugin`. The model-facing tool name is exactly `FACTORY__publish_projection`.
- **Grant denied / tool absent from the agent**: GET the agent config from AgentOS or rerun the provisioner. Effective integrations must contain `"FACTORY":["publish_projection"]`; an absent key or empty list grants nothing. Do not change it to a broad integration grant.
- **`CASE_CONTEXT_UNAVAILABLE`**: invoke the agent inside a normal persisted AgentOS case, not through a context-free tool harness. Continue in one case; its event history must be non-empty and have one consistent case ID.
- **`REVISION_CONFLICT`**: the case is stale or another publisher advanced the workflow. Read the current Factory detail for `projection-smoke-demo`, then start a fresh smoke case or explicitly tell the agent the authoritative current revision before retrying. Never retry blindly with revision 1.
- **`FACTORY_UNAVAILABLE` / `FACTORY_TIMEOUT`**: confirm Factory is running on port 3141 and AgentOS was started with `AGENTOS_FACTORY_BASE_URL=http://localhost:3141`. Inspect Factory logs for a rejected request; network failures are intentionally returned without raw exception details.
- **Factory persisted but Angular did not update**: verify Angular is on the same namespace and its request to `/api/factory/workflows/stream?namespaceId=0d4bd471-df37-43d8-a8f7-c989f95e71d7` remains open. Refresh once to distinguish an SSE connection issue from persistence; HTTP list/detail is authoritative.

Manual validation command retained for later use (do not run as part of this preparation):

```bash
./gradlew :agentos-service:test --tests io.whozoss.agentos.factory.FactoryPublishProjectionToolSpec
```
