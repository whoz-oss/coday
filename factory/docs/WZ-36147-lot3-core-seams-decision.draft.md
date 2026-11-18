# WZ-36147 Lot 3 — Factory seam decision (draft)

Status: **architecture decision required before generic runtime changes**.
This is a reviewable proposal, not permission to change Factory core or to
publish `forge.delivery`. It complements the separate Lot 4 human/catalog
decision draft. The Ready judgment remains the first configurable
ProductEngineer step; no Jira rule belongs in Factory core.

## Demonstrated boundaries

| CE | Current proof | Required behavior not provided by that path |
| --- | --- | --- |
| CE04 parent launch | `ParentRunWorkspaceProvisioningIntegrationTest` passes with two active `forge.delivery` parent Runs, distinct root Case IDs and fragments for one ticket when callers choose different `runId`s. `RunService` locks/replays by `runId`. `FACTORY_WORKSTREAM.start_workflow` calls `CreateWorkflowRunService` and creates a direct workflow. | One manual parent-Run launch per active `(tenant, workstream, delivery run type, ticket)` and no direct-workflow bypass for the official type. ProductEngineer must recheck Ready in its first step after launch. |
| CE05 packet publication | `ForgePacketManifest` validates actual document bytes and returns canonical manifest bytes/hash. `ArtifactStore.putArtifact` persists tenant-scoped bytes in `factory-service`. The Forge plugin compiles only against `factory-sdk`, which has no artifact publication/open port. | Copy pinned Whoz Git bytes into Factory storage, record immutable artifact IDs/hashes/source commit on the parent Run, verify them on resume and present the stored manifest ID/hash to the gate. |
| CE06 Build guard | The plugin checks step scope, read-only steps, applicability and nonempty implementation/verification plan outputs. The draft is not published. | Route standard workers through bounded fragments and keep Build locked until the exact current packet has a positive human decision. |
| CE07 candidate | `ForgeHumanAuthority` models pure packet/candidate decisions. Generic human replies have no gate-specific authorization or packet/candidate identity check in their transactional path (Lot 4 characterization). No service-side CI/deployment receipt source is present in the inspected Factory service/SDK. | Seal a candidate from trusted Git/CI/deployment observations; re-read deployment and bind both authorized decisions to one current generation before the joint. |

## Proposed domain-neutral seams

1. **Owned parent-Run launch.** Let a plugin declare an owned parent run type and
   validate its launch context before workspace provisioning. Factory performs
   a single atomic reservation of an active key supplied by that policy, scoped
   to tenant/workstream/run type/ticket. Same-key replay returns the original
   Run; a different request for the same active ticket conflicts. The generic
   `start_workflow` operation must route owned parent types to this use case or
   reject them, while a dedicated parent launch operation can be exposed to
   ForgeSupervisor. The plugin checks ticket shape and workstream binding;
   ProductEngineer alone judges current Jira Ready/source sufficiency in the
   first workflow step. The host does not interpret Jira status.
2. **Scoped artifact publication.** Add an SDK port for a plugin to submit
   canonical bytes under a host-resolved Run/tenant scope and to reopen the
   immutable artifact by ID. The host delegates to `ArtifactStore`, returns
   ID, SHA-256 of the stored bytes and availability, and persists the accepted
   artifact reference with source Git repository/commit/tree provenance on the
   parent Run. A failed state update leaves an identifiable orphan for existing
   artifact GC; it must not make a packet approvable. On resume, missing bytes,
   changed digest or source mismatch stop before Build. This port does not
   expose `factory-service` implementation classes to the plugin.
3. **Transactional human checkpoint policy.** Use the Lot 4 draft's generic
   plugin policy at open/reply/joint in the existing human-step transaction.
   It binds the packet interaction to the stored manifest artifact ID/digest,
   checks trusted actor entitlement and generation, then records or rejects
   the decision atomically with the step transition. Later it binds both
   technical and functional decisions to the same sealed candidate and
   rechecks observed deployment. A Forge-only endpoint cannot close the
   generic reply bypass.

These are proposed interfaces and transaction boundaries, not code already in
Factory. The exact port signatures, authoritative persisted record and recovery
protocol need architecture review before implementation. In particular, a
plugin-only check after `RunService.startRun` cannot prevent a second parent
from being provisioned, and a selector's in-memory state cannot authorize a
generic human reply.

## Required proof after the decision

- CE04/T11–T12/T17: same ticket and run type permit one active parent across
  different callers/IDs and restart; an upstream readiness Run on that ticket
  remains possible; negative ProductEngineer evidence blocks design and a
  human retry starts a fresh attempt. A Jira status transition alone starts no
  Run. Internal fragments have no independent launch route.
- CE05/T07–T10: pinned Whoz commit bytes, manifest canonical stored bytes,
  artifact ID/hash and parent state agree across restart and lease transfer;
  changed/unavailable bytes fail closed. Packet rejection/change creates a new
  generation and no old approval opens Build.
- CE06/T13–T18: Backend/Frontend routing, bounded steps, scoped Git delta,
  approved commands and independent read-only verification survive duplicate,
  late and retry submissions.
- CE07/T19–T21: trusted Git, CI build and observed deployment attest one exact
  candidate; dirty/colliding or mismatched evidence cannot seal. Both human
  approvals are current, authorized and bound to one generation; replacement
  invalidates both atomically and a stale reply cannot complete the Run.

The next human input is an architecture decision on these generic seams or a
specific existing port/transaction path that provides the same guarantees.
No official definition should be seeded until the full delivery path passes
these tests.
