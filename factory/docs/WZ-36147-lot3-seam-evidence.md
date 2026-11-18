# WZ-36147 Lot 3 — delivery entry and Factory seam evidence

Status: **CE04 pre-Build draft prepared; CE04–07 are not execution ready**. This is a
review artifact, not a Factory Run or a Jira decision. The Coday branch was
merged with the refreshed `origin/workstream/forge/master` before inspection.

## Delivery entry decision (2026-11-17)

The requester confirmed the configurable first-step approach and the
unversioned term “Factory” on 2026-11-18. This validates the entry contract,
not publication of the draft or the downstream Build gates.

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

The version `0.2.0` draft in `WZ-36147-delivery-workflow.draft.json` uses the
ordinary configurable Factory workflow schema. It defines nine ordered steps:
readiness, applicability assessment, product/UX/technical design,
implementation planning, QA and verification planning, packet assembly, then
the human packet gate. Optional design disciplines still get an explicit
assessment step and either a document or a reasoned not-applicable decision;
the implementation and verification plans are required on every route. The
definition is deliberately outside the published catalog: it would complete
after packet approval and has no safe Build or final joint. Packet assembly
also lacks the publication seam needed to supply a trusted stored artifact ID
to the human gate. The current legacy selector still starts
`forge.product-spec` and contains a trivial path to Build without the complete
packet gate. Existing pinned legacy Runs must retain their behavior. The
draft's graph test is not a launch, packet approval or retry integration proof.

The plugin now rejects repository writes during `delivery-preflight` and
`design-assessment`. The latter step must submit one `forge.business-outputs.v1`
artifact with an `applicability` decision for detailed, UX, technical and QA
specifications; each decision has a Boolean `applicable` and a nonempty reason.
Missing or malformed decisions fail the step. These checks validate the
agent's result shape and observed workspace delta; they do not verify Jira
readiness or publish the design documents.

The `implementation-planning` step now rejects an empty or malformed
`delivery_plan.steps[]`, including duplicate IDs, unsafe/empty file scopes and
missing verification commands. The `qa-design` step requires nonempty
`verification_plan` Markdown. Both use the existing Forge business-output
artifact. These are result guards for every route, including trivial work;
they do not prove that the corresponding plan files were committed or copied
into `ArtifactStore`. The manifest's byte check remains required before the
human gate.

## Focused Factory evidence

### CE05 packet contract (T07)

`ForgePacketManifest` now provides a pure validation boundary for the design
packet. Its `packet-manifest.v1` input identifies the source repository and
pinned commit, records an explicit applicability decision for source, detailed,
technical, UX and QA specifications and both plans, and lists every applicable
document with a normalized relative path, locator, revision, size and SHA-256.
The source specification, implementation plan and verification plan are always
required. Every listed revision must match the pinned source commit. The
validator compares the manifest to the actual document bytes, rejects
missing/extra documents, duplicate or unsafe paths, contradictory applicability,
revision/hash mismatches and blank plans, then returns canonical manifest bytes
and their digest. The caller must persist those exact bytes before presenting
the packet to a human gate.

This establishes the local T07 shape and rejection cases only. The current
test uses in-memory fixture bytes; it does not prove a Whoz Git snapshot,
ArtifactStore publication, a packet ID bound to a human decision, or restart
behavior. Those require the remaining CE05/CE07 integration seams.

### CE06 standard-worker step guards

The Forge brief and result validator now select implementation, correction,
verification and codebase-research obligations by flow/step identity rather
than `ForgeBuilder`, `ForgeVerifier` or `Searcher` agent names. Backend and
Frontend therefore receive the declared file scope and cannot pass an
out-of-scope edit by using a different agent name. Verification and research
remain read-only for renamed agents. A `forge.delivery` implementation or
correction step also fails closed when its file scope is absent, unsafe or
duplicated; legacy `forge-bmad` Runs retain their existing optional-scope
behavior. These are synchronous plugin guards over Factory's captured workspace
delta, not proof that the new fleet is routed or that the complete delivery
Run is executable.

| Contract | Observed result | Remaining proof |
| --- | --- | --- |
| Tenant artifact bytes (CE05, T08/T09) | `Neo4jArtifactStoreIntegrationTest` passes put/open/hash, tenant metadata, retention and legal-hold cases. `ArtifactStore` accepts bytes with `TenantScope` and returns ID and hash. | Publish a pinned Whoz Git snapshot, attach its IDs to the parent Run, and verify stored bytes/provenance on resume. No plugin-facing publication port was found. |
| Official catalog (CE04/09, T22/T23) | `WorkflowDefinitionSeeder.seed()` skips an existing type/version; the authoring controller accepts user registration/upload. | Protect an official Forge type/version against tenant shadowing and pin its package provenance. |
| Two human branches (CE07/08, T14/T20/T21) | `WorkflowRoutingTest` passes generic AND-join readiness and shows human waiting is not success. | Bind both authorized replies to one current packet/candidate generation, re-read observed deployment, invalidate both on change, and prove restart/races through the API. |
| Parent launch (CE04, T11/T12/T17) | `RunService.startRun()` pins a selector, persists one parent `RunRecord`, uses a deterministic root Case ID and replays an identical `runId` with a persisted provisioning intent. The new integration characterization passes with **two** active `forge.delivery` parent Runs, distinct Case IDs and fragments for the same ticket when callers choose different `runId`s. `FACTORY_WORKSTREAM.start_workflow` calls `CreateWorkflowRunService`, which creates a direct workflow rather than a parent Run. | Supply a governed delivery entry that creates the parent Run, enforces atomic active-ticket uniqueness and keeps ProductEngineer as the configurable first step. Prove negative preflight retry/recovery; reject a separate direct-workflow launch of the official type. |

Focused Gradle commands:

```text
./gradlew :factory-service:test --tests 'io.whozoss.factory.artifact.infrastructure.persistence.Neo4jArtifactStoreIntegrationTest' --tests 'io.whozoss.factory.workflow.domain.WorkflowRoutingTest' :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.workflow.domain.ForgeDeliveryDraftDefinitionTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgePacketManifestTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeStepRoleGuardTest' --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeStepRoleGuardTest' --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' :factory-service:test --tests 'io.whozoss.factory.workflow.domain.ForgeDeliveryDraftDefinitionTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.run.ParentRunWorkspaceProvisioningIntegrationTest' --no-daemon
```

The first four commands passed before the `0.2.0` draft change. The latest
combined command passed on the updated files: 8 step-role cases, 18 existing
Forge scenarios and 1 Factory definition case, all with zero failures. Gradle
executed the definition test after the JSON draft was declared as a test input.
The CE04 characterization command passed 9 integration tests, including the
two-Run same-ticket case; it demonstrates a missing uniqueness fence, not an
accepted delivery launch.
The earlier Kotlin preflight suite was removed with its implementation; the
draft is not a registered or executable delivery workflow.

These results do **not** satisfy T08/T09/T14/T19–T23. They need persisted API,
race, restart, and integration-receipt proofs on the actual versioned delivery
workflow. ProductEngineer and ForgeSupervisor definitions with Factory tools
already exist in the inspected Whoz checkout, but their effective runtime
routing and package pins are not verified; WZ-36113's observed routing commit
is not an ancestor of that checkout's current HEAD. This is an activation
proof still needed, not a blanket reason to stop Coday development. These
checks did not run a pilot, create a Jira subtask, merge or deploy anything.

## Remaining architecture work

No pre-provisioning Jira-specific core hook is needed for the revised entry
contract. The same-ticket integration test proves that a generic server
decision is required for atomic active-Run uniqueness by `(tenant, workstream,
run type, ticket)`; existing `runId` replay is insufficient. The Forge plugin
compiles against `factory-sdk` alone, and that SDK exposes no scoped
`ArtifactStore` publication port. `ArtifactService` exists only in
`factory-service`; injecting it by implementation class would break the plugin
boundary. `FACTORY_WORKSTREAM.start_workflow` cannot launch the required parent
Run on its current path. The generic human reply path does not bind an
authorized decision to a stored packet/candidate identity, as the Lot 4
characterization records. The concrete decision request is in
`WZ-36147-lot3-core-seams-decision.draft.md`. Preserve Factory's domain-neutral
boundary and do not claim Lot 3 PASS until CE04–07 execute through those
contracts with persistence, retry and restart proof.
