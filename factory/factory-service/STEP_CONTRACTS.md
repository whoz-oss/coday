# Explicit workflow step contracts (WZ-36102)

A workflow step may declare an ordered, human-readable work contract alongside its existing identity, responsibility and dependencies:

```json
{
  "id": "implement",
  "name": "Implement the requested change",
  "responsibility": { "kind": "agent", "name": "Dev" },
  "dependsOn": ["initialize-branch"],
  "entry": ["Inspect and sync the authorized branch before writing using AgentOS tools."],
  "mission": "Implement only the authorized request, following the project's AgentOS skills.",
  "exit": ["Commit scoped changes and push before reporting success.", "Report exact checks and commit evidence."]
}
```

`entry` is preparation before the mission, not a DAG dependency predicate. `mission` is the step's work, not an agent persona or a template. `exit` describes completion obligations and deliverables. These are prose obligations for the executor, **not machine-verified predicates**. For a human or code step the prose documents intent; it does not add agent execution or alter the existing approval/verifier semantics. Never place secrets in these operator-visible fields or the user request.

All three fields must be supplied together. `mission` must be nonblank, at most 16,000 characters. `entry` and `exit` are ordered arrays (possibly empty), at most 50 nonblank strings each, at most 4,000 characters per string. Unknown step fields remain rejected; nulls, partial contracts and wrong types are invalid. Order is preserved by normalization and hashing. The same contract is retained in workflow projections.

## Mandate assembly and ownership

For explicit agent steps Factory assembles the actual turn text in this order:

1. Original user request, current step identity and scope, ticket context and durable dependency inputs/run-brief handoff when present.
2. Entry / Preparation, Mission, Exit / Deliverables, in declared order.
3. Existing context contributions from configured plugins, when present.
4. Factory execution protocol: clarification continuation, `FACTORY_WORKER__submitStepResult`, Factory-generated `attemptId` and submission idempotency key, structured payload/schema correction, PASS/FAIL/NEEDS_RESEARCH and stopping after acceptance.

An explicit contract bypasses the legacy name-based Gitay brief and inferred entry-step framing/deliverable instructions. It does not change dependency scheduling, approvals, trusted workspace binding, result capability issuance, result validation, configured plugin validators or oracle checks. Prose cannot turn off those controls. Plugin contributions retain their existing extension semantics and should supply context rather than duplicate workflow business instructions. Reusable work conventions belong in AgentOS skills, not in Factory's mandate renderer.

The rendered mandate (including resolved attempt identifiers) is frozen in the existing durable attempt `brief` and reused unchanged during replay. A new attempt remains a separate record. The authenticated, tenant/namespace-scoped attempt read surface exposes this turn text as `effectiveMandate` once `startedAt` exists; it does not expose capability tokens, leases or the raw context envelope. This is the payload used by dispatch, not proof that the remote agent received or fulfilled every obligation (an interrupted/ambiguous dispatch remains governed by the existing recovery protocol). It includes user/dependency content, so treat it with the same confidentiality as the run. No truncation or reconstruction from a later workflow definition is used.

The cockpit definition page displays configured Entry/Mission/Exit in readable prose, with JSON available separately. The session phase detail shows that configuration and the frozen effective mandate for the **selected attempt**, including older retries. Missing/legacy or not-yet-dispatched mandate data is explicitly unavailable, never synthesized from configuration. Plain-text interpolation preserves line breaks without executing HTML.

## Compatibility and adoption

Schema version remains `1`. Steps omitting **all three** fields remain legacy steps: normalization injects no new keys, preserving their canonical definition shape/hash. They retain historical root-step clarification/run-brief framing, downstream handoff preservation, inferred deliverable guidance, special Gitay/A11y instructions and existing plugin contributions. Existing persisted attempts replay their frozen brief without regeneration. No stored definition or bundled catalog is silently rewritten.

Adopt explicit contracts deliberately by registering a new workflow version with all three fields on each agent step being migrated. Specify the needed business framing, artifact/handoff preservation, review scope and deliverables in those fields rather than relying on inferred root position or persona name. Do not change a definition used by an active run: existing hash mismatch protections still apply. Warning: omitting a required handoff/artifact obligation is not a validation error; authors must state it explicitly. Factory can relay an existing ancestor run-brief, but prose validation does not prove that an agent produced or preserved the required artifact. Empty arrays mean no additional preparation/exit prose, not a request for legacy defaults. This opt-in compatibility lane is intentional; removal/migration of all legacy defaults is not part of this change.

## Illustrative Git checkpoint workflow — agent obligations only

The validated fixture is `src/test/resources/workflows/git-checkpoint-contract.json`. It is intentionally not seeded into production. It declares two dependent **agent** steps: initialize an authorized branch before any writing, then inspect/sync on entry, perform scoped work, commit and push before success, and report durable evidence. AgentOS agents use existing AgentOS Git tools and skills. Factory does not execute Git operations from `entry`/`exit`, parse them as commands, add a Git adapter or implement checkpoint verification. Actual Git checkpoints belong to parent WZ-36049 and remain out of scope. A prose instruction to push is not proof that a remote branch exists; a PASS is still subject only to the existing structured result and explicitly configured validators.

Full agent CRUD tools for workflow definitions and a full visual graph editor are followups. PR creation, review/merge automation and Git checkpoint automation are not implemented here.

## Automated coverage

`ExplicitStepContractTest` validates the fixture, field bounds/types, legacy shape/hash compatibility and definition → policy → instance/projection round-trip. `CapabilityExecutionContextReplayTest` covers ordered assembly, explicit Gitay override, concrete attempt identifiers, retained submission/schema protocol, frozen payload exposure and replay, plus legacy framing. Existing result-service, validator, DAG, approval and recovery suites continue to cover the technical orchestration boundary. Cockpit mapper/component tests cover readable/escaped prose, exact retry selection and honest unavailable states.
