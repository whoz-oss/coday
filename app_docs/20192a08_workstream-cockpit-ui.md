# Workstream Cockpit UI scaffold (Phase 11 prep)

Angular-only scaffold of the Workstream Cockpit in `apps/client`. All seven views compile and
render against an injectable **mock data service** whose fixtures match the Phase 0 DTO contracts
in `app_docs/workstream_agent_cartography_and_contracts.md` (§5 capability matrix, §6 DTO schemas).
No real HTTP is performed — every mock method carries a `// TODO(Phase 6):` marker naming the real
`/api/factory/**` endpoint it will later call. Nothing outside `apps/client/` (plus the build spec
in `specs/`) was touched; no Kotlin backend, no migrations, no pipelines.

## Entry points

- **Routes** (`apps/client/src/app/app.routes.ts`): lazy `loadComponent` routes at
  `/workstream` and `/project/:projectName/workstream` (the project-scoped one behind
  `projectStateGuard`), both rendering `WorkstreamCockpitComponent`.
- **Sidenav** (`sidenav.component.html` / `.ts`): a "Workstream" section entry (icon
  `account_tree`) visible for all users when a project is selected, disabled with a
  "(No project)" label otherwise; `openWorkstream()` navigates to the project-scoped route.

## Data layer

- `apps/client/src/app/core/models/workstream.model.ts` — the DTO contracts, mirroring Phase 0 §6
  field-for-field: `WorkstreamDto`, `WorkflowSummaryDto` / `WorkflowListDto`, `WorkflowDetailDto`
  (`state`, `steps[{stepId,status,revision}]`, `blockers`), `DurableAgentAttemptDto`
  (alias `StepAttemptDto`) with the 9-value `AttemptStatus` enum, `WorkflowBlockerDto` with the
  6-value `BlockerCode` enum, `HumanActionRequiredDto` (alias `InteractionDto`),
  `PlanChangeProposalDto` / `PlanChangeOperationDto` (`add_step | remove_step | reorder_step |
  change_responsibility`), and `ControllerHistoryDto`. The file documents that it models the
  **unwrapped** `data` payload of the Factory `{ data: ... } / { error: ... }` envelope (§2.3, §7).
  Two deliberate extras, both flagged as *not* part of the Phase 0 contract:
  - `ControllerHistoryDto` is a minimal inferred shape (the doc defines no concrete DTO) — to be
    confirmed in Phase 6.
  - `StepLane = 'human' | 'agent' | 'code'` is view-model metadata for the lanes view, currently
    served from mock lane assignments.
- `apps/client/src/app/core/services/workstream-mock.service.ts` — `WorkstreamMockService`
  (`providedIn: 'root'`), returning `of(...)` observables so swapping to `HttpClient` is mechanical.
  Read methods map to Phase 0 tools (`getWorkstream`, `listWorkflows`, `getWorkflow`,
  `getStepAttempts`, `getBlockers`, `getRequiredHumanActions`, `getPlanChangeProposals`,
  `getControllerHistory`, `getStepLanes`); command stubs (`requestAgentRetry`,
  `respondToInteraction`, `decidePlanChange`) return canned acknowledgements. Fixtures cover three
  mock workflows (`wf-101` running with a human checkpoint and an indeterminate attempt, `wf-102`
  waiting_human, `wf-103` blocked with a failed retryable attempt) and export `MOCK_AS_OF` as the
  freshness anchor.

## Views (`apps/client/src/app/components/workstream-cockpit/`)

Container `WorkstreamCockpitComponent` (signals + `computed`, `ChangeDetectionStrategy.Eager`)
loads the workstream, preloads details/actions per workflow, auto-selects the first workflow, and
fans data out to seven presentational panels in a responsive grid:

1. `summary-view/` — active workflows with progress bars, per-workflow blocker badges, and a
   pending-human-actions count; selecting a row drives the other panels.
2. `agent-link-tile/` — placeholder tile for the Workstream Agent conversation; the button is
   intentionally disabled with a "Phase 6" tooltip (thread not wired yet).
3. `workflow-detail/` — Human / Agent / Code lanes of steps with status badges and a
   revision/state freshness header; step selection feeds the attempts view.
4. `step-attempts/` — durable attempt facts (agent, case, status, evidence id, failure code,
   revision) with a **Retry** button enabled only for `failed` / `indeterminate` attempts
   (§5 matrix: `request_agent_retry` is control-plane/human; nothing can be freely launched from
   the browser).
5. `human-interactions/` — open checkpoints with prompt, resumption revision, and response buttons
   rendered **strictly** from the DTO `actions` field, emitted upward as `InteractionResponse`.
6. `plan-changes/` — proposals with op list, reason/impact note, and Approve/Reject buttons that
   only emit decision intents (`propose_plan_change` applies nothing by itself, §6.2).
7. `controller-history/` — timeline of controller case transitions, visually distinct from worker
   execution steps (§1.2).

`workstream-badges.ts` centralizes the visual-distinction rule as pure functions returning
`ws-badge ws-badge--<tone>` classes: `waiting_human` / `WAITING_HUMAN_INTERACTION` → amber,
`blocked` / `failed` → red, `indeterminate` / `UNKNOWN_RUNTIME` → purple-grey, success → green,
in-flight → blue-grey. The cockpit header always shows a freshness badge (`rev N`, "mock data",
`as of` timestamp).

Action intents (`retry`, `respond`, `decide`) bubble to the container, which forwards them to the
mock command stubs and logs the acknowledgement — no state mutation yet.

## Verification

- `apps/client/src/app/components/workstream-cockpit/workstream-cockpit.component.spec.ts` —
  Jest tests: component creation, mock workflows load + first workflow auto-selected, freshness
  badge renders `rev` / "mock data", step selection loads the matching attempts.
- Run: `pnpm nx test client` (spec included), plus `pnpm nx build client` / `pnpm nx lint client`.
- Manual: `pnpm web`, select a project, click **Workstream** in the sidenav (or open
  `/workstream` directly).
- `specs/20192a08_workstream-cockpit-ui.md` is the build spec that drove this change.
