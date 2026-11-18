# WZ-36147 Lot 3 — delivery entry and Factory seam evidence

Status: **CE04 pre-Build draft prepared; CE04–07 are not execution ready**. This is a
review artifact, not a Factory Run or a Jira decision. The Coday branch was
merged with the refreshed `origin/workstream/forge/master` before inspection.

## Single-workflow correction (2026-11-18)

The requester requires one published `forge.delivery` definition and one
workflow execution visible in the Factory cockpit from the Run button through
final validation. The cockpit already launches a selected definition through
`POST /api/factory/workflows`; its resulting `workflowId` must own all delivery
steps. No `FactoryFlowSelector` parent Run or chain of `forge.*` fragment
workflows is part of this delivery design. The experimental
`FACTORY_WORKSTREAM.start_parent_run` endpoint, its OpenAPI/tool documentation
and its endpoint tests have been removed from this PR. The pre-existing
`forge-bmad` parent-Run machinery remains available for other uses.

The existing `forge.delivery` JSON is already one workflow, but stops at the
packet gate and must remain unpublished. The single-workflow engine supports
ordered and parallel steps, one bounded conditional split/join, durable human
waits and step retry. It does not currently express an arbitrary number of
planned implementation steps or a cyclic correction path as distinct dynamic
nodes in the same definition. Those capabilities must be added to the
single-workflow execution model, or represented as bounded work within a
fixed step while keeping each substep's evidence in the same workflow. The
choice must be verified against CE06/CE07 and their required proofs before
publication; it is not a reason to silently switch back to parent fragments.

Some prior evidence below characterizes the parent-Run route and remains a
historical test result, not evidence for the now-required delivery launch.
In particular, the Git packet observer currently requires a parent Run's
persisted work environment and cannot yet observe a direct workflow Run.
Its Git manifest validation is reusable, but the trusted worktree binding,
evidence persistence and technical-pause recovery must be adapted to the
single `workflowId` before the packet gate is wired.

The direct-launch inspection locates the precise mismatch. A regular
`CreateWorkflowRunService.create()` stores a workflow instance and submits its
Run, but does not provision a `WorkEnvironment`. The existing packet observer
requires an occupied `WorkEnvironment` owned by `parentRunIdOf(instance)`;
`acquireAgentWorktree()` returns without an occupation when that parent ID is
absent. Thus the first direct `forge.delivery` packet submission reaches
`WORKTREE_UNAVAILABLE`, even if AgentOS created a Case worktree. The Forge
validator also returns `newDomainState` for applicability, plans and packet
identity, while `CapabilityExecutionService` applies those updates only when
a parent Run ID exists. Direct workflow evidence must therefore be persisted
and read through the workflow instance and its own trusted Case/worktree
binding; changing only the observer's ID comparison would leave both holes.
The targeted HTTP test `WorkflowControllerHttpTest.delivery draft direct
launch...` passed (`BUILD SUCCESSFUL in 2m 29s`): with the draft step graph
registered in the isolated fixture, two idempotency keys for WZ-36147
created distinct direct workflow IDs and replay of the first key returned its
original ID. The fixture omits `execution.plugin=forge` because PF4J is not
installed in that HTTP test; this proves CE04 launch identity only, not plugin
activation, Case/worktree isolation or end-to-end delivery.

Correction verification: `./gradlew :factory-service:test --tests
'io.whozoss.factory.workstreamservice.WorkstreamServiceControllerHttpTest'
--tests 'io.whozoss.factory.workflow.domain.ForgeDeliveryDraftDefinitionTest'
--no-daemon` passed with `BUILD SUCCESSFUL in 1m 45s` (17 controller tests and
one draft-definition test, zero failures). This verifies the removed endpoint
does not break those paths; it is not an end-to-end delivery run.

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
| Delivery launch (CE04, T11/T12/T17) | The earlier `RunService.startRun()` characterization passed with two active parent Runs for one ticket, but that is **historical evidence for a superseded path**. The cockpit Run button already uses `POST /api/factory/workflows` to launch one selected definition. `start_parent_run` was removed from this PR. | Prove two independent `forge.delivery` workflow instances on the direct launch path, separate Case/worktree identities, exact-request replay, negative preflight retry/recovery, and API restart behavior. An optional overlap warning must respect access boundaries. |

Focused Gradle commands:

```text
./gradlew :factory-service:test --tests 'io.whozoss.factory.artifact.infrastructure.persistence.Neo4jArtifactStoreIntegrationTest' --tests 'io.whozoss.factory.workflow.domain.WorkflowRoutingTest' :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.workflow.domain.ForgeDeliveryDraftDefinitionTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgePacketManifestTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeStepRoleGuardTest' --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeStepRoleGuardTest' --tests 'io.whozoss.factory.forge.bmad.ForgeBmadFlowAndValidatorTest' :factory-service:test --tests 'io.whozoss.factory.workflow.domain.ForgeDeliveryDraftDefinitionTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.run.ParentRunWorkspaceProvisioningIntegrationTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.workstreamservice.WorkstreamServiceControllerHttpTest' --no-daemon
./gradlew :factory-forge-plugin:test --tests 'io.whozoss.factory.forge.bmad.ForgeGitPacketSnapshotTest' --tests 'io.whozoss.factory.forge.bmad.ForgeStepRoleGuardTest' :factory-service:test --tests 'io.whozoss.factory.run.CapabilityValidationFinalizationRollbackIntegrationTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.run.CapabilityValidationFinalizationRollbackIntegrationTest' --no-daemon
./gradlew :factory-service:test --tests 'io.whozoss.factory.agentattempt.BridgeRecoveryWorkerTest' --tests 'io.whozoss.factory.agentattempt.BridgeRecoveryWorkerWorkspacePreflightTest' --no-daemon
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
The new external-observation command first found a Git reader classification
bug: a document larger than its declared size was reported as unavailable.
After bounding reads by actual cumulative packet size, the same focused plugin
and service command passed with `BUILD SUCCESSFUL in 3m 29s`. The revised
integration scenario then passed with `BUILD SUCCESSFUL in 3m 52s`: a simulated
Git outage deferred advancement and persisted a technical finding; a later
finalization rollback preserved that pause; recovery reobserved outside the
transaction, wrote one accepted result and resolved the finding. The bridge
worker regression command passed with `BUILD SUCCESSFUL in 3m 53s`; it covers
the shared recovery paths after the new nonterminal observation branch.
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
`StepValidationContext` originally carried the report and workspace delta but
no trusted Git reader or worktree path. Running `git cat-file` inside that
transaction would violate the service's explicit external-work/transaction split. The
generic `code` responsibility executes commands declared by the target
repository's `factory/verification.json`; the inspected Whoz checkout has no
such manifest. `FactoryStepEvidenceObserver` now calls `ForgeGitPacketSnapshot`
before that transaction, using the persisted work environment and the
attempt's physical worktree identity. The packet validator fails closed when
the observation receipt is missing, and the receipt is written into the parent
Run with the accepted result. This is the pre-gate seam, not a live human gate.

The worker result permits inline artifacts up to 256 KiB each; the packet
manifest can be carried there as metadata while document content stays in
Git. Its own commit cannot be embedded in the same Git commit that it names,
so Factory must persist the resulting canonical manifest identity separately
from the committed documents. The required trusted sequence is: commit the
documents, observe that commit and its bytes outside a DB transaction, then
atomically bind the verified manifest identity to the parent Run and gate.

### Narrow core extension — approved and implemented as an unpublished seam

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

The requester approved a domain-neutral pre-finalization evidence observation
SPI on 2026-11-18. Factory supplies the trusted Run/worktree identity and
attempt token; the Forge plugin supplies the Git-specific check and a bounded
immutable receipt. Factory atomically stores only the receipt and manifest
metadata, never the document bytes. The bridge recovery path now repeats this
observation before finalizing a recovered result. A repository-declared `code`
verification cannot currently replace this: the Whoz checkout has no
`factory/verification.json`, and a plain command exit code would not bind a
manifest digest to the parent Run and human decision. A Git outage persists a
technical-pause evidence item and Run finding and defers advancement; an
observed retry resolves the finding. The attempt remains nonterminal, and
recovery follows its lease rules. This does not yet prove an immediate
operator-triggered resume or a complete gate decision, since the delivery
workflow is still unpublished.

The inspected Whoz `.github/workflows/build.yml` dispatches integration
deployment, and `.github/workflows/deploy.yml` runs a remote deployment script
before writing a success summary. The remote `infra/compose/whoz/scripts/deploy.sh`
also records the release tag in deployed dynamic properties and inspects
container image IDs when redeploying. These are useful observation sources,
but the dispatch, successful command and tag alone do not supply Factory with
a receipt binding the observed target to the exact code commit and built image
digests. No reader for that receipt was found in the inspected Coday modules,
so the CE07 pure seal cannot yet attest a live candidate.
The existing Factory `DeliveryOperationService` is not that reader: its
documented current path uses stub adapters and immediately reconciles a new
deployment operation to `succeeded` with only adapter id/state. Its
`sourceCommit` and `artifactDigest` fields validate request consistency, but
the service does not inspect the running integration target or a built image.
Using its success flag as a Forge deployment receipt would therefore be a
false positive. The Whoz test design requires CI provenance and an observed
deployment receipt for the exact integrated SHA and build digest (T19/T24).

### Integration observation from the Talent Portal example (2026-11-18)

The requester identified Whoz PR #8488 as an in-flight work-branch example.
Its observed head is `cbf17264d4dbbb58cc63cadfdfe92ea63d8c8977` on
`work/talent-portal/WZ-35341_no_npx`; the remote
`integration/talent-portal` head was still
`d48c202351acf0af3bc91b78aa2337c0840a53e3` at the last read. Thus no
integration of this PR was observed yet. GitHub's private PR/check API was not
available through the current read-only access; a passing validation job or
deployment for #8488 has **not** been observed.

The requester subsequently supplied the `Push Work to Integration` job
capture for this PR. It shows the attempted merge of
`work/talent-portal/WZ-35341_no_npx` into `integration/talent-portal`
returning HTTP 409 with `"message": "Merge conflict"`, and the job exiting
with code 1. This is positive evidence of a **failed integration attempt**,
not merely a pending CI check. It does not identify the conflicting files or
show that a later attempt succeeded.

The Whoz `.github/workflows/validate.yml` runs on PR open, synchronize,
reopen and ready-for-review. Its `push-work-to-integration` job depends on the
required validation jobs and invokes `tools-validate:exec-push-work-to-integration`
only on their success. That executor calls GitHub's branch merge API with the
work branch as head and `integration/<workstream>` as base, leaving the PR
open. Existing integration merge commits have two parents and contain `Source`
and `Target` in their message; the second parent is the included work-branch
commit. Parentage, rather than the human-readable message alone, proves which
work revision was included. A 204 response also means the source was already
contained; the observer must then check ancestry instead of expecting a new
merge commit. More recent integration commits can include additional work, so
the candidate must pin the exact integrated tree, not simply the work SHA.

The Whoz `Build` workflow runs on pushes to `integration/**`. After a successful
build it dispatches `Deploy` with the exact generated RC tag. Tag promotion
creates the RC Git tag at `github.sha`, so the tag resolves to the built
integration commit. `Deploy` serializes runs per target and invokes the
workstream deployment script with that tag; the script writes the tag into
runtime dynamic properties. A successful dispatch only proves that deployment
was requested; a successful job alone does not prove the target still runs
that release when a human later validates it. The current dynamic properties
expose the tag, but no observed image-digest manifest or Factory reader was
found. This is the concrete missing receipt for T19/T24.

#### Observed #8488 chain after conflict resolution (2026-11-18)

The failed `Push Work to Integration` attempt returned HTTP 409 (`Merge
conflict`), so it was correctly insufficient to open functional validation.
After the work branch was repaired, the [successful push job](https://github.com/biznet-io/whoz/actions/runs/44362869370/job/135032454278)
merged `work/talent-portal/WZ-35341_no_npx` at `cbf17264d4dbbb58cc63cadfdfe92ea63d8c8977`
into `integration/talent-portal`. The resulting integration commit is
`1bfdb5f8dfa89880fa61675092fc7e1498a13e23`, with that work SHA as its
second parent. Read-only Git ancestry verification succeeded. The tag
`integration-talent-portal_rc100` resolves to the same integration commit.

The [integration Build run #11054](https://github.com/biznet-io/whoz/actions/runs/44369638977)
completed successfully at that commit. Its `promote-tags` and
`dispatch-integration-deployment` jobs succeeded, and the dispatch summary
named target `forge-talent-portal` and exact tag `integration-talent-portal_rc100`.
The dispatch was **not** treated as the deployment receipt. The separate
[Deploy run #7089](https://github.com/biznet-io/whoz/actions/runs/44371473992)
completed successfully at the same commit; its `deploy to workstream` job
reported target `forge-talent-portal`, release tag
`integration-talent-portal_rc100` and successful completion at
`2026-11-18 18:44:23 UTC`. This is a concrete, machine-addressable CI chain
that can establish *historical deployment success* for the integration commit.

Build #11054 skipped `backend-build` and `docker-build-and-push` while
`promote-tags` succeeded. The inspected promotion executor explicitly falls
back to `currentVersion` artifacts for unaffected services. Therefore the
integration Git SHA and RC tag do **not** by themselves identify the image
bytes that were deployed. A candidate receipt needs the per-service promoted
artifact digests (or a digest of an immutable manifest containing them), plus
a post-deploy target readback. The GitHub success summary does not prove that
the target still runs rc100 at a later human decision. The Factory has no
reader for that live version or image identity in this checkout.

The first automatic gate check can follow this chain: current work SHA is
contained in the integration commit; the successful Build run is for that
exact commit; its RC tag resolves to that commit; a successful **Deploy** run
names that tag and the authorized target. It must then compare a fresh target
readback with the tag and built/promoted artifact manifest before opening the
functional human request, and recheck before accepting the decision. A newer
deployment or changed work head starts a new candidate generation. The #8488
example proves the CI correlation path exists; it does not satisfy the missing
live readback or candidate seal.

#### First-version environment-independent contract

The delivery definition can have an integration-observation step after code
verification and before the two human validation branches. Its input is the
Run-bound work branch and approved packet; its output is one immutable
candidate identity or a durable wait with a technical reason. Factory only
stores the evidence and schedules/retries the step. A Forge-owned observer
uses an environment profile selected from trusted Run configuration:

| Profile | Evidence needed to open validation |
| --- | --- |
| Hosted Forge | Confirm the work commit is an ancestor of the integration commit; resolve the build's RC tag to that exact integration commit; obtain the built artifact digest/manifest; observe the workstream target running that tag and matching built digests. Pin all of these to one candidate generation. |
| Local | Pin the worktree/repository commit and the locally built version; record an explicit developer attestation that the services were started and checked at a named local target. Label this as *manual local evidence*, never as a CI deployment receipt. A changed commit or rerun starts a new candidate generation. |

The observer can poll on step entry and explicit retry/resume in version one;
CI webhooks are an optimization, not a prerequisite. Missing CI, pending
integration, failed deployment or unavailable Git leaves the step waiting or
technically blocked with the precise cause. In particular, #8488 must not
open functional validation while the old integration release remains live.
For a 409 merge conflict, retain the Run and its completed upstream evidence,
record `INTEGRATION_CONFLICT` with the source/target branch and attempted work
SHA, and wait for a conflict resolution or a new work commit followed by a
successful integration attempt. Do not retry the same unchanged conflict in
a tight loop or treat this job's success prerequisites as proof of a merge.
After a new attempt, independently confirm ancestry of the then-current work
commit in the integration branch before looking for build and deployment
evidence; a stale failed job must not permanently block a later valid
candidate. The wait is scoped to this Run's integration-dependent path, not
to unrelated Runs on the workstream.

The PR's agent review and technical preparation can proceed while integration
is blocked. The technical **human** request opens only once that review
applies to the pinned head and a candidate has been sealed; the current
`ForgeHumanAuthority` requires both human decisions to reference that same
candidate generation. A later integration deployment or PR head change
invalidates affected decisions. Hosted Forge must re-observe the live target
before each human decision and the final join; local mode rechecks the pinned
worktree/version and explicit manual evidence. This preserves the same
workflow shape without making Factory understand Whoz CI job names.

The first implementation boundary is therefore a Forge evidence reader plus
a durable candidate wait. The Whoz CI/deploy chain must emit or expose a
machine-readable build manifest and a post-deploy readback that binds target,
RC tag, integration commit and built digests. Until that exists, hosted
candidate sealing cannot truthfully pass T19/T24 and the official workflow
must remain unpublished. Treating dispatch or elapsed time as deployment
would open functional validation against potentially older code.
