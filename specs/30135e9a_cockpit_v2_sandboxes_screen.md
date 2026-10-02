# Cockpit V2 Sandboxes Screen Implementation Plan

## Overview
Implement Cockpit V2 Sandboxes Screen (Task T2 of Wave 2 cockpit-v2) by porting `sandboxes-page` and `sandbox-card` components from design system mockups into `apps/cockpit-v2/src/app/features/sandboxes/` and updating route definitions in `apps/cockpit-v2/src/app/app.routes.ts`.

---

## Technical Design & Strategy

### Target Architecture & File Structure
Files to create in `apps/cockpit-v2/src/app/features/sandboxes/`:
1. `sandbox-card/sandbox-card.component.ts`
2. `sandbox-card/sandbox-card.component.html`
3. `sandbox-card/sandbox-card.component.scss`
4. `sandboxes-page.component.ts`
5. `sandboxes-page.component.html`
6. `sandboxes-page.component.scss`

File to modify:
1. `apps/cockpit-v2/src/app/app.routes.ts`

### Relative Imports Audit & Resolution

#### In `sandbox-card.component.ts`:
Target location: `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.ts`
Imports from mockups vs target paths:
- `import { Sandbox } from '../../../core/models'` -> `import { Sandbox } from '../../../core/models'` (Matches directory depth: `features/sandboxes/sandbox-card` is 3 levels below `app`).
- `import { StatusChipComponent } from '../../../shared/ui/status-chip.component'` -> `import { StatusChipComponent } from '../../../shared/ui/status-chip.component'`
- `import { MetricChipComponent } from '../../../shared/ui/metric-chip.component'` -> `import { MetricChipComponent } from '../../../shared/ui/metric-chip.component'`
- `import { PhaseBarComponent } from '../../../shared/ui/phase-bar.component'` -> `import { PhaseBarComponent } from '../../../shared/ui/phase-bar.component'`
- `import { DurationPipe, TokensPipe, UsdPipe } from '../../../shared/pipes/format.pipes'` -> `import { DurationPipe, TokensPipe, UsdPipe } from '../../../shared/pipes/format.pipes'`

#### In `sandboxes-page.component.ts`:
Target location: `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.ts`
Imports from mockups vs target paths:
- `import { FactoryStore } from '../../core/factory.store'` -> `import { FactoryStore } from '../../core/factory.store'` (Matches directory depth: `features/sandboxes` is 2 levels below `app`).
- `import { ShellState } from '../../core/shell-state'` -> `import { ShellState } from '../../core/shell-state'`
- `import { UsdPipe } from '../../shared/pipes/format.pipes'` -> `import { UsdPipe } from '../../shared/pipes/format.pipes'`
- `import { SandboxAction, SandboxCardComponent } from './sandbox-card/sandbox-card.component'` -> `import { SandboxAction, SandboxCardComponent } from './sandbox-card/sandbox-card.component'`

### Detailed Component Specifications

#### 1. `SandboxCardComponent`
- **Selector**: `sf-sandbox-card`
- **Inputs**: `sandbox = input.required<Sandbox>()`
- **Outputs**: `action = output<SandboxAction>()`
- **Type**: `export type SandboxAction = 'ask' | 'workflow' | 'conversation' | 'log' | 'commits' | 'destroy'`
- **Imports**: `RouterLink`, `MatCardModule`, `MatButtonModule`, `MatIconModule`, `StatusChipComponent`, `MetricChipComponent`, `PhaseBarComponent`, `UsdPipe`, `DurationPipe`, `TokensPipe`
- **Change Detection**: `ChangeDetectionStrategy.OnPush`
- **HTML & SCSS**: Ported directly from `scratch/cockpit-v2-mockups/design system factory/sandbox-card.component.{html,scss}`.

#### 2. `SandboxesPageComponent`
- **Selector**: `sf-sandboxes-page`
- **Form**: `form = inject(NonNullableFormBuilder).group({ project: 'coday', branch: '', roster: 'default', orchestrator: true })`
- **Constructor / Init**: Sets breadcrumbs in `ShellState`: `inject(ShellState).crumbs.set([{ label: 'Sandboxes' }])`
- **Methods**:
  - `mount()`: `console.log('monter', this.form.getRawValue())`
  - `bestOfN()`: `console.log('best-of-N', this.form.getRawValue())`
  - `onAction(name: string, action: SandboxAction)`:
    - If `action === 'destroy'`, calls `this.store.destroy(name)`
    - Else `console.log(action, name)`
- **Imports**: `ReactiveFormsModule`, `RouterLink`, `MatButtonModule`, `MatCheckboxModule`, `MatFormFieldModule`, `MatIconModule`, `MatInputModule`, `MatSelectModule`, `SandboxCardComponent`, `UsdPipe`
- **Change Detection**: `ChangeDetectionStrategy.OnPush`
- **HTML & SCSS**: Ported directly from `scratch/cockpit-v2-mockups/design system factory/sandboxes-page.component.{html,scss}`.

#### 3. Route Configuration (`app.routes.ts`)
Update route for `sandboxes`:
```typescript
{
  path: 'sandboxes',
  loadComponent: () =>
    import('./features/sandboxes/sandboxes-page.component').then((m) => m.SandboxesPageComponent),
}
```

---

## Step-by-Step Implementation Steps

1. Create directory `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/`.
2. Write `sandbox-card.component.ts`, `sandbox-card.component.html`, and `sandbox-card.component.scss`.
3. Create file `sandboxes-page.component.ts`, `sandboxes-page.component.html`, and `sandboxes-page.component.scss` in `apps/cockpit-v2/src/app/features/sandboxes/`.
4. Update `apps/cockpit-v2/src/app/app.routes.ts` to swap `SandboxesPlaceholderComponent` with `SandboxesPageComponent`.
5. Run Nx checks to verify linting, testing, and compilation:
   - `pnpm nx test cockpit-v2`
   - `pnpm nx lint cockpit-v2`
   - `pnpm nx build cockpit-v2`

---

## Verification & Validation Plan
- **Lint Check**: `pnpm nx lint cockpit-v2` must pass cleanly without TypeScript or ESLint errors.
- **Unit Tests**: `pnpm nx test cockpit-v2` must pass cleanly.
- **Build**: `pnpm nx build cockpit-v2` must complete successfully, bundling `SandboxesPageComponent` lazy chunk properly.
