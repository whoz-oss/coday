---
name: factory-bmad-projection
description: "Map a Forge/BMAD Jira Story lifecycle to revision-safe transitions of the governed Factory bmad-story workflow."
---

# Forge/BMAD Factory Workflow Adapter

Apply this adapter together with `core/factory-workflow-projection/SKILL.md`.

## Domain workflow declaration

- **workflowType:** `bmad-story`.
- **definitionVersion:** `1.1.0` (`factory/workflows/bmad-story/1.1.0.json`).
- **Compatible input:** an approval-gated Forge/BMAD lifecycle for a Jira Story.
- **Identity:** use the Jira Story key unchanged as `workflowId` and its Jira title as `title`, after deterministic Forge Run reconnaissance.
- **Sole transition owner:** ProductEngineer. Delegates report facts and results; they never mutate the global workflow.
- **Authority:** the immutable Factory definition owns step IDs, responsibilities, dependencies, transitions, and evidence policy.

## Governed operation

A `bmad-story` created by `FACTORY__start_workflow` is governed.

- Never update it with `FACTORY__publish_projection`.
- Call `FACTORY__get_workflow` immediately before every state change.
- Perform one `FACTORY__transition_workflow` call at a time using the current revision.
- Before completing an agent step, call `FACTORY__record_agent_result`, capture the exact opaque `evidence.evidenceId`, refresh, and transition with that ID.
- Never construct an evidence ID from Jira, a step, an artifact, or an idempotency key.
- Use `FACTORY__record_artifact` only for an actual immutable artifact and SHA-256 hash. Artifact evidence does not replace a required `agent-result:pass`.

## ProductEngineer ownership semantics

All agent-owned steps in `bmad-story@1.1.0` are assigned to ProductEngineer because it is the sole governed transition owner. This does not mean ProductEngineer performs every specialist task itself.

After delegation, ProductEngineer records `agent-result:pass` only when it has received and accepted a complete authoritative result from the delegated phase. `pass` means the phase execution/result handoff succeeded. The business verdict is recorded separately in bounded facts such as `resultCode`, `category`, `attempt`, `durationMs`, or `itemCount`.

Never mark a phase passed merely because a delegate returned text.

## Mandatory synchronization boundaries

Synchronize before delegation, after every delegated result, before a human checkpoint, after a human decision, and before a terminal response.

For each agent phase:

1. Refresh the workflow.
2. Transition the eligible step to `running`.
3. Delegate.
4. Evaluate the returned authoritative result.
5. Record an honest agent result (`pass`, `fail`, or `indeterminate`).
6. Capture its opaque evidence ID.
7. Refresh the workflow.
8. Transition the step using that evidence.

Never batch missed transitions at the end or backdate them.

## Human checkpoints

Human steps remain owned by the Product owner and enforced through the Factory human control plane.

- Refresh, then call `FACTORY__request_human_decision` for the ready human step with that exact revision, a bounded prompt, fixed `approve`/`reject` actions, and an idempotency key. Do not separately call `transition_workflow`; the dedicated open operation creates the durable gate and performs `ready` → `waiting_human`.
- Relay the question and stop. ProductEngineer must not complete the checkpoint with `record_agent_result` or impersonate `factory-human`.
- Resume only after `FACTORY__get_workflow` reports the step `completed` (approved) or `failed` (rejected). The cockpit reply path alone records the matching human-decision evidence and resolves the step.
- If the request capability is unavailable, leave the step ready and report the missing capability.

## Technical review and rework

In version 1.1.0, `technical-review` completion means the review was executed and an authoritative verdict was received. Record execution success as `agent-result:pass`; record the actual verdict in facts, for example:

```json
{
  "resultCode": "REVIEW_FAIL",
  "category": "technical-review",
  "attempt": 1
}
```

Then:

- for a FAIL verdict, execute real `review-rework`, record its authoritative result, and complete the step;
- for a PASS verdict, perform an explicit verified no-op for `review-rework`, record why no change was required, and complete it;
- `functional-validation` depends on `review-rework`, preventing it from bypassing review handling.

This graph is acyclic and models one review/rework handling pass. Further review loops require a new workflow instance/sub-workflow or a future immutable definition; never force a cycle into this definition.

## Errors

- `REVISION_CONFLICT`: refresh; if the intended state already exists, treat it as complete, otherwise retry with the current revision.
- `EVIDENCE_NOT_FOUND`: the supplied ID is invalid or was never recorded. Never invent a replacement; record real evidence first.
- `PASS_EVIDENCE_REQUIRED`: record the exact evidence kind/outcome required by policy or stop.
- `GOVERNED_WORKFLOW_REQUIRES_TRANSITION`: stop using publication and use the transition tool.
- Policy, ownership, removed, or purged errors must not be bypassed.

BMAD, Jira, Git, worktree, PR, case, and conversation artifacts retain their own lifecycle. Their presence does not make them Factory runs or Factory evidence.
