# Implementation Plan: Cockpit V2 Session / Timeline Screen (Wave 2 - Task T3)

## Context
Task T3 of Wave 2 requires implementing the Session / Timeline screen in `apps/cockpit-v2` by faithfully porting the mockup implementation located in `scratch/cockpit-v2-mockups/design system factory/`.

The implementation involves creating 3 standalone Angular components (`SessionPageComponent`, `AgentTimelineComponent`, `EventLogComponent`) under `apps/cockpit-v2/src/app/features/session/`, along with their template and SCSS files, updating import paths to match the target architecture (`src/app/core/`, `src/app/shared/`), and wiring `SessionPageComponent` into `apps/cockpit-v2/src/app/app.routes.ts` in place of `SessionPlaceholderComponent`.

## Target Files & Scope

### Files to Create in `apps/cockpit-v2/src/app/features/session/`:
1. `session-page.component.ts`
2. `session-page.component.html`
3. `session-page.component.scss`
4. `agent-timeline.component.ts` (selector: `sf-agent-timeline`)
5. `agent-timeline.component.html`
6. `agent-timeline.component.scss`
7. `event-log.component.ts` (selector: `sf-event-log`)
8. `event-log.component.html`
9. `event-log.component.scss`

### File to Modify:
- `apps/cockpit-v2/src/app/app.routes.ts`: Replace lazy import of `SessionPlaceholderComponent` with `SessionPageComponent` for route `sessions/:runId`.

### Scope Restrictions:
- DO NOT touch: `core/`, `shared/`, `layout/`, other `features/` (`placeholders/`), backend Kotlin (`agentos/`), `apps/client`, `apps/server`, `factory/dashboard`.
- ONLY modify files in `apps/cockpit-v2/src/app/features/session/*` and `apps/cockpit-v2/src/app/app.routes.ts`.

---

## Technical Details & Adaptations

### 1. `AgentTimelineComponent` (`apps/cockpit-v2/src/app/features/session/agent-timeline.component.ts`)
- **Selector**: `sf-agent-timeline`
- **Imports**:
  - Angular Material: `MatIconModule`, `MatTooltipModule`
  - Core models: `TimelineBlock`, `TimelineLane` from `../../core/models`
  - Shared pipes: `DurationPipe` from `../../shared/pipes/format.pipes`
- **HTML & SCSS**: Port from `scratch/cockpit-v2-mockups/design system factory/agent-timeline.component.html` and `.scss`.
- **Logic**: Standalone component, `changeDetection: ChangeDetectionStrategy.OnPush`. Inputs: `lanes` (required `TimelineLane[]`), `nowSec` (required `number`), `tickEverySec` (default `120`), `selected` (optional `string`). Output: `blockSelect` (`output<TimelineBlock>()`). Computes axis end, ticks array, percentage positioning (`pct`), width percentage (`widthPct`), and actor icons (`iconFor`).

### 2. `EventLogComponent` (`apps/cockpit-v2/src/app/features/session/event-log.component.ts`)
- **Selector**: `sf-event-log`
- **Imports**:
  - CDK: `ScrollingModule`, `CdkVirtualScrollViewport` from `@angular/cdk/scrolling`
  - Angular Material: `MatButtonToggleModule`, `MatCheckboxModule`, `MatIconModule`
  - Core models: `RunEvent`, `RunEventType` from `../../core/models`
- **HTML & SCSS**: Port from `scratch/cockpit-v2-mockups/design system factory/event-log.component.html` and `.scss`.
- **Logic**: Standalone component, `changeDetection: ChangeDetectionStrategy.OnPush`. Inputs: `events` (required `RunEvent[]`), `running` (default `false`), `activityLabel` (default `''`). Signals: `filter` ('all' | RunEventType), `follow` (boolean). Live scroll effect in constructor using `viewChild(CdkVirtualScrollViewport)` and `queueMicrotask`.

### 3. `SessionPageComponent` (`apps/cockpit-v2/src/app/features/session/session-page.component.ts`)
- **Selector**: `sf-session-page`
- **Imports**:
  - Common: `DatePipe`
  - Angular Material: `MatButtonModule`, `MatExpansionModule`, `MatIconModule`
  - Core: `FactoryStore` from `../../core/factory.store`, `ShellState` from `../../core/shell-state`, `TimelineBlock` from `../../core/models`
  - Shared UI: `StatusChipComponent` from `../../shared/ui/status-chip.component`, `MetricChipComponent` from `../../shared/ui/metric-chip.component`
  - Shared Pipes: `UsdPipe`, `DurationPipe`, `TokensPipe` from `../../shared/pipes/format.pipes`
  - Feature components: `AgentTimelineComponent` from `./agent-timeline.component`, `EventLogComponent` from `./event-log.component`
- **HTML & SCSS**: Port from `scratch/cockpit-v2-mockups/design system factory/session-page.component.html` and `.scss`.
- **Logic**: Standalone component, `changeDetection: ChangeDetectionStrategy.OnPush`. Input: `runId = input.required<string>()`. Injected: `FactoryStore`, `ShellState`. Computed: `session = computed(() => this.store.session(this.runId()))`. Constructor updates breadcrumbs in `ShellState`. Signal: `selectedBlock = signal('build')`. Methods: `selectBlock(b: TimelineBlock)`, `stop()`.

### 4. `apps/cockpit-v2/src/app/app.routes.ts`
- Update route `sessions/:runId` lazy import:
  ```ts
  {
    path: 'sessions/:runId',
    loadComponent: () =>
      import('./features/session/session-page.component').then((m) => m.SessionPageComponent),
  },
  ```

---

## Step-by-Step Implementation Instructions

### Step 1: Create `AgentTimelineComponent` files
- Create `apps/cockpit-v2/src/app/features/session/agent-timeline.component.ts`
- Create `apps/cockpit-v2/src/app/features/session/agent-timeline.component.html`
- Create `apps/cockpit-v2/src/app/features/session/agent-timeline.component.scss`
- Verify correct import paths (`../../core/models`, `../../shared/pipes/format.pipes`).

### Step 2: Create `EventLogComponent` files
- Create `apps/cockpit-v2/src/app/features/session/event-log.component.ts`
- Create `apps/cockpit-v2/src/app/features/session/event-log.component.html`
- Create `apps/cockpit-v2/src/app/features/session/event-log.component.scss`
- Verify correct import paths (`../../core/models`).

### Step 3: Create `SessionPageComponent` files
- Create `apps/cockpit-v2/src/app/features/session/session-page.component.ts`
- Create `apps/cockpit-v2/src/app/features/session/session-page.component.html`
- Create `apps/cockpit-v2/src/app/features/session/session-page.component.scss`
- Verify imports for `FactoryStore`, `ShellState`, `StatusChipComponent`, `MetricChipComponent`, `AgentTimelineComponent`, `EventLogComponent`, and format pipes.

### Step 4: Modify `app.routes.ts`
- Replace `import('./features/placeholders/session-placeholder.component').then((m) => m.SessionPlaceholderComponent)` with `import('./features/session/session-page.component').then((m) => m.SessionPageComponent)`.

---

## Verification Plan

### 1. Build & Lint Checks
Execute:
```bash
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```
Ensure 0 compilation errors and 0 linting warnings/errors.

### 2. Unit Tests
Execute:
```bash
pnpm nx test cockpit-v2
```
Ensure all tests pass.

### 3. Affected Baseline Verification
Execute:
```bash
pnpm nx affected -t lint --base="$(cat /work/data/baseline)"
pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
pnpm nx affected -t build --base="$(cat /work/data/baseline)"
```
Ensure everything builds, lints, and passes tests cleanly.
