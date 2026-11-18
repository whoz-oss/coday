# WZ-36147 Lot 4 — governance seam evidence

Status: **partial contract only; CE08 and CE09 remain blocked**. No official
delivery definition has been published. This note records the current code
paths and candidate extension points to review only if the completed CE05/07
path cannot use existing Factory gates. It does not claim T08/T09/T14/T20–T23 PASS.

## CE08: durable human authority

`WorkflowController.replyInteraction` authenticates the caller and resolves a
tenant/workstream from its namespace. `WorkflowService.replyInteractionTransactional`
then checks interaction revision and waiting step status, appends a
`human-decision` evidence item with actor ID, applies the transition and closes
the interaction in one transaction. This is useful durable CAS/restart behavior.
The reply payload contains only `namespaceId`, `expectedRevision`, `actionId`
and optional text. Neither the open interaction nor the decision evidence
contains a packet artifact ID/digest or candidate ID/generation. The code does
not consult a gate-specific authorization policy or re-read observed deployment
before accepting the reply. The service-level test accepts an actor ID without
a supplied Forge role; HTTP authentication is a separate boundary. The transition policy
checks human evidence provenance, not Forge artifact or candidate identity.

`ForgeHumanAuthority` in the plugin now models the intended *pure* rules over
trusted, immutable identities. Its focused test proves unauthorized, duplicate,
rejected and stale decisions are refused; replacement clears decisions; Build
requires the current packet approval; completion requires both independent
positive decisions on one current candidate. It deliberately does not claim a
live server fence. CE05 must produce the persisted Git repository, commit and
canonical `packet-manifest.v1` SHA-256; CE07 must bind the integrated work
commit, successfully deployed tag and candidate generation. Their APIs must supply these identities
from storage, never from agent prose or a client-declared `result.outputs`.

**Current gap:** the service-level characterization test opens a human
checkpoint and submits `approve` with the current interaction revision and an
actor ID that has no supplied Forge gate role. The generic service records the
decision without checking a packet/candidate identity. This test does not
exercise the HTTP authorization boundary. A candidate replacement cannot
invalidate already recorded evidence through this path alone.

**Potential generic core decision, if the completed CE05/07 path still leaves
a bypass:** add a plugin-owned human checkpoint policy resolved from the pinned
execution plugin, called inside the same reply CAS transaction before evidence
append and transition. The policy would read the
current persisted packet/candidate generation and trusted actor entitlements,
bind them to interaction/evidence, and refuse stale, late or unauthorized
responses. A replacement caused by this Run's changed work head or packet must
atomically cancel both open final interactions and invalidate their decisions;
an unrelated later integration tag does not. The joint must reject blocking
findings, without asserting which exact version a human tested. Do not place Forge gate names,
Jira status or domain authorization rules in generic endpoints. The policy's
persisted state and transaction ownership need an explicit design review before
wiring the pure rules. These pure rules alone cannot enforce the Forge Build or
completion gates.

## CE09: official definition publication

`WorkflowDefinitionController` rejects user register/upload/delete for
`forge.*`; the cockpit detail page hides Edit/Delete and displays plugin
publication as read-only for that prefix. Generic definition runs pin version
and hash. Parent Runs persist selector version and flow definition hashes and
refuse a mismatched selector/flow on resume. These are useful partial fences.
They do not pin an exact agent/skill package locator/digest. The current legacy
`forge-controller-v1-searcher` type is outside the `forge.*` UI/API prefix
policy, so prefix matching is not an ownership registry.

`SessionDefinitionCatalog` loads plugin flows but supplies no ownership,
release provenance or repository digest metadata. `WorkflowDefinitionSeeder`
skips any existing `workflowType@version` regardless of its content or source.
`RunService.ensureCatalogDefinitionsSeeded` repeats that key-only behavior at
parent Run launch, so protecting startup seeding alone is insufficient.
`SessionDefinitionSeedRunner` treats seed failure as best effort. Thus a tenant
definition created before plugin release can shadow the official same-version
definition, even if its bytes happen to match. The API may later reject writes
to `forge.*`, but the stored tenant record remains authoritative for lookup and
launch. No official `forge.delivery` definition exists yet: Lot 3 now has an
unpublished one-step preflight draft and a pure packet-manifest validator, but
neither a complete delivery workflow nor persisted packet approval. The draft
cannot be seeded as a safe delivery workflow.

**Simulated collision, not an observed tenant:** register `forge.delivery@V`
before `V` becomes plugin-managed, then install a plugin that contributes
`forge.delivery@V`.
Seeder sees the key and leaves the tenant record in place. The list/detail API
cannot distinguish it from the official package or show release provenance.

**Potential generic core decision, after the official publication path is
designed:** introduce an owner/provenance
contract for catalog contributions and persisted definition records. Reserve
plugin-owned type identities before publication, reject all user writes to
those identities, and fail closed on pre-existing same-key tenant records even
when the hash matches. Publication must be a reviewed repository/plugin release
with immutable source revision, plugin/package locator and digest and canonical
definition digest. A migration or quarantine procedure for existing collisions
must be explicit; silently overwriting could reinterpret pinned Runs. List and
detail should expose owner/provenance so UI can use server metadata rather than
a name prefix. Old Runs must retain their accepted definition/selector and
agent/skill package pins; missing packages stop resume.

## Required proof after CE05/07 and the core decisions

| TD case | Required live proof |
| --- | --- |
| T08/T09 | API decision and Build entry reference the pinned Git repository, commit and canonical manifest SHA-256; reject/change creates a new generation and leaves no old approval on Build/review/candidate. |
| T14 | API denies unauthorized, chat-derived, stale and late replies; audit records trusted actor, gate, outcome and exact identity across restart. |
| T20/T21 | Parallel technical/functional replies on the presented deployed-tag reference; one alone cannot complete. A changed work head or packet for this Run invalidates both, while an unrelated later integration tag does not. Authorization, stale replies and races are exercised without claiming exact tested-version proof. |
| T22 | API/UI deny edit/delete and same-key shadow, including identical bytes; catalog shows repository release provenance and digest. |
| T23 | Old/new Runs retain their exact definition, selector and agent/skill package locators and digests; missing package blocks restart. |

The pure Forge tests cover only the policy algebra. The API, restart, version,
UI and integration-receipt tests above remain pending until the contract is
connected to durable server paths and a complete official definition exists.

## Focused verification (2026-11-18)

- `:factory-forge-plugin:test --tests ForgeHumanAuthorityTest`: passed (5 tests).
- `:factory-service:test --tests WorkflowDefinitionAuthoringAuthTest --tests WorkflowExecutionPluginResolutionTest`: passed; these verify existing API authoring and execution-plugin behavior, not CE09 publication.
- Cockpit workflow page/detail Jest suites did not start: local `node_modules` is missing `@csstools/css-calc`, required by `jsdom`. This is a dependency-installation issue; no UI test result is claimed.
- `git diff --check`: passed.

Additional characterization tests: `WorkflowDefinitionSeederShadowingTest`
passed and proves same-key shadowing for different and identical bytes;
`WorkflowServiceIntegrationTest.generic human reply has no Forge gate role
authorization` passed against the integration store and proves the generic
service accepts a positive reply without a Forge role or identity binding.
The proposed core extension and acceptance tests are in
`WZ-36147-lot4-core-seams-decision.draft.md`.
