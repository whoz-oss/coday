---
name: factory-workflow-projection
description: "Select an owned domain workflow and publish complete, revision-safe projections through the Factory tool."
---

# Factory Workflow Projection

Use this industrialized run protocol for `/run-factory <business-reference-or-intent> [--workflow=<workflowType>]` and whenever an agent owns publication of a workflow projection. The input reference is opaque to this generic protocol: never assume a source system, syntax, domain, or identifier format.

## Conversational entry algorithm

1. Verify that the model-facing tools `FACTORY__get_workflow` and `FACTORY__publish_projection` are available. If either is absent, refuse to start a Factory run and explain which capability is required.
2. Inventory the loaded domain workflow declarations owned by the current agent. A compatible declaration must explicitly define `workflowType`, intent/reference compatibility criteria, its stable identity rule, its sole publisher role, and its lifecycle source.
3. If `--workflow=<workflowType>` is present, use it only when that exact loaded declaration is owned by this agent and compatible with the input. Otherwise refuse and state why. Without a selector, select only when exactly one owned declaration is compatible. If several are compatible, ask the human to choose; if none is compatible, refuse. Having the tool alone never authorizes inventing a workflow.
4. Interpret the reference or intent and resolve or establish its stable domain identity strictly according to the selected declaration. Ask for missing domain facts when its identity rule requires them. Never create an identity convention, workflow schema, steps, dependencies, actors, or lifecycle from generic intuition.
5. Call `FACTORY__get_workflow` with only the resolved `workflowId`. For `absent`, create by publishing the declaration's complete initial projection with `expectedRevision: 0`. For `existing`, require its `workflowType` to equal the selected declaration; an incompatibility is a hard refusal before any publication. Otherwise resume from the returned complete v1 or v2 projection and authoritative revision. Never ask the human for, infer, or guess a revision. For `removed` or `purged`, halt and report the lifecycle state; this protocol does not authorize restore, tombstone clearing, or identity replacement.
6. Continue through the declaration's normal domain orchestration and authoritative lifecycle. Publish the complete projection after every material transition using the revision returned by lookup or the last successful publication as `expectedRevision`.
7. Represent waiting, blocked, failed, cancelled, and completed outcomes honestly. Never auto-approve a human gate. A failed terminal publication must be reported as a synchronization failure alongside the domain outcome.

Namespace, runtime, case, thread, user, and controlling-execution identity are trusted runtime attribution. Never place them in `workflowId`, `workflowType`, title, steps, descriptions, dependencies, responsibilities, or any other model-authored projection field.

## Domain workflow declaration convention

Each domain adapter skill must contain one clearly labelled `Domain workflow declaration` section defining exactly:

- `workflowType`
- compatible intent/reference criteria
- stable identity rule
- sole publisher role
- lifecycle source, including its stable schema-v2 step graph and transition/gate rules

This prose declaration is the registry: do not infer declarations from tool access and do not build an ad hoc code registry.

## Publication contract

- Schema v1 remains valid for publishers that do not know step responsibility. Schema v2 adds a required `responsibility` to every step: `kind` is exactly `human`, `agent`, or `code`, and optional `name` is a bounded display label. Use v2 whenever actor lanes are known. Never infer kinds from names or statuses, and never mix v1 steps with v2 responsibility requirements.

- Publish the complete current projection through `FACTORY__publish_projection`; never present projection JSON in prose as a substitute for the tool call.
- Keep `workflowId`, `workflowType`, and every step `id` stable for the lifetime of the represented workflow. Names and descriptions may change without changing IDs.
- A publication is an idempotent replacement snapshot, not a patch. Include every current step, its dependencies, and its current status on every call.
- Publish after each material transition: workflow creation, a step or workflow status change, dependency change, human checkpoint, blocker/failure, recovery, cancellation, or terminal outcome. Do not publish for prose-only progress with no represented state change.
- The first publication sends `expectedRevision: 0`; revision zero is the creation precondition. After a successful call, retain the returned revision and send it as `expectedRevision` on every later publication. An unchanged publication may return the same revision; continue using the returned revision.

## Status semantics

Use only the contract statuses:

- `pending`: known but not yet eligible to start.
- `ready`: dependencies are satisfied and work may start.
- `running`: work is actively progressing.
- `waiting_human`: progress requires a human decision, approval, or input.
- `blocked`: progress cannot continue because a non-human prerequisite or recoverable problem is unresolved.
- `completed`: finished successfully.
- `failed`: terminal unsuccessful outcome.
- `cancelled`: intentionally terminated without completion.

Workflow status summarizes the real current outcome; it must not claim more progress than its steps support. Terminal statuses are `completed`, `failed`, and `cancelled`.

## Errors and lifecycle

- On `REVISION_CONFLICT`, do not retry with a guessed revision and do not overwrite newer state. Call `FACTORY__get_workflow` again, verify `workflowType` compatibility, reconstruct the complete current projection from authoritative domain facts plus the returned snapshot, then publish deliberately with the returned revision.
- On `FACTORY_UNAVAILABLE`, preserve the domain workflow state, report that projection synchronization is unavailable, and retry only when the Factory is available. Never represent prose as a successful publication.
- On `WORKFLOW_REMOVED` or authorization/schema errors, stop publishing and report the structured error; do not recreate, restore, or alter identity fields unless separately authorized.
- Factory and AgentOS persist the projection only. They do not inspect, infer, mutate, clean up, or delete domain artifacts.
- Publish a terminal projection successfully before reporting the workflow as terminal. If terminal publication fails, report the domain outcome together with the projection synchronization failure; do not claim Factory synchronization.
- Terminal publication grants no cleanup authority. Domain cleanup is governed only by separately authorized lifecycle rules.
