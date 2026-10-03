# Plan — Scaffold Angular Workstream Cockpit UI (Phase 11 prep)

## Objective
Build a **frontend-only** scaffold for the Workstream Cockpit inside `apps/client`.
All 7 views must compile and render against **mock injectable data** that matches the
Phase 0 DTO contracts in `app_docs/workstream_agent_cartography_and_contracts.md`
(§5 capability matrix, §6 DTO schemas, §7 error codes). **No real HTTP to Factory endpoints.**

## Hard boundaries (do not cross)
- Touch **only** files under `apps/client/`. Zero changes to `factory-service/`, `agentos/`,
  `libs/`, migrations, or release pipelines. (Acceptance criterion 3 + `diff_matches_claims` gate.)
- No real `HttpClient` calls to `/api/factory/**`. Mock services return in-memory data and carry
  explicit `// TODO(Phase 6):` stub markers at every method that will later be wired to HTTP.
- No worker-launch actions. The UI exposes **only** read + retry/decision actions that are derived
  from DTO fields (`actions`, blockers). This matches the §5 capability matrix (control-plane/human
  may approve/reject/retry; the browser must not freely start workers or transition workflows).

## Conventions to follow (verified in repo)
- Standalone Angular components, `ChangeDetectionStrategy` (existing code uses `.Eager`; reuse it),
  `inject()` DI, signals + `computed()`, `toSignal()`. No NgModules.
- No semicolons, single quotes, 120-char lines (eslint + prettier enforced).
- Files kebab-case; classes PascalCase; selectors `app-*`.
- Lazy routes via `loadComponent` / `loadChildren` (see `app.routes.ts`).
- Imports via relative paths within `apps/client` (the app does not use `@coday/*` for its own files;
  it imports `@whoz-oss/design-system` and Angular Material).
- Explicit return types on functions/methods.
- Jest + `jest-preset-angular` for tests (`apps/client/jest.config.ts`, setup `src/test-setup.ts`).

## Reference facts from Phase 0 doc (authoritative DTO source)
- §6.1 `get_workstream` output → `WorkstreamDto`.
- §6.1 `list_workflows` output items → `WorkflowSummaryDto`; envelope `{ items, nextCursor }`.
- §6.1 `get_workflow` output → `WorkflowDetailDto` (`state`, `workflowId`, `revision`, `workflowType`,
  `status`, `steps[{stepId,status,revision}]`, `blockers[]`). `state` enum: `absent|existing|removed|purged`.
- §6.1 `get_step_attempts` output items → `DurableAgentAttemptDto` (status enum:
  `pending|claiming|starting|running|waiting_human|succeeded|failed|indeterminate|interrupted`).
- §6.1 `get_blockers` output items → `WorkflowBlockerDto` (code enum:
  `WAITING_HUMAN_INTERACTION|STEP_BLOCKED|ATTEMPT_FAILED|REAL_COST_PAUSED|VERIFICATION_FAILED|UNKNOWN_RUNTIME`).
- §6.1 `get_required_human_actions` output items → `HumanActionRequiredDto` / `InteractionDto`
  (`interactionId`, `stepId`, `questionEventId: string|null`, `prompt`, `actions[{id:'approve'|'reject',label}]`,
  `expectedRevision`).
- §6.2 `propose_plan_change` → `PlanChangeProposalDto` (`proposalId`, `workflowId`, `status`, `summary`,
  `operations[{op:'add_step'|'remove_step'|'reorder_step'|'change_responsibility', stepId, target?}]`, `revision`).
  Status seen in doc: `pending_validation`.
- `ControllerHistoryDto`: controller **case** transitions (distinct from worker execution steps). The doc
  distinguishes `WorkflowTransitionNode` (worker steps) from controller cases (§1.2). Model a timeline of
  controller case transitions: `{ caseId, fromStatus, toStatus, at (iso), note? }` wrapped as
  `{ workflowId, revision, entries: ControllerHistoryEntryDto[] }`. Mark with a comment that the exact
  Phase 6 shape is TBD (no concrete DTO schema in Phase 0 doc — infer a minimal, clearly-labelled shape).

All envelopes in Factory are `{ data: ... }` success / `{ error: { code, message, details } }` error (§2.3, §7).
Model the mock service to return the **unwrapped data** (what a client would consume after unwrapping),
but add a comment noting the real `{ data }` / `{ error }` envelope that Phase 6 wiring must unwrap.

---

## Files to create (all under `apps/client/src/app/`)

### 1. Models — `core/models/workstream.model.ts`
Export TypeScript interfaces + union string-literal types. One file, single responsibility.

```ts
// Mirrors Phase 0 DTO contracts in
// app_docs/workstream_agent_cartography_and_contracts.md (§6). Keep in sync with Factory.

export type AttemptStatus =
  | 'pending' | 'claiming' | 'starting' | 'running' | 'waiting_human'
  | 'succeeded' | 'failed' | 'indeterminate' | 'interrupted'

export type BlockerCode =
  | 'WAITING_HUMAN_INTERACTION' | 'STEP_BLOCKED' | 'ATTEMPT_FAILED'
  | 'REAL_COST_PAUSED' | 'VERIFICATION_FAILED' | 'UNKNOWN_RUNTIME'

export type WorkflowState = 'absent' | 'existing' | 'removed' | 'purged'
export type HumanActionId = 'approve' | 'reject'
export type PlanChangeOp = 'add_step' | 'remove_step' | 'reorder_step' | 'change_responsibility'

export interface WorkstreamDto {
  workstreamId: string
  organizationId: string
  name: string
  status: string
  revision: number
}

export interface WorkflowSummaryDto {
  workflowId: string
  workflowType: string
  title: string
  status: string
  revision: number
}

export interface WorkflowStepDto {
  stepId: string
  status: string
  revision: number
}

export interface WorkflowBlockerDto {
  code: BlockerCode
  stepId: string | null
  message: string
}

export interface WorkflowDetailDto {
  state: WorkflowState
  workflowId: string
  revision: number
  workflowType: string
  status: string
  steps: WorkflowStepDto[]
  blockers: WorkflowBlockerDto[]
}

export interface DurableAgentAttemptDto {
  attemptId: string
  stepId: string
  attemptNumber: number
  agentName: string
  status: AttemptStatus
  caseId: string
  failureCode: string | null
  resultEvidenceId: string | null
  revision: number
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}
// StepAttemptDto is an alias of DurableAgentAttemptDto for the step-attempts view
export type StepAttemptDto = DurableAgentAttemptDto

export interface HumanActionDto {
  id: HumanActionId
  label: string
}

export interface HumanActionRequiredDto {
  interactionId: string
  stepId: string
  questionEventId: string | null
  prompt: string
  actions: HumanActionDto[]
  expectedRevision: number
}
export type InteractionDto = HumanActionRequiredDto

export interface PlanChangeOperationDto {
  op: PlanChangeOp
  stepId: string
  target?: string
}

export interface PlanChangeProposalDto {
  proposalId: string
  workflowId: string
  status: string // e.g. 'pending_validation'
  summary: string
  operations: PlanChangeOperationDto[]
  revision: number
}

// Controller case transitions — distinct from worker execution steps (§1.2).
// NOTE: no concrete Phase 0 DTO; minimal inferred shape — confirm in Phase 6.
export interface ControllerHistoryEntryDto {
  caseId: string
  fromStatus: string
  toStatus: string
  at: string // iso
  note?: string
}
export interface ControllerHistoryDto {
  workflowId: string
  revision: number
  entries: ControllerHistoryEntryDto[]
}

// list_workflows envelope (§6.1)
export interface WorkflowListDto {
  items: WorkflowSummaryDto[]
  nextCursor: string | null
}
```

### 2. Mock data service — `core/services/workstream-mock.service.ts`
`@Injectable({ providedIn: 'root' })`. Returns `Observable<T>` using rxjs `of(...)` so views can later
swap to real HTTP with minimal change. **Every method gets a `// TODO(Phase 6):` marker** naming the
real endpoint it will call.

Methods (named after Phase 0 read/command tools):
- `getWorkstream(workstreamId: string): Observable<WorkstreamDto>` — TODO(Phase 6): `GET /api/factory/workstreams` / tool `get_workstream`.
- `listWorkflows(workstreamId: string): Observable<WorkflowListDto>` — TODO(Phase 6): `GET /api/factory/workflows` / tool `list_workflows`.
- `getWorkflow(workflowId: string): Observable<WorkflowDetailDto>` — TODO(Phase 6): `GET /api/factory/workflows/{workflowId}`.
- `getStepAttempts(workflowId: string, stepId: string): Observable<StepAttemptDto[]>` — TODO(Phase 6): `GET /api/factory/workflows/{workflowId}/attempts`.
- `getBlockers(workflowId: string): Observable<WorkflowBlockerDto[]>` — TODO(Phase 6): `GET /api/factory/workflows/{workflowId}/actions`.
- `getRequiredHumanActions(workflowId: string): Observable<HumanActionRequiredDto[]>` — TODO(Phase 6): `GET /api/factory/workflows/{workflowId}/interactions`.
- `getPlanChangeProposals(workflowId: string): Observable<PlanChangeProposalDto[]>` — TODO(Phase 6): tool `propose_plan_change` result listing.
- `getControllerHistory(workflowId: string): Observable<ControllerHistoryDto>` — TODO(Phase 6): controller case history source (TBD).
- Command stubs (no real effect, log + return mock acknowledgement):
  - `requestAgentRetry(workflowId, stepId, expectedRevision, reasonCode): Observable<{status: string}>` — TODO(Phase 6): `POST /api/factory/workflows/{workflowId}/retries` / tool `request_agent_retry`.
  - `respondToInteraction(workflowId, interactionId, actionId: HumanActionId): Observable<{state: string}>` — TODO(Phase 6): `POST /api/factory/workflows/{workflowId}/interactions/{id}/reply`.
  - `decidePlanChange(proposalId, decision: 'approve'|'reject'): Observable<{status: string}>` — TODO(Phase 6): plan-change decision endpoint (TBD).

Seed one realistic in-memory dataset covering: 2–3 workflows, several steps with mixed statuses, at least
one blocker of each visually-distinct kind (`WAITING_HUMAN_INTERACTION`, `STEP_BLOCKED`, and one producing
an `indeterminate` attempt), attempts with `succeeded`/`failed`/`running`/`waiting_human`/`indeterminate`,
one open human interaction, one plan-change proposal, and a few controller-history entries. Keep the data
in private const fixtures at the top of the file.

### 3. Shared status-badge helper — `components/workstream-cockpit/workstream-badges.ts`
Small pure helpers (no component) mapping statuses/blocker codes to CSS class + label, enforcing the
visual-distinction rule:
- `waiting_human` → warning / **amber**
- `blocked` / `STEP_BLOCKED` / `ATTEMPT_FAILED` → error / **red**
- `indeterminate` → neutral / **purple-grey**
- `succeeded` → success/green; `running`/`starting`/`claiming`/`pending` → info/blue-grey.
Export `attemptBadgeClass(status: AttemptStatus): string`, `blockerBadgeClass(code: BlockerCode): string`,
`blockerLabel(code: BlockerCode): string`. Used by templates via a small pipe or `[ngClass]`.

### 4. Container (smart) component — `components/workstream-cockpit/workstream-cockpit.component.{ts,html,scss}`
- Selector `app-workstream-cockpit`, standalone.
- Reads route param (`workstreamId` or `projectName`) via `inject(ActivatedRoute)`.
- Injects `WorkstreamMockService`; exposes signals via `toSignal(...)` for the workstream, workflow list,
  selected workflow detail, attempts, blockers, interactions, proposals, controller history.
- Renders a **freshness / revision badge** at the top (shows current `revision` + a "mock data" chip and a
  relative "as of" timestamp from fixtures).
- Hosts the 7 presentational child components below in a layout (header + grid/sections). It owns the
  selected-workflow / selected-step signals and passes data down via `@Input()`; children emit intent via
  `@Output()` which the container forwards to the mock service command stubs.

### 5. Seven presentational (dumb) child components under `components/workstream-cockpit/`
Each standalone, `@Input()`-driven, `ChangeDetectionStrategy.Eager`, with its own `.html`/`.scss`.
Keep them presentational; all data flows from the container.

1. `summary-view/workstream-summary.component.*` — active workflows list, a simple progress indicator
   (e.g. completed-steps/total bar), blockers list with distinct badges (amber/red/purple-grey), and a
   "pending human actions" count/list. Emits `(selectWorkflow)`.
2. `agent-link-tile/workstream-agent-link.component.*` — **placeholder** tile/button linking to the
   Workstream Agent chat/case thread. Non-functional link with a `// TODO(Phase 6):` note; render as a
   disabled-ish CTA or a `routerLink` placeholder to the existing thread route pattern (do not invent a
   backend call).
3. `workflow-detail/workflow-detail.component.*` — **lanes** representation: Human / Agent / Code lanes.
   Group steps into lanes (derive lane from step status/metadata; since mock, assign lanes in fixtures via
   a `lane?: 'human'|'agent'|'code'` optional field added to the mock step rows — keep that as a view-model
   extension, not a DTO field, so the DTO stays contract-exact). Show step statuses, current `revision`, and
   a freshness indicator. Reuse styling ideas from existing cockpit components if present (see note below).
   Emits `(selectStep)`.
4. `step-attempts/step-attempts.component.*` — attempts table for the selected step: `agentName`, `caseId`,
   environment/state (status), status badge, `resultEvidenceId`, `failureCode`, and a **Retry** button that
   is enabled only when status is `failed`/`indeterminate` and emits `(retry)` carrying
   `{ workflowId, stepId, expectedRevision }`. No free launching.
5. `human-interactions/human-interactions.component.*` — for each open interaction: the `prompt`, recipient/
   actor label, response options rendered **strictly from `actions[]`** (approve/reject buttons), and a
   resumption/state indicator. Emits `(respond)` with `{ interactionId, actionId }`.
6. `plan-changes/plan-changes.component.*` — **placeholder** proposal view: `summary`, list of `operations`
   (op + stepId + target), reason/impact text, and approve/reject decision buttons. Emits `(decide)` with
   `{ proposalId, decision }`. Mark the decision wiring as Phase 6 TODO.
7. `controller-history/controller-history.component.*` — timeline/log of controller case transitions
   (`caseId`, from→to, timestamp, note), visually distinct from worker steps (label it "Controller cases").

### 6. Routing — edit `app.routes.ts`
Add a lazy route. Preferred (project-scoped, consistent with existing routes and `projectStateGuard`):

```ts
{
  path: 'project/:projectName/workstream',
  loadComponent: () =>
    import('./components/workstream-cockpit/workstream-cockpit.component').then(
      (m) => m.WorkstreamCockpitComponent
    ),
  canActivate: [projectStateGuard],
},
```
Also add a standalone-accessible alias so the cockpit is reachable without a project (useful for the
scaffold demo and tests):
```ts
{
  path: 'workstream',
  loadComponent: () =>
    import('./components/workstream-cockpit/workstream-cockpit.component').then(
      (m) => m.WorkstreamCockpitComponent
    ),
},
```
Insert both before the catch-all `{ path: '**', redirectTo: '' }`.

### 7. Navigation entry — edit `components/sidenav/sidenav.component.{ts,html}`
Add a sidenav section "Workstream" (icon e.g. `account_tree` or `hub`) mirroring the existing
project-scoped pattern (`openSchedulers` etc.). Add method `openWorkstream(): void` that guards on
`selectedProjectName()` and `this.router.navigate(['project', projectName, 'workstream'])`, plus the
`@if (selectedProjectName()) { ... } @else { disabled }` markup block alongside Agents/Prompts/Schedulers.
This satisfies "navigation link or standalone entry exists."

### 8. Spec test — `components/workstream-cockpit/workstream-cockpit.component.spec.ts`
One lightweight Jest spec that:
- Creates the component via `TestBed` with the real `WorkstreamMockService` (no HTTP), providing
  `ActivatedRoute` stub returning a `workstreamId`/`projectName` param.
- Asserts the component instantiates and that mock signals populate (e.g. workflow list length > 0).
This gives `pnpm nx test client` a passing new test and proves mock data renders.

---

## Verification (factory runs tests; you may run build/lint locally)
1. Lint: `pnpm nx lint client` — must pass (no-semicolon/quote/line-length rules).
2. Build: `pnpm nx build client` — components compile; stay within the 1.1mb initial-bundle warning
   budget (route is lazy, so the cockpit chunk is separate — fine).
3. Test: `pnpm nx test client` — existing + new spec pass.
4. Manual sanity (optional): `pnpm web`, navigate to `/workstream` (or `/project/<name>/workstream`),
   confirm all 7 views render with mock data and the distinct badge colours appear.

## Acceptance checklist (map to task)
- [ ] All 7 views present and render mock DTO data.
- [ ] DTO interfaces match §6 contracts field-for-field (enums included).
- [ ] Mock service has `// TODO(Phase 6):` markers on every method naming the real endpoint.
- [ ] No real HTTP to `/api/factory/**`; no worker-launch action; only retry/decision actions from DTO fields.
- [ ] Freshness/revision badge on top of the cockpit.
- [ ] Distinct visual badges: `waiting_human` amber, `blocked` red, `indeterminate` purple-grey.
- [ ] Routes added + sidenav link.
- [ ] Lint/build/test green for `client`.
- [ ] Zero changes outside `apps/client/`.

## Claims declaration (for `diff_matches_claims` gate)
Declare exactly these paths (new unless marked edit):
- `apps/client/src/app/core/models/workstream.model.ts`
- `apps/client/src/app/core/services/workstream-mock.service.ts`
- `apps/client/src/app/components/workstream-cockpit/workstream-badges.ts`
- `apps/client/src/app/components/workstream-cockpit/workstream-cockpit.component.ts`
- `apps/client/src/app/components/workstream-cockpit/workstream-cockpit.component.html`
- `apps/client/src/app/components/workstream-cockpit/workstream-cockpit.component.scss`
- `apps/client/src/app/components/workstream-cockpit/workstream-cockpit.component.spec.ts`
- `apps/client/src/app/components/workstream-cockpit/summary-view/workstream-summary.component.{ts,html,scss}`
- `apps/client/src/app/components/workstream-cockpit/agent-link-tile/workstream-agent-link.component.{ts,html,scss}`
- `apps/client/src/app/components/workstream-cockpit/workflow-detail/workflow-detail.component.{ts,html,scss}`
- `apps/client/src/app/components/workstream-cockpit/step-attempts/step-attempts.component.{ts,html,scss}`
- `apps/client/src/app/components/workstream-cockpit/human-interactions/human-interactions.component.{ts,html,scss}`
- `apps/client/src/app/components/workstream-cockpit/plan-changes/plan-changes.component.{ts,html,scss}`
- `apps/client/src/app/components/workstream-cockpit/controller-history/controller-history.component.{ts,html,scss}`
- `apps/client/src/app/app.routes.ts` (edit)
- `apps/client/src/app/components/sidenav/sidenav.component.ts` (edit)
- `apps/client/src/app/components/sidenav/sidenav.component.html` (edit)

## Notes
- Existing cockpit-flavoured specs live in repo `specs/` (e.g. `9f90fadc_cockpit-static-lanes.md`,
  `c6d71268_factory-cockpit-timeline.md`) — these are Kotlin/backend-era specs and are **out of scope**;
  do not reuse their backend wiring. This task is purely the Angular `apps/client` scaffold.
- Keep each component single-responsibility (CLAUDE.md). Prefer functional/signal patterns.
- Commit with conventional messages, e.g. `feat(client): scaffold workstream cockpit UI with mock data`.
