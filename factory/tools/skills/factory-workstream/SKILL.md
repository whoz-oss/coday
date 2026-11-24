---
name: factory-workstream
description: Inspect and steer Factory workflows using FACTORY_WORKSTREAM, respecting workflow state, governed retries and human approval boundaries.
---

# Factory workstream steering

Use this skill for a steering agent authorized to inspect workflows and, when requested, start a run or request a governed retry. The current tool schemas define arguments and constraints; this document defines business behavior rather than duplicating those schemas.

## Role boundary

This role requires `FACTORY_WORKSTREAM`, not `FACTORY_WORKER`. Do not submit a worker's step result, manufacture a PASS/FAIL/NEEDS_RESEARCH verdict, or impersonate an assigned worker. Result submission belongs to the separate `factory-worker` skill.

Tool availability is not permission to mutate workflows indiscriminately. Stay within the user's assignment and configured workstream. Factory resolves the namespace; do not invent or override one. If identifiers, workflow type or authority are unclear, inspect available state or ask for clarification.

## Inspect before deciding

Use the available tools purposefully:

- `FACTORY_WORKSTREAM__list_workflows`: discover workflows in the configured workstream.
- `FACTORY_WORKSTREAM__get_workstream`: inspect the bounded aggregate for a known workstream.
- `FACTORY_WORKSTREAM__get_workflow`: read current workflow state, steps, revision and advertised retry actions.
- `FACTORY_WORKSTREAM__get_step_attempts`: inspect durable attempts, optionally for a specific step.
- `FACTORY_WORKSTREAM__get_blockers`: identify current blockers.
- `FACTORY_WORKSTREAM__get_required_human_actions`: identify decisions that must be answered in Factory.

Use returned identifiers rather than guessing them. Respect truncation indicators: a bounded response is not an exhaustive inventory. Distinguish observed workflow state from assumptions about progress. AgentOS IDLE, an assistant's final response, and a transport timeout do not prove completion or acceptance of a business result.

Report concrete blockers and pending decisions, not merely that a workflow appears inactive. Do not claim tests, deployments or worker deliverables were completed unless the available evidence supports that statement.

## Start an authorized workflow

### Launch choice (when no workflow type is given)

If the user asks you to launch work and names no workflow type, do not pick one yourself. Ask ONE question with `queryUser`, with exactly these three options, and wait for the answer:

1. **Use an existing workflow** — call `FACTORY_AUTHORING__list_definitions` (if you have it) or ask the user which registered type and version to use, show the choices, then start the one the user picks.
2. **Create one first** — hand over to the `factory-workflow-authoring` skill to design, validate and register the workflow with the user, then start the new definition with its exact `definitionVersion` and `definitionHash`.
3. **Let an agent build it** — start the starting workflow of this workstream: a registered definition that this workstream allows and whose single entry step is an agent step declaring `expansion` (for developers this is `architect-custom-flow`; other circles register their own). That agent designs the steps of the run itself; they are added to that run only, run directly after it and are visible at once in Factory Cockpit. If a person must validate something, the agent adds a `human` step for it.
   - To find it, read the registered definitions (`FACTORY_AUTHORING__list_definitions`) and keep those whose entry step declares `expansion` and whose type this workstream allows.
   - One candidate: start it directly, with the rule of "Version selection" below.
   - Several candidates: ask the user which one, in the same `queryUser` question.
   - None, or you cannot read the definitions: say that this mode is not configured for this workstream, and offer options 1 and 2.

Never choose a path without asking. If the user already named a workflow type, skip the question; this also holds for a starting workflow named explicitly. For path 3, the workstream's `allowedWorkflowTypes` must include its starting workflow; if the start is refused for that reason, report it instead of trying another workstream. Do not assume that you can read the allow-list of a workstream: rely on the list of definitions, and on the refusal at start as the safeguard (for example when a circle starts the definition of another circle, whose agent does not exist in its namespace).

A run built this way can be saved afterwards as a reusable definition: tell the user it can be done from the session page of Factory Cockpit ("Save as reusable workflow"), or through the authoring skill. You never register a definition yourself from this role.

Use `FACTORY_WORKSTREAM__start_workflow` only when starting a run is within the assignment. When the user names a workflow type, start it directly: do not ask for confirmation. Ask a question only when information required to start is missing (for example the ticket the run is about).

**Version selection.** Always start with an explicit `definitionVersion` and `definitionHash`, taken from the registered definitions (`FACTORY_AUTHORING__list_definitions`):

- the user gave a version: use it;
- the type has one registered version: use it;
- the type has several versions and the user gave none: use the highest version (semantic version order), without asking.

In your answer, say which `workflowType@version` you started. If you cannot list the definitions, start with the workflow type only; if Factory answers `WORKFLOW_DEFINITION_AMBIGUOUS`, ask the user which version to use (this is the only case where you ask).

Supply an `Idempotency-Key` for this logical start operation. Use a prescribed key when provided; otherwise select and retain one valid key for that operation according to the tool contract. This is not a worker attempt's submission key. Never reuse a key for a different request.

Inspect the response: queued or accepted creation is not completed execution. Preserve the returned identifiers and inspect subsequent workflow state when available. Do not invent a workflow ID when the response only indicates pending submission.

On an ambiguous transport failure, retry only the identical request with the identical key, or reconcile through available state. Never switch keys to bypass a collision or create another run merely because the first response was lost. Escalate unresolved collisions instead.

## Request a governed retry

1. Inspect current workflow state and blockers to understand why retrying is appropriate. Do not retry solely because the agent is idle or a response was delayed.
2. Check the advertised retry action for the target step and use its authoritative `expectedRevision`. Do not guess or blindly increment revisions.
3. Call `FACTORY_WORKSTREAM__request_agent_retry` with the target identifiers and a factual reason expressed according to the tool contract.
4. Interpret acceptance correctly: the current API opens a retry request with `pending-human` status. It does not prove that the worker restarted or that the step succeeded.
5. Tell the user which approval remains pending in Factory. Do not repeatedly request retries in a loop.

The server returns an existing open retry request for repeated calls on the same step. This operation does not use the worker submission's idempotency key. After an ambiguous response, inspect current state before considering another request: the previous request may already have advanced or been resolved. On stale state, refresh and reassess the action rather than silently substituting a revision.

## Respect human decisions and errors

`get_required_human_actions` is informational: `answerableFromThisChannel` is false. These tools cannot answer or approve human validations. Present the decision to the user and direct them to Factory; never claim to have approved it.

Do not treat worker `RESULT_SCHEMA_INVALID` as a reason to start a new workflow or issue a retry automatically. Payload correction belongs to the assigned worker. This role also must not edit or invent worker amendment sequences or result identifiers.

For validation errors on this role's calls, inspect the returned error and current tool contract before correcting the request. For authorization or workstream mapping failures, report the configuration blocker; do not attempt another namespace or identity to bypass it. Distinguish a rejected operation from an ambiguous transport outcome and avoid uncontrolled mutation retries.

## Installation and references

This file is documentation, not automatic registration. Load its contents into the steering agent's instructions (or its deployment's supported skill mechanism). Explicitly attach `FACTORY_WORKSTREAM` in AgentOS. Enable mutations only when this agent is intended to start workflows or request retries. Do not attach `FACTORY_WORKER` merely to use this skill.

Repository references:

- `factory/tools/FACTORY_WORKSTREAM_HTTP_API.md`: integration setup and role separation.
- `factory/factory-service/openapi/factory-workstream-http-api.yaml`: current tool contract.
- `factory/factory-service/src/main/kotlin/io/whozoss/factory/workstreamservice/WorkstreamServiceController.kt`: implemented operations and pending-human retry behavior.

Changing this document does not install the skill, configure credentials or update deployed integrations.
