# WZ-36147 Lot 3 — delivery decisions and remaining seams

Status: **CE04–CE07 product behavior clarified; draft implementation present, full runtime proof pending**.
This records the requester's 2026-11-18 correction to the earlier technical
proposal. It is not permission to publish the incomplete `forge.delivery`
definition. The Ready judgment remains the first configurable ProductEngineer
step; Factory core does not interpret Jira status.

**Scope update, 2026-11-19:** automatic GitHub PR and Deploy proof is deferred.
The first version reads the Case's committed Git packet, asks a person to
confirm functional testing, and ends at ready to merge after both human
approvals of the same candidate. The older CI and PR design below is
historical, not the current delivery contract.

**Later correction, 2026-11-18:** delivery must be one `forge.delivery`
workflow execution launched from the cockpit's Run button. The parent-Run
entry and fragment chain discussed below are superseded for this delivery;
they remain descriptions of pre-existing Factory capability and historical
tests. `start_parent_run` has been removed from this PR. CE04 concurrency,
CE05 Git packet identity and the human gate requirements remain in force,
but must be proved through the direct `start_workflow` path.

The requester approved a narrow domain-neutral external-evidence observation
extension on 2026-11-18. It lets the Forge plugin read a pinned Git packet
outside the finalization transaction. The direct workflow path can now pass a
locally accessible root Case worktree to that observer and retain its facts as
workflow evidence. Promotion of the approved packet identity to durable gate
state, remote-host Git reads, the complete human gate, Git recheck on later
resume/Build and the observed CI/deployment candidate remain pending. This
approval does not publish the draft delivery definition.

**Implementation update, 2026-11-18:** the remote Case Git reader, the
dedicated ForgeDeveloper/ForgeVerifier agent definitions, the fixed Build,
verification, review and integration-observation draft steps, and focused
GitHub deployment/PR readers now exist on the Lot 3 PR branches. The preceding
paragraph records the earlier decision point; its pending list is historical.
An opt-in generic checkpoint hook now binds the packet interaction and reply
to its exact Git identity. It also binds technical and functional replies to
the same work candidate, with the successful deployment receipt on the
functional side. The final joint checks both audited decisions. No full
runtime approval or recovery proof exists, so the workflow remains unpublished.

**Human authority clarification, 2026-11-18:** in the first pilot, any
authenticated person with access to the Factory Run may approve the packet or
the technical and functional validations. One person may take several such
decisions. Record the authenticated actor, decision and server timestamp in
the Run audit. Gate-specific roles and workstream permission governance are
deferred; the exact packet/candidate binding and stale-reply rejection remain
required.

## CE04 — concurrent deliveries are allowed

Two different delivery Runs may target the same ticket, including the same
workflow definition, at the same time. Each has its own identity and isolated
worktree. A repeated request with the **same** request identity replays its Run;
a new request identity creates a new Run. The ticket is not a uniqueness key.
The launch surface may show a nonblocking warning with existing Run/workstream
links when the caller has access. A warning cannot be a launch prerequisite:
the user may deliberately compare approaches or retry after an unproductive
Case. No Jira status transition starts a delivery Run automatically.

The existing integration characterization with two active parent Runs on one
ticket is a historical test of a different launch path. A newer direct-launch
test proves two different workflow identities can start for the same ticket.
Still unproved: each such Forge delivery gains its own AgentOS Case and
worktree, and an optional warning respects the caller's access. The first
ProductEngineer step rechecks Ready and source sufficiency for each instance
independently.

## CE05 — pin the Git packet; do not require a blob copy for Forge

The design agents write the packet to the Whoz repository. Before the human
gate, they publish a reachable commit and one canonical `packet-manifest.v1`
identifying that repository, commit, paths, applicability decisions, sizes and
SHA-256 digests. Factory persists the manifest identity and the human decision
against that exact Git revision. Before Build, review and any resume, the
trusted runtime must re-read the pinned Git objects and verify the expected
content and provenance. A moved branch is irrelevant; an unavailable or
mismatched commit puts the Forge Run in a nonterminal technical pause with a
visible reason; operations can resume it after Git recovers and the evidence
is reverified. A stored blob must not let a Forge Run continue while its
required source Git repository is unavailable.

The current Factory `ArtifactStore` is a Factory-owned abstraction, separate
from AgentOS Case/Namespace exchanges. Its Neo4j nodes store metadata, while
payload bytes live in object storage (`in-memory` by default, optionally
S3/MinIO). Its default retention window is 90 days. Neither that default nor
the in-memory backend proves durable packet retention. Forge does not need a
new plugin-facing blob publication port for the Git-backed packet. A future
workflow without Git may motivate a different artifact backend or a blob
publication path; that is outside this delivery requirement.

The prior Whoz architecture spine AD-2/AD-3/AD-4 says conflicting active
delivery requests stop and approved document bytes are copied to Factory.
Those clauses conflict with the clarified product behavior and need an
explicit architecture update in their owning source. This document does not
silently rewrite that source. The minimum manifest/decision record and the
trusted Git-read boundary still require a concrete design and tests.

## Other seams and required proof

- **CE04:** two different requests for the same ticket and definition create
  independent workflow Runs and worktrees; exact-request replay remains
  idempotent. A duplicate warning, if implemented, is nonblocking and
  permission-filtered. Negative ProductEngineer evidence stops only its Run;
  a human retry starts a fresh preflight attempt.
- **CE05:** manifest bytes/hash, repository, pinned commit and document digests
  agree before the gate and on resume. Approval binds that identity. Missing
  Git access or changed/missing objects pauses the Run as a technical error.
  Rejection or a new packet generation invalidates the earlier approval.
- **CE06:** one fixed Build phase in the delivery workflow needs a narrowly
  scoped coordinator agent to dispatch the ordered, bounded plan items to
  Backend/Frontend agents and report each item's evidence. The existing agents
  accept one item at a time, and the legacy ForgeController owns a different
  full lifecycle, so neither can receive the whole plan unchanged. These
  items are not dynamically created workflow steps. Approved packet identity
  and current Git availability must guard Build. Independent verification
  follows as another fixed phase. The coordinator definitions and receipt
  validators exist in the draft; delegated execution, retry and correction
  still need runtime proof.
- **CE07:** after local code review, the technical and functional human
  validations can open independently. The functional request asks the person
  to confirm that they tested the presented work candidate; Factory has no
  automatic deployment proof in this version. Both decisions bind to the same
  packet and work commit. A changed candidate needs fresh decisions; the
  final outcome is ready to merge, without a merge or Jira transition.

The opt-in human-reply policy is now implemented for the draft delivery
workflow. It binds each answer to the packet or work candidate presented,
records the authenticated actor and server time, and refuses a stale answer.
The official workflow remains unpublished until the complete delivery path
has API, persistence, restart, race and integration proof. No further product
decision about duplicate blocking or mandatory Factory blob storage is
required to proceed with those tests.
