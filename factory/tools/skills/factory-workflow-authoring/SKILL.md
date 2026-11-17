---
name: factory-workflow-authoring
description: Conversationally co-design, validate and register immutable Factory workflows through FACTORY_AUTHORING, with explicit step contracts and separate authorized launch.
---

# Factory workflow authoring

## Co-design the flow before encoding it

Lead a short conversation, not a JSON dump or a mandatory questionnaire. Establish the user's objective, expected input and output/deliverable from the request and available context. Reuse known scope, constraints, decisions and approvals; do not ask the user to repeat them. Propose a minimal flow in plain language or a simple `Analyze → Human approval → Produce deliverable` diagram, stating reasonable defaults and assumptions.

When a meaningful choice is missing and cannot be inferred, ask **one focused question at a time**, preferably with a proposed default. For example: “I suggest approval before implementation so no changes begin without your decision. Is that the right checkpoint?” Do not require a separate approval for every field. Validate significant choices (scope, deliverable, responsibility, approval placement, dependencies) through a concise summary and adjust it with the user. If the request already settles them, proceed without redundant gates.

For each step, describe a readable name, its agent or human role, purpose/prompt, pre-actions (preparation), post-actions (deliverables/evidence), and any needed predecessors. Human steps must state what decision is being made and what evidence the person needs. Use code responsibility only for an existing configured verifier, not to invent an executable action. Generate technical IDs, `schemaVersion` and other internal structure yourself; propose a workflow type/version from the registry rather than making the user fill out a schema or calculate a hash.

Use actual namespace agent discovery **if exposed by the current environment**, following its real tool schema or configured namespace UI. `FACTORY_AUTHORING` lists definitions, not agents. Never invent a discovery tool or infer that an agent is enabled merely from a repository name, example or another namespace. If discovery is unavailable, keep a useful role-based draft, clearly mark assignments unverified, and request the actual namespace selection only when needed to finalize it. Do not claim execution readiness without evidence.

### Dependencies are not prose prerequisites

Map pre-actions / prompt / post-actions to `entry` / `mission` / `exit`. A pre-action such as “Read the approved analysis” is an executor obligation, **not** a scheduler gate: use `dependsOn` for the analysis and approval steps that must complete first. `dependsOn` is required in JSON, but predecessors are optional in the conversation (`[]` for a root). Multiple predecessors form a join: all must complete. Show fanout/joins only when useful work really can run independently; prefer a simple chain otherwise.

A human approval step uses the existing Factory checkpoint: after its predecessors complete, it waits for an authenticated human decision in Factory. Approval completes the step; rejection fails it, rather than selecting an invented alternative branch. Dependent work cannot proceed without completion. Conversational design validation is not a runtime approval. Use only the explicit named-route decision and join contract below for conditional work. Do not invent optional edges, expression predicates, loops or automatic rework routes: the schema remains a DAG. Warn when a requested edit creates a cycle and propose an acyclic revision or a separate later run instead.

Revise the current draft incrementally. Preserve accepted decisions, unchanged contracts, step IDs, dependencies and plugin settings; explain the changed part and its downstream impact. Do not silently drop an approval dependency when inserting, removing or renaming a step. Keep readable names separate from stable internal IDs.

### Short co-design example

The role labels below are illustrative, not a discovered agent catalog.

> User: Design a workflow to turn a support ticket into a customer reply. A person must approve it.
>
> Assistant: Input: the ticket and approved support guidance; output: a customer-ready reply, with no sending. I suggest `Draft reply (agent) → Approve reply (human)`. The draft step reads the ticket and guidance, writes a reply and records uncertainties; the human checks accuracy and tone before recording the decision in Factory. Is approval of the final text sufficient, or do you need a separate analysis first?
>
> User: Add analysis first, and have me approve that before drafting too.
>
> Assistant: Revised: `Analyze ticket (agent) → Approve analysis (human) → Draft reply (agent) → Approve reply (human)`. Analysis produces findings and uncertainties from the ticket/guidance. Your first decision approves that evidence; drafting depends on it, reads the approved findings and preserves them with the reply. Final approval remains in place; nothing sends the reply. I'll use agents verified in your namespace and generate the internal IDs. This is still a design draft, not saved.
>
> User: Yes, register that, don't launch it.
>
> Assistant: I'll register this four-step flow with both human checkpoints and no sending or launch, using the verified assignments. I'll validate it first and correct any schema errors, then report the registered version/hash and where to view it.

If a significant choice remains unresolved (including unavailable agent assignments), settle that choice before registration rather than treating the example's last turn as blanket authority for a different design.

## Canonical definition and step contracts

Encode the agreed flow using the same canonical JSON as the Cockpit composer; show JSON only when useful or requested, not as the primary design experience. A definition requires `schemaVersion: "1"`, safe `workflowType`, strict `MAJOR.MINOR.PATCH` `version`, nonblank `title` (at most 256 characters), and 1–500 `steps`. Safe IDs contain 1–128 letters, digits, dots, underscores or hyphens and start with a letter or digit. Versions have no leading zeroes except `0`.

Each block requires unique `id`, nonblank `name` (at most 256 characters), a `responsibility` object with `kind` (`agent`, `human` or `code`) and `name`, and `dependsOn: []` or predecessor IDs. The responsibility name is nonblank and at most 256 characters; for an agent it must be the actual enabled agent name in the intended namespace. Never invent a catalog. Use separate blocks for separate agents. All references must exist, be unique and form an acyclic graph. Preserve existing human/code responsibilities and plugin routing unless explicitly asked to change them.

New agent blocks must have all three contract fields together:

```json
{
  "id": "analyze",
  "name": "Analyze request",
  "responsibility": { "kind": "agent", "name": "<verified namespace agent name>" },
  "dependsOn": [],
  "entry": ["Clarify missing intention with the user before proceeding."],
  "mission": "Analyze only the requested scope; do not implement.",
  "exit": ["Provide durable findings and verification evidence; state unresolved questions."]
}
```

`entry` and `exit` are ordered prose arrays, at most 50 nonblank strings of at most 4,000 characters each; empty arrays are valid. `mission` is nonblank and at most 16,000 characters. These are obligations, not executable actions or machine-verified completion predicates. Explicitly include critical scope, handoff/artifact preservation, deliverables and verification obligations. Never put secrets in prose. Factory retains technical result submission, authorization, approvals and configured validation. No Git subsystem or permissions are granted by prose.

Omitting all three fields is legacy compatibility, not an empty explicit prompt. Generic legacy adoption needs deliberate review of run-brief creation/preservation obligations. Gitay branch obligations and A11yReviewer authoritative changed-file/range scope are runtime-derived: do not claim a static prompt safely converts them. Keep existing special legacy steps pending an explicit backend/context decision. Human/code prose does not replace approval or verifier implementations.

## Conditional decisions: one route or several, then a common join

When the request needs a choice, describe it conversationally: “Architect chooses frontend, backend, or both; Review waits for all chosen implementations.” Clarify whether multiple choices are allowed only if the request does not settle it. Fullstack means selecting both named routes, never a magic third route. Keep ordinary predecessors as AND dependencies. Ask what common step should wait for the selected work; do not infer routing from pre/post-action prose, PASS/FAIL, human rejection or an agent's narrative.

The initial contract supports exactly one decision group per workflow, 2–16 named routes, distinct targets, a single step per branch, and exactly one explicit join. Each branch depends only on the decision and is consumed only by the join. The join lists every route target in dependsOn, and may also have ordinary dependencies that must succeed. Nested, overlapping and multi-step branches are rejected; simplify the scope rather than quietly dropping intermediate steps or approvals. Human/code branches are supported with their normal approval/verifier semantics. Scheduling stays sequential in the shared worktree, not parallel.

Canonical example (names are illustrative, not verified assignments):

```json
{
  "schemaVersion": "1", "workflowType": "scoped-implementation", "version": "1.0.0",
  "title": "Choose implementation scope then review",
  "steps": [
    {"id":"architect","name":"Select scope","responsibility":{"kind":"agent","name":"Architect"},"dependsOn":[],
     "entry":["Read the request; clarify missing scope."],"mission":"Choose frontend, backend, or both.","exit":["Explain scope and submit the named routes."],
     "decision":{"mode":"multiple","routes":[{"name":"frontend","target":"implement-front"},{"name":"backend","target":"implement-back"}]}},
    {"id":"implement-front","name":"Frontend","responsibility":{"kind":"agent","name":"Frontend"},"dependsOn":["architect"],
     "entry":["Read Architect's handoff."],"mission":"Implement only the selected frontend scope.","exit":["Report changes and actual verification."]},
    {"id":"implement-back","name":"Backend","responsibility":{"kind":"agent","name":"Backend"},"dependsOn":["architect"],
     "entry":["Read Architect's handoff."],"mission":"Implement only the selected backend scope.","exit":["Report changes and actual verification."]},
    {"id":"review","name":"Common review","responsibility":{"kind":"agent","name":"Reviewer"},"dependsOn":["implement-front","implement-back"],"join":"architect",
     "entry":["Read every selected implementation's evidence."],"mission":"Review the selected changes only.","exit":["Report findings and residual risks."]}
  ]
}
```

`decision.mode` is `single` (exactly one) or `multiple` (one or more). Agents return route **names**, not arbitrary target IDs. The runtime tool remains the existing `FACTORY_WORKER__submitStepResult`; its structured argument for both routes is:

Inspect the exposed HTTP_API schema: parameters are `Idempotency-Key` and `body`; attemptId is inside body (not a separate path/query parameter). Send these tool arguments:

```json
{"Idempotency-Key":"<Factory submission ID>","body":{"attemptId":"<Factory attempt ID>","result":{"status":"PASS","summary":"Both frontend and backend changes are required.","claims":{"modifiedFiles":[]},"selectedRoutes":["frontend","backend"]}}}
```

For one route, use `["frontend"]` or `["backend"]` instead. Omit selectedRoutes on ordinary steps and on FAIL/NEEDS_RESEARCH. Missing/empty/unknown/duplicate/out-of-mode choices are rejected, never inferred from prose. PASS submission alone does not activate work: configured validators and auto-oracles must also succeed. Accepted routing is pinned to result ID/hash and attempt ID; a later retry cannot silently replace it. Nonselected steps are `skipped`, `executed=false`, not failed or succeeded. The join waits for every selected success; failed selected work blocks it, and human/research waits are not successes. Routing fields are omitted entirely on legacy workflows, preserving their definition hashes and AND behavior.

## Validation, registration and launch

Before registration, show the agreed human-readable flow with roles, key pre/post-actions, dependencies and any remaining assumptions. For a design-only or ambiguous request, provide the draft and ask whether to register; never interpret “design” as permission to save. When registration is already explicitly authorized and the summary reflects the agreed choices, complete it without another redundant confirmation. Material changes still need user validation. Tool confirmation gates remain separate and must be respected.

The authoritative validator is `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowDefinition.kt` and its graph validator. Unknown fields, partial contracts, duplicate IDs, missing dependencies, self-dependencies and cycles fail validation. UI early feedback never replaces server validation. Only use recognized optional `execution: { "plugin": "..." }` and `trustedExecution: { "allowedPaths": [...] }` when authorized; preserve untouched values.

The existing organization-scoped HTTP registry supports:

- `GET /api/factory/workflow-definitions`
- `GET /api/factory/workflow-definitions/{workflowType}/{version}`
- `POST /api/factory/workflow-definitions/validate` for authoritative dry-run validation (no persistence or execution).
- `POST /api/factory/workflow-definitions` with the canonical definition JSON for atomic immutable registration, idempotent for identical canonical content. The authoring tool does not send `If-None-Match: *` (that optional UI precondition rejects even identical replays).

Treat `(workflowType, version)` as immutable. On conflict, choose a new version rather than overwrite/delete a pinned definition. The server validates/normalizes and returns identity plus its canonical SHA-256 `definitionHash` inside the `data` envelope. Do not supply a guessed hash. Changes require a new version; existing runs remain pinned.

Saving must never auto-run. Only after an explicit launch request use the canonical `POST /api/factory/workflows`, with execution `namespaceId`, `workflowType`, `definitionVersion` and exact server `definitionHash`, and caller-owned `Idempotency-Key`. Version and hash must be supplied together. Re-read stale identity/hash failures, never silently switch to latest. A saved definition is not execution evidence.

## Author autonomously through actual tools

When the user authorizes authoring and registration, complete validation and registration yourself; do not stop at a file draft or request manual file import. Inspect the actually exposed tool schemas first; the source contract is `factory/factory-service/openapi/factory-authoring-http-api.yaml`. Use list/get to inspect existing identities and retrieve the source definition when revising one. Do not assume tools exist merely because this skill names them. The dedicated `FACTORY_AUTHORING` HTTP_API integration defines exactly:

- `FACTORY_AUTHORING__list_definitions` with `{}` to inspect existing identities.
- `FACTORY_AUTHORING__get_definition` with `{"workflowType":"request-review","version":"1.0.0"}` to preserve the existing canonical shape when editing.
- `FACTORY_AUTHORING__validate_definition` with `{"body": <canonical definition object>}`. A successful dry run is not registration.
- `FACTORY_AUTHORING__register_definition` with the identical `{"body": <canonical definition object>}` after successful validation. No file import, overwrite, delete or launch is performed.

For example, pass this structured argument to validation, then unchanged to registration (substitute real available agent names for agent steps):

```json
{
  "body": {
    "schemaVersion": "1",
    "workflowType": "request-review",
    "version": "1.0.0",
    "title": "Human request review",
    "steps": [{
      "id": "review",
      "name": "Review request",
      "responsibility": {"kind": "human", "name": "reviewer"},
      "dependsOn": [],
      "entry": ["Read the request and attached evidence."],
      "mission": "Make the human approval decision in Factory.",
      "exit": ["Record the decision and its rationale."]
    }]
  }
}
```

On 400, use `error.code` and `error.details.path` to correct the definition and revalidate. On 409 with different content, choose a new version, never overwrite or delete the prior one. After an ambiguous registration timeout, retry the identical payload or read that identity and compare the server hash. Preserve the returned `data.workflowType`, `data.version`, `data.definitionHash` as registration evidence. Finish by showing those actual values, a short flow recap and the next step: open Factory Cockpit **Workflows → the registered type/version** to visualize the dependency diagram and inspect each step's Pre-actions / Prompt / Post-actions. State that no run was launched. A validation hash alone is not evidence of saving. Do not claim schema validation checked agent availability, execution-plugin readiness or launch authority.

Only after explicit launch authorization, call `FACTORY_WORKSTREAM__start_workflow`, for example:

```json
{
  "namespaceId": "<assignment namespace UUID>",
  "Idempotency-Key": "request-review-launch-001",
  "body": {
    "workflowType": "request-review",
    "definitionVersion": "1.0.0",
    "definitionHash": "<exact hash returned by registration>"
  }
}
```

Use the actual UUID and hash, not these placeholders. Registration never implies launch permission. A queued launch is not execution completion. `FACTORY_WORKER` submits attempt results only; its narrow result capability cannot authorize authoring. Use the separately configured ordinary Factory operator Auth Setting. Definitions are organization-owned; launch remains fenced by the assignment namespace's existing active workstream mapping and allowed types. Do not invent organization, workstream or identity headers.

If tools or the selected skill are unavailable, explain the concrete observed limitation (for example an integration connection failure, missing binding or unreadable skill), provide a useful unsaved flow/contract draft and identify the needed setup; never claim saved, validated or launched without actual results. Setup is in `factory/tools/FACTORY_WORKSTREAM_HTTP_API.md`: attach `FACTORY_AUTHORING` and select this skill through the real AgentOS namespace mechanisms. Respect the configured integration and security mode: ordinary operator authentication is required in `auth` mode, while `local` mode needs no token. Do not ask for secrets in chat or instruct token minting merely because tools are unavailable; never embed credentials in a definition. Use the existing per-agent tool allowlist to restrict authoring tools to trusted agents. Standard HTTP_API write confirmation semantics apply: the advanced agent requests confirmation on every non-GET call; simple agents do not enforce that gate, so restrict their write tools with the allowlist. Prior authoring authorization does not bypass tool confirmation gates. A skill cannot grant tools, credentials or bypass those gates.

See `factory/factory-service/STEP_CONTRACTS.md`, `WorkflowDefinitionController.kt`, `WorkflowService.kt`, and sibling `factory-workstream` / `factory-worker` skills for the source contracts.
