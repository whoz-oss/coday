# WZ-36147 Lot 4 — core seam decision (draft)

Status: **exploratory, not approved**. This records possible generic extension
points if existing Factory gates cannot enforce the CE08/09 contracts. It is
not a request to redesign Factory gates or to approve a core change now. It builds on
`WZ-36147-lot4-governance-evidence.md` and the executable characterization
tests. CE05 packet persistence, CE07 candidate sealing and the complete Lot 3
delivery definition remain dependencies.

## Scope in the delivery Run

The developer manually launches the Run. Its first configurable ProductEngineer
step checks the ticket's current `Ready` state and source evidence; this is not
a human approval gate. Design agents then produce specifications and nonempty
implementation/verification plans. The **existing Factory human-step mechanism**
presents that complete design packet for approval before Build. After Build,
review and integration deployment, two human validation steps assess the same
integration candidate. CE08 concerns only the authority of those existing
human decisions. CE09 concerns publication of the official workflow definition
at deployment time. Neither task adds a human approval before launching a Run.

Here, *design packet* means the specifications and plans shown to the human.
*Plugin package* or *agent/skill package* means executable code or instructions
installed to execute a workflow; these are separate objects.

## Observed failure cases

1. `WorkflowServiceIntegrationTest.generic human reply has no Forge gate role authorization`
   passes today: a human step accepts `unassigned-member` as actor and persists
   a positive decision with no packet or candidate identity. The test calls
   the service directly; the HTTP boundary separately authenticates the caller
   and resolves a namespace, but has no gate-specific role check.
   The plugin cannot reject the reply inside the current transaction.
2. `WorkflowDefinitionSeederShadowingTest` passes today: an existing tenant
   `forge.delivery@1.0.0` record prevents the plugin's same-key definition from
   being seeded, for different or identical bytes. The record has no owner or
   release provenance. This is a simulated collision, not evidence that such
   a record exists in a live tenant. The same key-only seeding exists in both
   `WorkflowDefinitionSeeder.seed` and `RunService.ensureCatalogDefinitionsSeeded`.

## Decision A — plugin human checkpoint policy

This applies **after a Run has started**, when a person answers a packet,
technical or functional validation interaction. Definition registration is
Decision B; Run launch and its version pins are Decision C.

**Potential narrow extension, only if required:** add a domain-neutral SDK policy extension selected by the
*pinned* execution plugin for a workflow. Call it during checkpoint opening,
reply and final joint evaluation. The generic host supplies the authenticated
actor, trusted entitlements, interaction/step/run IDs, expected revision and a
read-only view of the plugin-owned persisted state. The policy returns either
a denial or canonical evidence facts and a proposed state update. Factory
validates those facts and commits interaction closure, decision evidence,
workflow transition and state update in one compare-and-swap transaction.
Plugin exceptions fail closed; the host never falls back to generic approval
for a workflow that declares the policy. An always-allow implementation may
exercise the wiring in an isolated test but cannot govern an active Forge
gate: it would accept the unauthorized and stale replies CE08 must reject.

Forge policy would bind the opened interaction to CE05's stored manifest
artifact ID and SHA-256 of its stored bytes, or CE07's sealed candidate ID,
generation, packet digest and observed deployment receipt. It would resolve
authorization from a trusted server source per gate, reject stale/late actors,
cancel outstanding attempts when an identity changes, and require both
independent positive decisions on the current candidate. The final joint
must re-read observed deployment and blocking findings in the same guarded
operation. `ForgeHumanAuthority` is the pure rule draft; it is not currently
connected to this extension or persistence.

**Alternative considered:** a Forge-only approval endpoint. The existing
generic reply endpoint would still accept a direct POST unless it gained an
owner guard, so this alternative does not remove the bypass. Core fields for
`packetArtifactId`, `candidateGeneration` or Forge roles would couple Factory
to this workflow and are not recommended.

**Open design detail for CE05/07:** identify the one authoritative persisted
record and transaction boundary shared by parent Run state and human
interactions. If they cannot be updated atomically in the existing store,
define an explicit fenced decision journal and recovery protocol before
implementing this policy. Do not infer approval from a selector's in-memory
state or chat transcript.

## Decision B — official catalog publication at deployment

**Recommend:** introduce a generic, immutable owner/provenance record for each
catalog definition: owner kind/id, repository revision, package locator and
digest, definition hash and publication timestamp. A plugin contribution
declares its reserved workflow type(s) and release metadata. Both API
authoring and both seeding paths consult the same server-side owner registry.
Registration, seed and collision checks must use one atomic identity claim per
organization/type/version; launch and list/detail must fail closed or visibly
mark collisions until they are reconciled. No matching hash can turn a tenant
record into an official publication.

Keep three identities distinct: (1) a plugin version installed with a Factory
deployment, (2) the plugin/definition version activated for an organization,
and (3) the exact version accepted by an individual Run. Installation must
not be treated as implicit activation for every organization if rollout is
later scoped by organization. The first official delivery rollout may use one
activation policy, but the catalog model should leave room for organization A
to use a new plugin version while organization B stays on its prior one.
The current PF4J deployment and catalog have not been shown to support
concurrent plugin versions; this is a design constraint, not an implemented
capability or an additional CE09 PASS claim.

At deployment/startup, finding an **already official** record with the same
owner, version, canonical definition hash and release provenance is a normal
idempotent restart: leave it unchanged. A differing hash for that official
identity is a release conflict and must fail closed. The owner/provenance check
is necessary because identical bytes alone cannot distinguish a prior tenant
copy from the plugin's earlier official publication.

For a pre-existing same-key tenant record, block *new* official launches and
record an explicit conflict. Preserve the old bytes and old Run pins for
forensic review; choose a new official version or migrate with a reviewed
record-level procedure. Never silently overwrite or delete the tenant record.
Expose owner/provenance in list/detail so the cockpit can render read-only
from server metadata. Reserve the type before publication so upload/register
cannot race the seed. Publication occurs only through a reviewed plugin/repo
release, after the delivery definition contains all required gates and paths.

**Alternative considered:** keep the `forge.*` prefix and compare hashes during
seeding. Prefixes do not cover the existing legacy Forge type or establish
record ownership; equal bytes still lack provenance. The startup seed is
best-effort and RunService has a second seeding path. This alternative leaves
same-key shadowing and race windows.

## Decision C — Run version coherence

**C1, already partly implemented:** a Run stores the workflow definition
version and canonical hash it accepted. A parent Run also stores its selector
version and fragment definition hashes. The selector is plugin code that
chooses the next fragment from the Run's accepted results. Factory refuses
resume if the required selector version or fragment hash no longer matches.
This catches the direct inconsistency between stored Run state and a changed
definition or selector. A deployment of a new workflow version should leave
old Runs on their old definition identity and hash.
An organization's later activation of another plugin version must not rewrite
these per-Run pins.

**C2, additional AD-7 requirement to design later:** the same definition can still behave
differently if its plugin binary or the selected agent/skill instructions
change. A *locator* is an immutable address of one such package; a *digest*
is its content hash. Record the exact package locators/digests used by a Run
and verify them on resume. This is distinct from the workflow definition's
own version/hash. Its concrete storage and resolution mechanism depends on
BD02's agent/skill packaging and needs a focused follow-up design; do not add
generic core fields speculatively. Missing old packages must stop recovery
with an inspectable reason, never silently substitute newer instructions.

## Acceptance for the core change

- A direct generic human-reply POST by an unauthorized actor, stale packet or
  stale candidate is rejected with no evidence or transition; authorized
  decisions survive process restart and remain bound to stored identities.
- Replacing a candidate races with two final replies under CAS; old attempts
  lose and neither old positive can complete the new joint.
- Existing same-key tenant records block official publication/new launches,
  even when hashes match; concurrent API registration and seeding cannot win
  against a reserved plugin identity.
- Restarting with the same already-official release is idempotent and performs
  no definition update; a changed hash at the same official version fails.
- List/detail and cockpit show immutable owner and release provenance. API/UI
  editing and deletion are rejected for plugin-owned definitions.
- Existing Runs retain their definition, selector, plugin and agent/skill pins
  after a new release; unavailable pinned packages stop recovery safely.

**Next decision point:** once CE05/07 provide concrete persisted identities,
first test whether the existing human steps and plugin selector can enforce
the required binding and routing without changing Factory core. Only a
demonstrated remaining bypass should lead to a reviewed, narrow generic
extension. Preserve C1's existing pins; design C2 after BD02 fixes the
agent/skill package identity. No core implementation is authorized by this
exploratory draft.
