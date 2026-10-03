# Technical Handoff Plan — Cockpit v2 Frontend Fixes (3 Issues)

## Goal
Implement front-only corrections for 3 specific issues in the Cockpit v2 Angular session view (`apps/cockpit-v2`) without making any backend changes.

---

## Target Files & Scope

### Core Logic & Models
- `apps/cockpit-v2/src/app/core/models.ts`
- `apps/cockpit-v2/src/app/core/mappers.ts`

### Components
- `apps/cockpit-v2/src/app/features/session/agent-timeline.component.ts`
- `apps/cockpit-v2/src/app/features/session/agent-timeline.component.html`
- `apps/cockpit-v2/src/app/features/session/session-page.component.ts`
- `apps/cockpit-v2/src/app/features/session/session-page.component.html`

### Tests
- `apps/cockpit-v2/src/app/core/mappers.spec.ts`
- `apps/cockpit-v2/src/app/features/session/agent-timeline.component.spec.ts` (NEW)

---

## Detailed Issue Breakdown & Implementation Plan

### Issue 2: Engineer Lane (`controllerRequest`) in `mapProjectionToLanes` (`mappers.ts`)

#### Context & Specification
- Snapshot contains `controllerRequest`:
  ```ts
  controllerRequest?: {
    text?: string
    prompt?: string
    namespaceId?: string
    observedAt?: string
    actorId?: string
    source?: string
  }
  ```
- In `mapProjectionToLanes(input: unknown, timing?: unknown)`:
  1. Read `controllerRequest` from the input snapshot (`asObject(snapshot['controllerRequest'])` or direct string if legacy string format).
  2. When present (has `text`/`prompt` or `actorId`/`observedAt`):
     - SYSTEMATICALLY create or ensure an engineer human lane exists:
       `id: 'engineer'`, `kind: 'human'`, `label: 'engineer'`, `subtitle: controllerRequest.actorId || 'engineer'`, `tone: 'amber'`.
     - Assign a `request` block (`lane.request`) derived from `controllerRequest` positioned at time 0 (`startSec: 0`, `endSec: MIN_BLOCK_SEC` or derived from `observedAt`/timing if applicable), with `label: 'request'`, `description: controllerRequest.text || controllerRequest.prompt`.
     - Guard: If a step in `steps` already has `id === 'request'` or `label === 'request'` AND a request block was already created/processed by the steps loop, DO NOT duplicate the block or lane.
  3. If `controllerRequest` is absent, preserve defensive existing fallback (creating human lane if a human step exists in `steps`).

#### Proposed Changes
1. Inspect `snapshot['controllerRequest']`. Handles object or text string representation safely.
2. In `mapProjectionToLanes`:
   - Before or during step mapping, check if `controllerRequest` exists.
   - Extract `reqText = controllerRequest.text || controllerRequest.prompt` (or raw string).
   - If present, check if step array already processed a step with `id === 'request'` or `label === 'request'`.
   - If not processed from steps, build a request block for time 0 (`startSec: 0`, `endSec: MIN_BLOCK_SEC`, `status: 'done'`, `label: 'request'`, `description: reqText`).
   - Ensure the `engineer` lane exists in `lanes` map with `lane.request = requestBlock` and `subtitle = controllerRequest.actorId || 'engineer'`.

---

### Issue 3: Real-Time Gantt Chart (`agent-timeline.component.ts`)

#### Context & Specification
- `AgentTimelineComponent` needs a clock signal / timer to advance time dynamically while `status === 'running'`.
- Inputs:
  - Add optional input `status = input<RunStatus>('running')` (or `input<string>()`).
  - Add optional input `startedAt = input<string>()`.
  - Pass `[status]="s.status"` and `[startedAt]="s.startedAt"` from `session-page.component.html`.
- Internal Signals & Effects:
  - Create a local signal `ticksClock = signal<number>(Date.now())`.
  - Use `effect((onCleanup) => { ... })` or `interval(1000)` / `setInterval` when `status() === 'running'`. Update `ticksClock` every second. Clean up interval on effect destroy/cleanup or component destruction (`takeUntilDestroyed`).
  - Calculate `effectiveNowSec = computed(() => ...)`:
    - Base calculation: If `status() === 'running'`, `Math.max(nowSec(), (ticksClock() - Date.parse(startedAt())) / 1000)` or increment relative to initial `nowSec()`.
    - If `status()` is terminal (`'succeeded'`, `'failed'`), timer stops/freezes, returning static `nowSec()`.
  - Axis End & Ticks:
    - Base `axisEnd` and `ticks` calculations on `effectiveNowSec()` instead of raw `nowSec()`.
  - Dynamic Extension of Running Blocks:
    - Compute `effectiveLanes = computed(() => ...)`:
      - Iterate over input `lanes()`.
      - For each lane block (and request block if status is running), if `block.status === 'running'`, set `endSec: Math.max(block.endSec, effectiveNowSec())`.
      - Use `effectiveLanes()` in `agent-timeline.component.html`.

---

### Issue 1: Real Phase Sections (`PhaseDetail` / `buildPhaseDetail` / `session-page.component.html`)

#### Context & Specification
Refactor generation of `PhaseDetail.sections` in `models.ts` and `mappers.ts`:

1. Models update in `models.ts`:
   - Extend `PhaseDetail` or section shapes to carry structured details for rendering in HTML:
     ```ts
     export interface PhaseSectionItem {
       title: string
       subtitle?: string
       status?: string
       details?: string
       actions?: Array<{ id: string; label: string }>
     }

     export interface PhaseSection {
       label: string
       count?: number
       body?: string
       items?: PhaseSectionItem[]
       notAvailable?: boolean
     }

     export interface PhaseDetail {
       // ... existing fields ...
       sections: PhaseSection[]
     }
     ```

2. Mapper logic (`buildPhaseDetail` / `mapProjectionToSessionDetail` in `mappers.ts`):
   - **Gates**:
     - Filter interactions corresponding to active step (`stepId`).
     - Count = number of interactions for active step (or total interactions if step unspecified).
     - Populate `items` with type, prompt, status, actions.
     - Body when count === 0: `"Aucune gate d'interaction pour cette phase."`
   - **Sorties (Outputs / Evidence)**:
     - Filter evidence items corresponding to active step or `resultEvidenceId`.
     - Count = number of output evidence items.
     - Populate `items` with facts/outcome/kind/message.
     - Body when count === 0: `"Aucune sortie enregistrée pour cette phase."`
   - **Configuration de l'agent**:
     - Extract real agent info: `agentName`, `role`/`responsibility.name`, `caseId`, `attemptNumber`/`totalAttempts`.
     - If known, format body/items; if nothing known: `"Information agent non disponible"`.
   - **Prompts compilés** & **Modèle LLM résolu**:
     - No backend data exists today.
     - Mark explicitly as `notAvailable: true` and body `"Non disponible (nécessite exposition backend)"`. Add explicit code comments explaining backend gap. DO NOT display misleading `count: 0`.

3. HTML Template (`session-page.component.html`):
   - Update accordion rendering to check section items, count, `notAvailable`, and render real item details or empty/unavailable messages cleanly.

---

## Verification & Testing Strategy

### Unit Tests
1. `mappers.spec.ts`:
   - Test `controllerRequest` mapping to `engineer` lane with `request` block.
   - Test step `request` deduplication with `controllerRequest`.
   - Test `PhaseDetail` generation for Gates (with real interactions) and Sorties (with evidence items).
   - Test agent config, compiled prompts, and LLM model sections.

2. `agent-timeline.component.spec.ts` (NEW):
   - Test `effectiveNowSec` increments every second when `status === 'running'`.
   - Test `effectiveNowSec` stops when `status === 'succeeded'`.
   - Test running blocks extend `endSec` to match `effectiveNowSec`.

### Quality Checks
- `pnpm nx test cockpit-v2`
- `pnpm nx lint cockpit-v2`
- `pnpm nx build cockpit-v2`
