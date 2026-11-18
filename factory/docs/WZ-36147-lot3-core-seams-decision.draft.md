# WZ-36147 Lot 3 — delivery decisions and remaining seams

Status: **CE04/CE05 product behavior clarified; runtime implementation and proof pending**.
This records the requester's 2026-11-18 correction to the earlier technical
proposal. It is not permission to publish the incomplete `forge.delivery`
definition. The Ready judgment remains the first configurable ProductEngineer
step; Factory core does not interpret Jira status.

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
ticket is therefore an allowed scenario, not a missing uniqueness fence.
Still unproved: the official entry creates a parent Run rather than a direct
workflow, AgentOS provisions distinct worktrees for concurrent Runs, and a
warning can be computed without leaking inaccessible workstream details.
The first ProductEngineer step rechecks Ready and source sufficiency for each
Run independently. Internal fragments are not manually launched.

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
  independent parent Runs and worktrees; exact-request replay remains
  idempotent. A duplicate warning, if implemented, is nonblocking and
  permission-filtered. Negative ProductEngineer evidence stops only its Run;
  a human retry starts a fresh preflight attempt.
- **CE05:** manifest bytes/hash, repository, pinned commit and document digests
  agree before the gate and on resume. Approval binds that identity. Missing
  Git access or changed/missing objects pauses the Run as a technical error.
  Rejection or a new packet generation invalidates the earlier approval.
- **CE06:** standard workers receive bounded Build scopes; approved packet
  identity and current Git availability guard Build. Independent verification
  and scoped workspace deltas survive retries and late results.
- **CE07:** trusted Git, CI and deployment observations seal one candidate;
  authorized technical and functional decisions bind its same generation.
  Changed evidence invalidates both, and stale replies cannot complete.

The generic human-reply path still needs the transactional gate policy
characterized by Lot 4. The official workflow remains unpublished until the
complete delivery path has API, persistence, restart, race and integration
proof. No further product decision about duplicate blocking or mandatory
Factory blob storage is required to proceed with those tests.
