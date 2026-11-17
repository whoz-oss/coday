# WZ-36147 Lot 3 — delivery entry and Factory seam evidence

Status: **CE04 first-step draft prepared; CE04–07 are not execution ready**. This is a
review artifact, not a Factory Run or a Jira decision. The Coday branch was
merged with the refreshed `origin/workstream/forge/master` before inspection.

## Delivery entry decision (2026-11-17)

The requester confirmed the configurable first-step approach and the
unversioned term “Factory” on 2026-11-18. This validates the entry contract,
not publication of the one-step draft or the downstream design/Build gates.

The workflow for bringing a ticket to `Ready` is a separate, potentially more
interactive Run. This Lot implements only the **delivery** Run. The two Runs
may refer to the same Jira ticket; duplicate protection must be scoped to an
active delivery Run, not to every Run carrying that ticket key.

The delivery Run may be created with a ticket that is not yet `Ready`. Its
**first executable step** is a read-only ProductEngineer preflight. The
agent reads the current Jira ticket and source specification, checks ticket
identity, source completeness and the `Ready` state, and submits an evidenced
Factory result. It does not groom, edit the ticket, change Jira status,
approve the design packet, or begin Build. A positive result permits design
assessment. A negative or unavailable result stops the Run before design. The
Run can wait for a human `retry` or `abort`; retry starts a new preflight
attempt against current Jira and source evidence. A human retry is permission
to recheck, never an assertion that the ticket is `Ready`.

This replaces the earlier proposal to hard-code a Jira-status preflight at
the server launch boundary. The Jira status adapter and pure hard-coded
preflight added during that investigation were removed. Factory stays unaware
of Jira and of the meaning of `Ready`. ProductEngineer owns the substantive
readiness judgment; the workflow definition supplies its instructions and
required Factory output.

### Proposed first step contract

| Part | Contract |
| --- | --- |
| Responsibility | `agent: ProductEngineer`, supplied and verified by BD02 before activation. |
| Entry | Read the explicit Jira ticket, workstream context and reachable source SPEC from the assigned environment; use current Jira evidence, not a launch-time status claim or chat text. |
| Mission | Decide whether this ticket is sufficiently specified and currently `Ready` for delivery. Do not perform upstream grooming, change Jira, or write product/code files. |
| Exit on PASS | Submit Factory `PASS` with `claims.modifiedFiles=[]`; include the observed ticket key/status, source locator/revision, evidence references and reasons in the summary and evidence artifacts. A future wired read-only guard must reject workspace writes. |
| Exit when not ready | Submit an evidenced non-PASS result. The selector opens a durable `retry`/`abort` wait. No design, packet, Build or integration fragment is scheduled. |
| Retry | Re-run ProductEngineer with a fresh attempt and current evidence. The prior negative result remains in the Run trace. |

The first step is now in `WZ-36147-delivery-workflow.draft.json`, using the
ordinary configurable Factory workflow schema. It is deliberately outside
the published catalog: a one-step workflow would otherwise complete after
preflight. Extend and version this definition with assessment, packet, Build
and final joint before publication. The current legacy selector still starts
`forge.product-spec` and contains a trivial path to Build without the complete
packet gate. Existing pinned legacy Runs must retain their behavior. The
draft's schema test is not a launch or retry integration proof.

## Focused Factory evidence

| Contract | Observed result | Remaining proof |
| --- | --- | --- |
| Tenant artifact bytes (CE05, T08/T09) | `Neo4jArtifactStoreIntegrationTest` passes put/open/hash, tenant metadata, retention and legal-hold cases. `ArtifactStore` accepts bytes with `TenantScope` and returns ID and hash. | Publish a pinned Whoz Git snapshot, attach its IDs to the parent Run, and verify stored bytes/provenance on resume. No plugin-facing publication port was found. |
| Official catalog (CE04/09, T22/T23) | `WorkflowDefinitionSeeder.seed()` skips an existing type/version; the authoring controller accepts user registration/upload. | Protect an official Forge type/version against tenant shadowing and pin its package provenance. |
| Two human branches (CE07/08, T14/T20/T21) | `WorkflowRoutingTest` passes generic AND-join readiness and shows human waiting is not success. | Bind both authorized replies to one current packet/candidate generation, re-read observed deployment, invalidate both on change, and prove restart/races through the API. |
| Parent launch (CE04, T11/T12/T17) | `RunService.startRun()` pins a selector, persists one parent `RunRecord`, uses a deterministic root Case ID and replays an identical `runId` with a persisted provisioning intent. An agent `FAIL` can lead to a durable run-level human escalation with `retry`/`abort`. | Prove one delivery parent/active Case mapping, retry from a negative ProductEngineer result, and reject a second active **delivery** Run for the same ticket while allowing a separate upstream readiness Run. Caller-chosen `runId` alone does not enforce this ticket/run-type uniqueness. |

Focused Gradle commands:

```text
./gradlew :factory-service:test --tests 'io.whozoss.factory.artifact.infrastructure.persistence.Neo4jArtifactStoreIntegrationTest' --tests 'io.whozoss.factory.workflow.domain.WorkflowRoutingTest' :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.workflow.domain.ForgeDeliveryDraftDefinitionTest' --no-daemon
```

Both commands passed. The JSON draft passes Factory's authoritative definition
validator. The earlier Kotlin preflight suite was removed with its
implementation; the draft is not a registered or executable delivery workflow.

These results do **not** satisfy T08/T09/T14/T19–T23. They need persisted API,
race, restart, and integration-receipt proofs on the actual versioned delivery
workflow. BD02 must supply ProductEngineer routing and ForgeSupervisor before
either agent is activated in this workflow or cockpit. These checks did not
run a pilot, create a Jira subtask, merge or deploy anything.

## Remaining architecture work

No pre-provisioning Jira-specific core hook is needed for the revised entry
contract. A separate generic server decision may still be required for atomic
active-Run uniqueness by `(tenant, workstream, workflow type, ticket)`; the
existing `runId` replay is insufficient when two callers choose different IDs.
Snapshot publication, official catalog protection and candidate-bound human
joint likewise need focused plugin/API designs and proofs before any core
change. Preserve Factory's domain-neutral boundary and do not claim Lot 3 PASS
until those contracts and the CE05–07 gates are exercised.
