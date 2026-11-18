# WZ-36147 Lot 3 — delivery entry and Factory seam evidence

Status: **CE04 pre-Build draft prepared; CE04–07 are not execution ready**. This is a
review artifact, not a Factory Run or a Jira decision. The Coday branch was
merged with the refreshed `origin/workstream/forge/master` before inspection.

## Delivery entry decision (2026-11-17)

The requester confirmed the configurable first-step approach and the
unversioned term “Factory” on 2026-11-18. This validates the entry contract,
not publication of the draft or the downstream Build gates.

The workflow for bringing a ticket to `Ready` is a separate, potentially more
interactive Run. This Lot implements only the **delivery** Run. Multiple
delivery Runs may also target the same Jira ticket, even with the same
definition; each has its own identity and worktree. The 2026-11-18 product
clarification permits this concurrency. A detected overlap may produce a
nonblocking, permission-filtered warning.

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
also lacks the Run-bound Git identity needed by the human gate. The current legacy selector still starts
`forge.product-spec` and contains a trivial path to Build without the complete
packet gate. Existing pinned legacy Runs must retain their behavior. The
draft's graph test is not a launch, packet approval or retry integration proof.

The plugin now rejects repository writes during `delivery-preflight` and
`design-assessment`. The latter step must submit one `forge.business-outputs.v1`
artifact with an `applicability` decision for detailed, UX, technical and QA
specifications; each decision has a Boolean `applicable` and a nonempty reason.
Missing or malformed decisions fail the step; accepted decisions enter the
parent Run domain state through the validator's existing transactional update.
These checks validate the agent's result shape and observed workspace delta;
they do not verify Jira readiness or publish the design documents.

The `implementation-planning` step now rejects an empty or malformed
`delivery_plan.steps[]`, including duplicate IDs, unsafe/empty file scopes and
missing verification commands. The `qa-design` step requires nonempty
`verification_plan` Markdown. Accepted plans enter the parent Run domain state.
Both use the existing Forge business-output artifact. These are result guards for every route, including trivial work;
they do not prove that the corresponding plan files were committed or copied
into a Git commit. The manifest's byte check remains required before the
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
and their digest. The trusted caller must bind the canonical manifest identity
and source Git commit to the Run before presenting the packet to a human gate.

This establishes the local T07 shape and rejection cases only. The current
test uses in-memory fixture bytes. `ForgeGitPacketSnapshotTest` separately uses
a real local Git repository: it reads the pinned commit after the branch and
worktree advance, and rejects a missing commit, missing document, wrong source
repository and mismatched content digest. This does not yet prove a trusted
Whoz workstream binding, a manifest identity persisted in the parent Run, a
human decision on that identity, Git-outage pausing or restart behavior.

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
| Tenant artifact bytes (CE05, T08/T09) | `Neo4jArtifactStoreIntegrationTest` passes put/open/hash, tenant metadata, retention and legal-hold cases. `ArtifactStore` is Factory-owned; Neo4j holds metadata and object storage holds bytes. Default retention is 90 days and the default blob backend is in-memory. | These tests do not require or prove a Factory blob copy for Forge. Pin the Whoz Git commit and manifest identity, bind the gate to them, and verify source Git availability/content on resume. |
| Official catalog (CE04/09, T22/T23) | `WorkflowDefinitionSeeder.seed()` skips an existing type/version; the authoring controller accepts user registration/upload. | Protect an official Forge type/version against tenant shadowing and pin its package provenance. |
| Two human branches (CE07/08, T14/T20/T21) | `WorkflowRoutingTest` passes generic AND-join readiness and shows human waiting is not success. | Bind both authorized replies to one current packet/candidate generation, re-read observed deployment, invalidate both on change, and prove restart/races through the API. |
| Candidate facts (CE07, T19) | `ForgeCandidateSealTest` checks a deterministic candidate identity for one Run/generation and refuses dirty or colliding code, absent review/verification, CI commit mismatch and observed deployment commit/build mismatch. | Obtain these observations from trusted Git, CI and deployment sources; persist a generation, re-read deployment on human decisions/joint, and join both decisions on it. The Whoz build workflow dispatches deployment and the deploy workflow reports command success, but the inspected Factory, AgentOS, app and integration code has no reader that binds an observed target to the exact commit and built artifact. |
| Parent launch (CE04, T11/T12/T17) | `RunService.startRun()` pins a selector, persists one parent `RunRecord`, uses a deterministic root Case ID and replays an identical `runId` with a persisted provisioning intent. The integration characterization passes with **two** active parent Runs, distinct Case IDs and fragments for the same ticket. `FACTORY_WORKSTREAM.start_parent_run` now resolves a request key to a parent Run ID and calls `RunService`; a second key is allowed and visible same-workstream runs are reported only as an advisory warning. `start_workflow` remains the separate direct-workflow path. | Prove the official `forge.delivery` selector/definition path, real AgentOS worktree isolation, negative preflight retry/recovery, permission-filtered cross-workstream warning if later required, and API restart behavior. |

Focused Gradle commands:

```text
./gradlew :factory-service:test --tests 'io.whozoss.factory.artifact.infrastructure.persistence.Neo4jArtifactStoreIntegrationTest' --tests 'io.whozoss.factory.workflow.domain.WorkflowRoutingTest' :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.workflow.domain.ForgeDeliveryDraftDefinitionTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgePacketManifestTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeStepRoleGuardTest' --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeStepRoleGuardTest' --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' :factory-service:test --tests 'io.whozoss.factory.workflow.domain.ForgeDeliveryDraftDefinitionTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.run.ParentRunWorkspaceProvisioningIntegrationTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.workstreamservice.WorkstreamServiceControllerHttpTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeGitPacketSnapshotTest' --tests 'io.whozoss.factory.forge.bmad.ForgePacketManifestTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.workstreamservice.ParentRunStartFacadeTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeStepRoleGuardTest' --tests 'io.whozoss.factory.forge.bmad.ForgeCandidateSealTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.workstreamservice.WorkstreamServiceControllerHttpTest' --tests 'io.whozoss.factory.workstreamservice.ParentRunStartFacadeTest' --tests 'io.whozoss.factory.openapi.FactoryOpenApiControllerHttpTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.run.ParentRunWorkspaceProvisioningIntegrationTest' --no-daemon
```

The first four commands passed before the `0.2.0` draft change. The latest
combined command passed on the updated files: 8 step-role cases, 18 existing
Forge scenarios and 1 Factory definition case, all with zero failures. Gradle
executed the definition test after the JSON draft was declared as a test input.
The CE04 characterization command passed 9 integration tests, including the
two-Run same-ticket case; it demonstrates permitted Run identity behavior,
but not yet isolated worktrees or an accepted official delivery launch.
The Git packet command passed with `BUILD SUCCESSFUL` on the updated plugin.
It exercises actual Git objects in a temporary local repository, not Whoz
workstream routing, parent-Run persistence or a human gate.
The `ParentRunStartFacadeTest` command passed with `BUILD SUCCESSFUL`. It checks
same-key replay, distinct-key parallel launches, distinct root Case IDs and an
advisory same-workstream overlap response at the controller/service boundary.
The Run service is mocked in this focused test; the separate provisioning
integration characterization proves persistence but not this HTTP route.
The combined HTTP and OpenAPI command passed with `BUILD SUCCESSFUL` after
the new endpoint was added. The provisioning integration command was rerun
after the conflict error was mapped to HTTP 409 and its assertion strengthened;
it passed with `BUILD SUCCESSFUL in 1m 54s`. A further HTTP route test now
checks that unpublished `forge.delivery` is rejected instead of starting a
partial Run; its targeted command passed with `BUILD SUCCESSFUL in 2m 3s`.
The candidate seal command passed with `BUILD SUCCESSFUL`; it tests the pure
identity checks with supplied observations, not that Factory currently has
trusted CI or deployment receipt readers. The plan/applicability validator
returns parent Run state updates, but no complete delivery selector consumes
them yet.
AgentOS `CaseWorktreeProvisioner.worktreePath()` resolves each checkout below
its root Case exchange directory, and `CaseWorktreeProvisionerSpec` contains a
two-root-Case isolation scenario. Those are code and existing-test evidence;
the Factory characterization uses a mocked AgentOS provisioner and does not
exercise both services together. The clarified product contract permits these
parallel Runs and allows only a nonblocking overlap warning.
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

No pre-provisioning Jira-specific core hook or active-ticket uniqueness fence
is needed. Existing `runId` replay covers repeated requests with the same
identity; different requests for one ticket are allowed. The new generic
`FACTORY_WORKSTREAM.start_parent_run` path calls the existing parent-Run
service, while `start_workflow` remains a direct-workflow operation. No
`forge.delivery` selector or complete definition is registered yet. Factory's
`ArtifactStore` is separate from AgentOS exchanges, and its blob publication
port is not a Forge prerequisite under the clarified Git-first packet rule.
The manifest/decision identity and trusted Git-read boundary still need a
concrete persistence path. The generic human reply path does not bind an
authorized decision to that packet/candidate identity, as the Lot 4
characterization records. The updated product rule and remaining proof are in
`WZ-36147-lot3-core-seams-decision.draft.md`. Preserve Factory's domain-neutral
boundary and do not claim Lot 3 PASS until CE04–07 execute through those
contracts with persistence, retry and restart proof.

The current Lot 4 `ForgePacketIdentity` names an `artifactId` and SHA-256.
The clarified Forge source is instead a Git repository, pinned commit and
canonical manifest SHA-256. The pure authority rules can still govern stale
decisions, but their packet identity must be aligned with that Git source
before the human gate is wired. This is a verified contract mismatch in the
present checkout, not an assumption about a future Lot 4 delivery.

### Verified execution boundary for CE05/CE07

`CapabilityExecutionService.evaluateStepResultValidators()` runs plugin
validation while finalizing the attempt in a short database transaction.
`StepValidationContext` carries the report and workspace delta, but no trusted
Git reader or worktree path. Running `git cat-file` inside that transaction
would violate the service's explicit external-work/transaction split. The
generic `code` responsibility executes commands declared by the target
repository's `factory/verification.json`; the inspected Whoz checkout has no
such manifest. The new `ForgeGitPacketSnapshot` proves the local Git read in
isolation, but it cannot be wired into the gate by the existing plugin SPI.

The worker result permits inline artifacts up to 256 KiB each; the packet
manifest can be carried there as metadata while document content stays in
Git. Its own commit cannot be embedded in the same Git commit that it names,
so Factory must persist the resulting canonical manifest identity separately
from the committed documents. The required trusted sequence is: commit the
documents, observe that commit and its bytes outside a DB transaction, then
atomically bind the verified manifest identity to the parent Run and gate.

### Proposed narrow core extension — decision still required

Scenario: ProductEngineer finishes `packet-assembly` and submits a manifest.
Before asking a human to approve it, Factory resolves the worktree already
bound to this parent Run, asks a plugin observer to inspect the pinned Git
objects outside a database transaction, then records the checked manifest
identity with the completed attempt and parent Run in the final short
transaction. If the commit or worktree is unavailable, the Run pauses with a
technical reason; neither the human gate nor Build opens. A crash before the
final transaction repeats the observation on retry. Before approval, Build or
resume, the same Git objects must be rechecked; a moved branch does not change
the approved commit.

The smallest proposed Factory change is a domain-neutral pre-finalization
evidence observation SPI. Factory supplies the trusted Run/worktree identity
and attempt token; the Forge plugin supplies the Git-specific check and a
bounded immutable receipt. Factory atomically stores only the receipt and
manifest metadata, never the document bytes. A repository-declared `code`
verification cannot currently replace this: the Whoz checkout has no
`factory/verification.json`, and a plain command exit code would not bind a
manifest digest to the parent Run and human decision. Architecture spine AD-7
requires a separate decision for a new generic Factory core extension. This
proposal is therefore intentionally unwired until that decision is made.

The inspected Whoz `.github/workflows/build.yml` dispatches integration
deployment, and `.github/workflows/deploy.yml` runs a remote deployment script
before writing a success summary. The remote `infra/compose/whoz/scripts/deploy.sh`
also records the release tag in deployed dynamic properties and inspects
container image IDs when redeploying. These are useful observation sources,
but the dispatch, successful command and tag alone do not supply Factory with
a receipt binding the observed target to the exact code commit and built image
digests. No reader for that receipt was found in the inspected Coday modules,
so the CE07 pure seal cannot yet attest a live candidate.
