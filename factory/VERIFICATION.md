# Code-step workspace binding and build verification (#1684)

## Workspace contract

With `factory.workspace.enabled=true` (default), **every code step**, including
standalone ticket-delivery sessions, requires a persisted READY/BUSY environment.
The owner is the parent Run ID for fragments, otherwise the workflow ID. Missing,
failed, unavailable or inconsistent environments fail the step; there is no
namespace-parent, submission-root or ancestor-directory discovery fallback.

`factory.workspace.enabled=false` is an explicit operator opt-out, not a repair.
It keeps the submitted root and records `workspaceProvenance=workspace-disabled`
and `workspaceChecksEnabled=false`. Source-level test compositions may omit the
resolver; production injects it through Spring.

Before each ready code step, Factory rereads the workflow rootCaseId and validates
the environment's namespace and owning case, exact local Git root and readable
HEAD. If baseCommit exists, HEAD must descend from it, not equal it. Ordinary
verification requires an attached branch. `prepare-work-branch` may inspect a
detached HEAD before preparation. HEAD/branch drift during verification rejects
the proof even when the command exits zero. This is not an immutable snapshot
of uncommitted files or a guarantee against concurrent filesystem replacement.

### Branch expectations: deliberately limited scope

The provisioning-time branch is an observation, often null/detached or stale,
not an authoritative policy. Factory looks for internal successful
`branch-preparation` evidence matching environmentId, with source
`factory-control-plane`, in the **owner and current fragment only**. It does
**not search earlier fragments**. Without a reliable expectation in that scope,
it requires an attached branch and reports
`branchExpectationSource=unavailable-attached-only`; it does not guess a branch
from agent prose. No cross-fragment evidence plumbing is introduced here.

The generic workflow evidence HTTP endpoint rejects `branch-preparation` with
`FACTORY_ONLY_EVIDENCE`, as it does oracle-result and human-decision. Internal
branch preparation writes directly to the repository. This change prevents new
HTTP forgeries; it does not retrospectively authenticate pre-upgrade records.
Before relying on old branch-preparation evidence, operators must establish its
provenance; do not treat an upgraded service as an audit of historical records.

### Standalone adoption: case parent is not environment owner

In `CapabilityExecutionService.resolveCaseFamily`, the standalone entry agent
reserves rootCaseId and runs in that case (`caseId=rootCaseId`, attempt
`parentCaseId=null`). Subsequent agent attempts have `parentCaseId=rootCaseId`.
By contrast, **WorkEnvironment.parentCaseId identifies the owning family root**,
not the entry attempt's null parent. `WorkEnvironmentWorkspaceStateAdapter`
projects that field as workspace caseId. Thus checking it against the refreshed
workflow rootCaseId is intentional; it must not be relaxed to accept null.

Standalone workflows containing a code step now adopt the AgentOS root workspace
in the sequencer **before claimStep changes an agent step to RUNNING**, and
before registering their first new durable agent attempt. `resolveCaseFamily`
reserves the root identity first; `StandaloneWorkspaceProvisioningService` persists
an empty provisioning intent owned by workflowId, then uses the same exact-root
HTTP creation (`worktree=true`, authenticated `X-External-User-Id`) and shared
`RootCaseWorkspaceAdoption` reader as parent Runs. Standalone REQUESTED/PREPARING
uses a single probe outside transactions; the durable continuation owns backoff.
Parent Runs keep their existing bounded polling. READY must expose an equipped, locally accessible absolute
path, exact Git root and readable HEAD. Its initial baseline must precede HEAD.
There is no GitProvisioner call, virtual path, second worktree, setup retry or
namespace-directory discovery. AgentOS children inherit the root through their
existing parentCaseId contract. Attempts capture the adopted environment before
dispatch, use physical-worktree occupancy and validate AgentOS path identity;
code resolves the same persisted root. Recovery acquires the same owner-fenced
occupation for a pending adopted attempt, and the existing local branch provider
recognizes this environment type without allocating another workspace.

The environment is attributed exclusively to `controllerRequest.actorId`,
persisted by authenticated HTTP/create-run boundaries even when request prose
is absent, and by the trusted parent-run driver for each fragment. Execution
body `actorId` is overwritten at compatibility start and is never a proxy
credential. Dispatch, prechecks and recovery do not fall back to body-derived
`controllerExecution.actorId` or historical environment `createdBy`. Missing
trusted attribution on old records requires explicit operator handling, not
implicit migration. Parent-run launch attribution still comes from its existing
authenticated launch intent; successor fragments inherit the persisted request
actor (or the authenticated escalation answerer).
Conflicting owners/namespaces/root cases/providers, multiple environments and
BUSY/decommissioned environments are refused without replacement. Only this
service's own PROVISIONING intent can be completed. READY re-adoption checks
physical identity and ancestry without updating its revision, branch observation
or initial baseline. Explicit AgentOS `FAILED` and proven permanent invariants
(relative/invalid root, changed physical root, owner identity mismatch, explicit
negative Git ancestry) are terminal, not wrapped as transient. Terminal refusal
marks only our unchanged pending intent (`provisioningFailure`), never a foreign,
READY, BUSY or decommissioned environment. Git ancestry exit 1 proves divergence;
Git inspection errors (other exits) remain transient. Terminal refusal is recorded through claim +
step failure; continuation never reexecutes the failed step or retries setup.
Recognized network/5xx/408/429 errors, missing workspace reads, PREPARING and
READY Git inspection unavailability retain intent and leave the step READY,
without an attempt or message dispatch. The common runSession boundary ensures
a durable continuation; only these transient pre-claim failures receive bounded
backoff (5 seconds to 5 minutes; 90 minutes from the first transient failure).
Other 4xx refusals persist terminal adoption failure through the same helper as
explicit FAILED/invariant refusals. Programming errors are not transient retries.
Interrupted waits retain intent and restore the interrupt flag; the worker releases
its claim by token CAS before returning, without waiting for lease expiry.
A crash during adoption or after its READY commit leaves a pre-claim step that
a new sequencer can safely prepare again, with the same root/environment and
baseline. Already registered/started attempts skip adoption and keep their
existing observation-only replay rules. Parent Runs retain their existing authenticated launch-intent/replay
semantics; only their workspace read/adopt loop is shared.

The explicit workspace-disabled opt-out remains. Agent/human-only workflows do
not request a workspace. Code-only workflows still require an externally
established real environment; this change does not invent a root agent for them.
Legacy polling is refused for standalone agent/code workflows while workspace
checks are enabled because it cannot honor this case-family/fencing contract.
With both adapter and workspace checks explicitly disabled, the mere presence
of the provisioning bean no longer prevents the legacy opt-out. Parent-run
polling remains refused. Disabled adapter + enabled workspace is rejected by
preparation before root reservation, environment insertion or AgentOS creation.
Recovery checks the authenticated actor of a pending workspace attempt before
claiming it; a legacy parent without that actor stays PENDING, not CLAIMING.
Old nonterminal attempts are not silently rebound,
and failed or foreign existing environments require explicit operator handling.
The deployment still requires AgentOS and Factory to see the same local
filesystem; remote path translation is not implemented.

## Whitelist and trust boundary

Only the exact target root's `factory/verification.json` resolves names. The
schema remains unchanged (`command`, optional positive `timeoutMs`), with no
`preflightCommand`. Manifest/name refusals follow the existing durable step
failure path. There is no added global executable precheck before progress is
loaded or on replay of terminal steps. Namespace prechecks that predate this
patch are not redesigned.

The target-repository whitelist is the existing trust model, **not an anti-agent
sandbox**. Manifest, script, Nx/package configuration and build code are mutable
repository content. This patch does not add signed provenance or partial guards
on selected files. Existing bounded log capture, including possible loss of the
tail of long output, is unchanged.

## Real build and prerequisites

From the **exact target repository root**, the whitelisted `build` runs:

```sh
node scripts/factory-build.mjs
```

The script checks prerequisites before scheduling work: Node >=24, pnpm 10,
JDK 25 selected through JAVA_HOME/PATH, .git, nx.json, pnpm-lock.yaml, installed
local Nx and both Gradle wrappers. Install dependencies beforehand in the target
workspace with `pnpm install --frozen-lockfile`. The Factory process must see
these tools and filesystem paths. Gradle dependencies/network, additional task
requirements, memory and ports remain prerequisites of their respective tasks;
the initial checks are not exhaustive. They run **at the code step**, not before
expensive agent turns.

After resolving nonempty build/lint/test project sets through Nx, it runs:

```sh
pnpm nx run-many --targets=build,lint,test,check-openapi-spec --parallel=2 --skip-nx-cache
```

This uses existing Nx targets and local Gradle inference plugins for TypeScript,
Angular, AgentOS and Factory compilation/checks. It verifies the **full monorepo**
in the absence of an authoritative affected baseline, not an affected-scope
optimization. Pre-existing unrelated failures may block it. It does not imply
browser E2E or manual web/terminal validation. The manifest's 1,800,000 ms budget
is not a promise that a cold full build will finish in 30 minutes.

## Validation commands and current limitation

**No shell execution tool was available during implementation/review follow-up.**
No compilation, targeted test, integration test or real build was executed.
Static review is not a passing validation; test execution remains a release
blocker. From repository root, with JDK 25 and Node/pnpm dependencies installed:

```sh
(cd factory && ./gradlew :factory-verification-core:test --tests '*VerificationManifestTest' --tests '*VerificationDeclarationTest')
(cd factory && ./gradlew :factory-service:test --tests '*RunWorktreeResolverTest' --tests '*CapabilityResolverTest' --tests '*CodeVerificationFactsTest' --tests '*SessionSequencerIntegrationTest' --tests '*RunDrivingLoopIntegrationTest' --tests '*WorkflowControllerHttpTest' --tests '*StandaloneWorkspaceProvisioningIntegrationTest' --tests '*ParentRunWorkspaceProvisioningIntegrationTest' --tests '*ParentRunWorkspaceValidationIntegrationTest' --tests '*ParentRunCodeOccupancyIntegrationTest' --tests '*AgentOsProxyMockTest' --tests '*DefaultAgentOsExecutionAdapterTest' --tests '*BridgeRecoveryWorkerWorkspacePreflightTest' --tests '*AgentStepQuestionOccupancyTransferIntegrationTest')
pnpm nx test scripts
node scripts/factory-build.mjs
```

Integration tests use the established embedded Neo4j fixture. Added/adapted
regressions cover strict standalone refusal, explicit opt-out, detached then
attached branch, reliable branch expectations, baseline ancestry, root case
refresh/mismatch, durable preparation/manifest failure and terminal replay,
HEAD before/after and timeout, linked worktree refs, declarative whitelist
resolution without execution/ancestor search, real build declaration and
prerequisite failure, and generic HTTP evidence forgery rejection. Follow-up
regressions add a standalone entry agent + child + code sequence against a real
linked Git worktree, real durable result submission and occupancy release, exact
code cwd and terminal replay; asynchronous adoption, absent/preparing/failed
workspace, durable failed-intent replay, owner/root mismatch, missing actor,
BUSY/decommissioned refusal and baseline preservation after an agent commit.
Only the remote AgentOS worker/HTTP boundary is doubled in these integration
fixtures. Parent-run adoption/occupancy suites remain required non-regressions.
Reviewer follow-up tests additionally exercise a crash after real READY adoption
but before claim through a restarted sequencer, transient absence/PREPARING/
network/READY-error/interruption then explicit continuation, interrupted polling,
body actor spoofing with and without trusted request attribution, authenticated
compatibility start without prose, parent-fragment actor persistence and the
explicit disabled-workspace polling opt-out. Mock HTTP verifies exact root
`worktree=true`, replay without another creation, Default adapter adoption of that
root, child-only parent inheritance and foreign root refusal. Final-review
regressions refresh recovery/answer-transfer actors, assert actorless recovery
leaves the attempt unchanged across two sweeps, reject polling before resource
creation and persist permanent adoption refusals through sequencer replay.
Direct adoption cases distinguish negative ancestry from Git unavailability and
reject owner drift / physical-root replacement without overwriting those records.

The compatibility start route already accepts caller-provided `relations.runId`.
For agent dispatch, that relation alone cannot prove case-family ownership:
preflight checks environment owner/namespace against the persisted workflow root,
and the authenticated request actor is not taken from the body. A forged relation
with no matching persisted root is refused before case creation/start (regression
added). However this is NOT general parent-fragment authorization: the existing
code-only path selects an owner from the relation and skips case comparison when
workflow rootCaseId is null. Thus a same-scope/namespace code-only workflow can
potentially select another Run's environment; code execution has no AgentOS actor
ACL check. This pre-existing compatibility-path risk is reported, not repaired
by general relation/membership plumbing in this follow-up. No claim of complete
cross-workflow authorization is made.
None of these new tests has been executed in this tool environment. Old records
without request attribution are not backfilled; even an empty authenticated
request is now persisted and participates in new start-command identity. No
compatibility migration of historical start hashes is claimed. Continuation
handoff/ack is transactional; execution uses a short token-fenced lease claim,
not a transaction held over polling or agent work. Full turns remain on the
existing scheduler. After an ungraceful crash recovery may wait up to the fixed
two-hour lease; no heartbeat framework is added. Agent RUNNING states/attempt
fences remain authoritative: continuation replay is not an automatic agent retry.
A distinct durable source arriving during a claim requests a new wake cycle;
ack/defer consumes that wake and clears the prior attempts/deadline/diagnostic.
Repeated delivery of the current source does not reset its retry budget.
Result handoff uses persisted submission repoRoot/ticket where available; legacy
results carry explicit deferred namespace-resolution provenance. HTTP resolution
runs only after the durable claim, outside the handoff transaction, with bounded
recognized-transport retries and no silent '.' fallback. Explicit resubmission
after dead uses the existing submission path; terminal/removed step guards are
not relaxed. Previously dispatched events without a durable continuation are not
retroactively repaired.

## Deployment order (no incident repair claimed)

1. Run the commands above and resolve actual failures before release.
2. After approval, deploy the corrected Factory backend and deliver the
   manifest/script in the intended target repository revision. Do not copy them
   into a namespace parent or unrelated external checkout.
3. Prepare tools/dependencies in the exact run workspace. Correlate owner,
   environment, rootCaseId, canonical path, actual branch/HEAD and any historical
   branch expectation. New standalone agent/code workflows now request and adopt
   their root workspace automatically; this does not migrate old failed intents
   or repair a foreign environment by ignoring the ownership guard.
4. Only after the deployed service and actual workspace are verified, consider
   an explicitly approved retry through existing human-governed mechanisms.

This patch does not prove the incident's effective path, run definition/version
or root cause. It changes no run, definition or workflow and performs no retry.
Deploying code alone neither prepares an old workspace nor repairs a failed run.
