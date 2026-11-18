# WZ-36147 Lot 3 — delivery entry and Factory seam evidence

Status: **CE04–07 draft through hosted integration observation prepared; the complete workflow is not execution ready**. This is a
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
nodes in the same definition. The requester clarified that the immediate aim
is adapting the Forge agents, not extending the Factory engine for dynamic
workflow nodes. The target therefore has fixed workflow phases: one Build
step would process the ordered plan items and record item-level progress and
evidence in the same Run; independent verification, review, integration
observation and human decisions remain separate fixed phases. Item rows in a
Build report are not separate workflow steps and have no independent engine
status or retry. The existing Backend/Frontend Factory skill accepts one
approved work item per attempt, and the strict Forge workers each execute one
item; none can receive the whole mixed-stack plan unchanged. AgentOS now has
`DELEGATE__delegate` and resumable sub-cases sharing an equipped root Case
worktree, so a narrow Build coordinator agent could dispatch those items
sequentially inside the one Build step. That coordinator and its item-level
receipt validation do not yet exist. The old ForgeController cannot simply
fill this role: it runs its own full lifecycle and gates. The Build/verification
correction and resume contract must still satisfy CE06/CE07 before publication.

Some prior evidence below characterizes the parent-Run route and remains a
historical test result, not evidence for the now-required delivery launch.
The Git packet observer can now receive the root Case worktree of a direct
workflow when AgentOS reports it READY and the path resolves locally in
Factory. Its observed facts are stored in the workflow's agent-result evidence.
For a hosted setup without a shared filesystem, AgentOS now exposes bounded,
authorized reads of a pinned Git commit and its document bytes from the root
Case worktree. Factory relays the authenticated actor identity and passes a
read-only Git-object interface to the Forge packet observer. The observer
checks the same manifest and exact bytes as the local path; Factory keeps only
the canonical manifest and digest, never a document copy. The remote read is
preferred when available, and an unreachable Git owner records a technical
pause. `CapabilityValidationFinalizationRollbackIntegrationTest` covers a
remote Case path with no Factory mount and the fail-closed unavailable route;
`ForgeGitPacketSnapshotTest` covers remote bytes and mismatch. These are
controlled integration fixtures, not a deployed Forge pilot.

The recovery path invokes the same observation method, but its direct-workflow
case has not yet been exercised by an integration test. Remote Git reads do
not yet provide an approved-packet gate.

The direct-launch inspection located the precise mismatch. A regular
`CreateWorkflowRunService.create()` stores a workflow instance and submits its
Run, but does not provision a `WorkEnvironment`. The earlier packet observer
required an occupied `WorkEnvironment` owned by `parentRunIdOf(instance)`;
`acquireAgentWorktree()` still returns without an occupation when that parent
ID is absent. Direct observation now uses the workflow's persisted root Case
ID and an AgentOS READY workspace snapshot, and refuses a missing or mismatched
worktree. The Forge validator also returns `newDomainState` for applicability,
plans and packet identity. Direct workflows now record each accepted state
snapshot in the same append-only agent-result evidence transaction, then
reconstruct the latest state for the next step. This preserves plan and packet
identity across restart without creating a parent Run. A human approval is
not yet bound to that packet, so Build remains unopened.
AgentOS exposes the Case workspace status and path through
`GET /api/cases/{caseId}/workspace/changes`, which Factory already reads for
workspace deltas. New `git-commit` and `git-blob` reads use the same Case access
boundary and AgentOS hardened Git runner; they reject commits outside the
Case's current HEAD history and unsafe paths. `workspace/diff` remains only a
working-tree change view. Forge delivery requires the AgentOS reader even if
a coincidental local path exists; other direct observers retain their local
fallback. This avoids treating an unrelated checkout with the same path as
the Run's Case workspace.
The direct-observation integration suite now also covers a remote Case path,
the authenticated AgentOS Git association and a second step recovering the
validated packet identity from the first step's durable evidence. The focused
`CapabilityValidationFinalizationRollbackIntegrationTest` run passed with
`BUILD SUCCESSFUL in 1m 49s`. The HTTP and Git-object tests exercise the
transport separately; none of these is a deployed Forge run or a human gate.
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

The version `0.6.0` draft in `WZ-36147-delivery-workflow.draft.json` uses the
ordinary configurable Factory workflow schema. It defines thirteen fixed steps:
readiness, applicability assessment, product/UX/technical design,
implementation planning, QA and verification planning, packet assembly, then
the human packet gate, one Build coordinator step, one independent verification
step, one whole-PR review and one integration observation
step. Optional design disciplines still get an explicit
assessment step and either a document or a reasoned not-applicable decision;
the implementation and verification plans are required on every route. The
implementation plan now requires a `frontend` or `backend` stack and an
explicit dependency on an earlier item (or `null`) for each ordered item.
This validates the proposed routing data and a structured Build receipt.
The direct workflow now persists the plan and pinned packet candidate in its
accepted evidence, but the human gate does not yet bind its decision to that
identity. The user chose a dedicated `ForgeBuilder`: the existing `BmadBuilder`
owns an older BMAD lifecycle and is not the Forge delivery coordinator. The
new agent must receive a bounded plan, delegate implementation by stack and
submit one Factory result; its configuration is now a local change on the
Whoz agent-fleet branch and has not been deployed. The definition is
deliberately outside the published catalog: it has an independent code review
step but would currently complete without the final human joint.
The current legacy selector still starts
`forge.product-spec` and contains a trivial path to Build without the complete
packet gate. Existing pinned legacy Runs must retain their behavior. The
draft's graph test is not a launch, packet approval or retry integration proof.

The plugin now rejects repository writes during `delivery-preflight` and
`design-assessment`. The latter step must submit one `forge.business-outputs.v1`
artifact with an `applicability` decision for detailed, UX, technical and QA
specifications; each decision has a Boolean `applicable` and a nonempty reason.
Missing or malformed decisions fail the step; accepted direct-workflow state
is preserved with the agent-result evidence, while legacy parent Runs retain
their existing transactional update.
These checks validate the agent's result shape and observed workspace delta;
they do not verify Jira readiness or publish the design documents.

The `implementation-planning` step now rejects an empty or malformed
`delivery_plan.steps[]`, including duplicate IDs, unsafe/empty file scopes and
missing verification commands. The `qa-design` step requires nonempty
`verification_plan` Markdown. Accepted plans enter the direct workflow's
durable evidence state (and the parent Run domain state on legacy flows).
Both use the existing Forge business-output artifact. These are result guards for every route, including trivial work;
they do not prove that the corresponding plan files were committed or copied
into a Git commit. The manifest's byte check remains required before the
human gate.

The draft's single `implementation-build` step assigns `ForgeBuilder` as the
full-stack coordinator. Its validator checks one ordered PASS receipt per
approved plan item, matching stack and manifest digest, a well-formed claimed
work commit, and each reported/observed changed file against the plan's file
scope. It rejects missing, reordered or extra receipts and out-of-scope
changes. The claimed work commit is not independently verified at Build
finalization; the later hosted observation checks its GitHub ancestry before
allowing integration evidence. The ForgeBuilder configuration has only static
YAML and scope checks, not an AgentOS runtime proof.
The separate `implementation-verification` step assigns `ForgeVerifier`, which
delegates the plan's commands in order to the existing Backend and Frontend
verifiers. Its result validator requires one matching green receipt and child
Case reference per item, with no repository writes. Those receipts are still
agent claims until the child Case commands are independently observed; neither
coordinator has an AgentOS pilot proof yet. The existing
ForgeController source confirms the delegation rule: it persists the ordered
plan and active item, sends `frontend` items to `ForgeFrontendWorker` and
`backend` items to `ForgeBackendWorker`, then advances only after item
verification. The new coordinator may retain that bounded item discipline,
but must not own workflow gates, whole-run review or completion.

`implementation-review` follows verification and is assigned to the existing
read-only `ForgeReviewer`, adapted locally in the Whoz fleet branch to find
the PR for the Build commit. The result guard requires four clean lens votes,
no critical or major finding, a matching claimed PR head and no file writes.
The Factory's trusted PR observer now reads the open PR through AgentOS and
requires its exact head to equal the accepted Build commit. This observer has
focused tests, not a live workflow proof. Integration observation branches from Build,
so CI waiting need not prevent verification and review from proceeding.

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
An additional regression recreated a local Git `replace` ref that made the
original commit name resolve to another commit's document bytes; it failed
before the fix. The packet reader now sets `GIT_NO_REPLACE_OBJECTS=1`, and the
same test passes with `CONTENT_MISMATCH`. This protects the local pinned-commit
check; it does not address the remote-host read gap above.

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
| Two human branches (CE07/08, T14/T20/T21) | `WorkflowRoutingTest` passes generic AND-join readiness and shows human waiting is not success. | Bind both authorized replies to the candidate presented for this Run and prove authorization, persistence, restart and races through the API. A later unrelated integration tag does not invalidate a human decision. |
| Candidate facts (CE07, T19) | `ForgeCandidateSealTest` checks a deterministic reference for one Run/generation and refuses unsafe or unverified code, absent work-commit ancestry, CI/tag mismatch and unsuccessful or different-tag deployment. | Read the Git ancestry, successful Build/tag and successful Deploy job from trusted sources; persist the tag and deployment run shown to the human. This is historical deployment evidence, not proof of the version the human actually tested. The exact-runtime artifact guarantee in the earlier T19/T24 draft needs alignment with the clarified product rule. |
| Delivery launch (CE04, T11/T12/T17) | The earlier `RunService.startRun()` characterization passed with two active parent Runs for one ticket, but that is **historical evidence for a superseded path**. The cockpit Run button already uses `POST /api/factory/workflows` to launch one selected definition. `start_parent_run` was removed from this PR. | Prove two independent `forge.delivery` workflow instances on the direct launch path, separate Case/worktree identities, exact-request replay, negative preflight retry/recovery, and API restart behavior. An optional overlap warning must respect access boundaries. |

After the 2026-11-18 product clarification, the focused
`ForgeCandidateSealTest` and `ForgeHumanAuthorityTest` command passed with
`BUILD SUCCESSFUL in 45s`. These pure rules now identify a successful
integration tag containing this Run's work commit, and a Git-pinned packet;
they do not read GitHub, execute deployment or enforce a persisted human gate.

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

On the direct workflow start path, `WorkflowRunAsyncHttpTest` now creates two
distinct workflow instances with the same ticket and definition, then replays
the first start request. Both new instances return 201 and create separate
durable submissions; the exact replay returns 200 and creates no third
submission. This proves there is no ticket-level uniqueness fence at launch.
The test uses a small registered human-step fixture, not the unpublished
Forge delivery definition, so it does not prove separate live AgentOS worktrees.

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
identity; different requests for one ticket are allowed. The cockpit's
existing `start_workflow` route is the required launch path for one complete
Forge workflow. The explored `start_parent_run` route was removed from this
PR when the single-workflow requirement was clarified. No
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

The pure `ForgePacketIdentity` now names the Git repository, pinned commit and
canonical manifest SHA-256, matching the clarified Forge source rather than a
Factory blob artifact ID. The authority rules are still not connected to the
generic human reply transaction or durable workflow state. Their passing unit
tests are not a gate authorization or restart proof.

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

### Parent Run observation seam — historical, unpublished

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
container image IDs when redeploying. Those are useful diagnostics. The
clarified gate requires a successful Deploy job for a tag whose Git commit
contains this Run's work commit; it does not attest which exact images a human
later tested. Factory still needs a trusted reader of that Git/CI job chain.
The existing Factory `DeliveryOperationService` is not that reader: its
documented current path uses stub adapters and immediately reconciles a new
deployment operation to `succeeded` with only adapter id/state. Its
`sourceCommit` and `artifactDigest` fields validate request consistency, but
the service does not inspect the running integration target or a built image.
Using its success flag as a Forge deployment receipt would therefore be a
false positive. The earlier Whoz test design asks for exact integrated SHA
and build-digest provenance (T19/T24); that stronger assertion must be revised
in its owning source to reflect the clarified historical-deployment gate.

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
was requested. A successful `deploy to workstream` job is the first-version
proof that the named tag was deployed. It does not prove the target still runs
that release or that a human later tested precisely those bytes; the requester
explicitly accepts those limits. Factory still lacks a trusted reader of the
GitHub job and tag facts.

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

The GitHub workflow-run API returns `inputs: null` for this run and its artifact
list is empty; its successful job status alone does not expose the release tag.
The successful deploy job's log does expose `RELEASE_TAG` and
`TARGET_ENVIRONMENT`. A bounded parser now extracts those two fields and
rejects missing or contradictory values. It must only receive the log of an
authenticated, successful `deploy to workstream` job. AgentOS now has a bounded
reader using the Case's namespace Git service account, and Factory has an HTTP
relay and plugin observer. This path has isolated and HTTP contract tests;
an actual Factory workflow pilot has not yet run.

Build #11054 skipped `backend-build` and `docker-build-and-push` while
`promote-tags` succeeded. The inspected promotion executor explicitly falls
back to `currentVersion` artifacts for unaffected services. Therefore the
integration Git SHA and RC tag do **not** by themselves identify the image
bytes that were deployed. The tag and successful Deploy run are kept as a
validation reference, with this limitation visible to the human. No
per-service artifact digest or current-target readback is required to open
this first-version human gate.

The first automatic gate check can follow this chain: current work SHA is
contained in the integration commit; the successful Build run is for that
exact commit; its RC tag resolves to that commit; a successful **Deploy** run
names that tag and the authorized target. The human request then displays the
deployed tag and waits for an authorized decision. A later integration tag
produced for other work does not revoke the request or an accepted decision.
A changed work head for this Run, or a replacement packet, does require fresh
assessment; a failed integration or Deploy job never opens the request. The
#8488 example proves the correlation is available in Git and CI, but the
The reader and observer are now implemented in the unpublished draft. Their
restart and race behavior, along with the later human-gate path, remain unproved.

#### First-version environment-independent contract

The delivery definition can have an integration-observation step after code
verification and before the two human validation branches. Its input is the
Run-bound work branch and approved packet; its output is one immutable
candidate identity or a durable wait with a technical reason. Factory only
stores the evidence and schedules/retries the step. A Forge-owned observer
uses an environment profile selected from trusted Run configuration:

| Profile | Evidence needed to open validation |
| --- | --- |
| Hosted Forge | Confirm the work commit is an ancestor of the integration commit; resolve the successful Build run's RC tag to that commit; confirm a successful Deploy job for that tag and target. Show the tag and deployment run to the human. |
| Local | Pin the worktree/repository commit and record an explicit developer attestation that the services were started and checked at a named local target. Label this as *manual local evidence*, never as a CI deployment receipt. A changed work commit or rerun starts a new candidate generation. |

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
candidate generation presented for this Run. A later unrelated integration
deployment does not invalidate them. A changed work head or packet for this
Run does; local mode likewise rechecks the pinned worktree and manual evidence.
The human's approval is recorded as a decision on the presented work, without
claiming certainty about the exact version they tested. This preserves the
same workflow shape without making Factory understand Whoz CI job names.

`GitHubForgeDeploymentReader` now rejects a Build whose tag points
elsewhere, a Deploy run or job that did not succeed, a different commit or
target, and missing or contradictory log fields. It accepts a later successful
exact deployment after an earlier failure. It uses GitHub's compare API,
successful Build and Deploy runs, the successful Deploy
job's bounded log, and the RC Git ref. It can discover recent Deploy runs by
integration branch; the AgentOS Case endpoint protects its namespace service
account, and the Factory relay supplies metadata to the Forge observer. A
missing receipt defers the integration-observation step. The tests do not yet
prove an actual Factory workflow run, restart behavior or the human gate.

The remaining implementation boundary is a durable candidate wait, including
restart and race behavior across Build, integration and code review. The
official workflow remains unpublished until the direct workflow packet
binding, authorized human replies and restart/race proofs are
in place. The earlier exact-image T19/T24 criterion must be updated rather
than silently presented as passed by this narrower contract.

Focused checks on this branch: AgentOS `:agentos-git:test` for
`GitHubForgeDeploymentReaderSpec` and `GitHubApiSpec` plus
`:agentos-service:test` for `WorkspaceControllersMvcSpec` passed. Factory
`:factory-forge-plugin:test` for the deployment and PR observers and role
guard, plus `:factory-service:test` for the draft graph and AgentOS proxy,
passed. `WorkflowRunAsyncHttpTest` passed separately for direct launch and
replay. These checks use mocked HTTP/CI responses; they do not establish a
deployed Forge workflow or an authorized human approval.
