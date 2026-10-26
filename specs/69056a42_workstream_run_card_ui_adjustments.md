# Plan: Workstreams UI Adjustments in apps/factory-cockpit

## Overview
Perform strictly visual UI adjustments on the Workstreams view within `apps/factory-cockpit`:
1. Highlight the workstream title in `WorkstreamCardComponent` so that `ws.title` is the dominant visual element.
2. Remove redundant title/workflow link and run ID code element inside the internal summary block of `RunCardComponent`.

No changes to data logic, models, services, mappers, or `namespaceId` grouping.

---

## Target Files

- `apps/factory-cockpit/src/app/features/workstreams/workstream-card/workstream-card.component.html`
- `apps/factory-cockpit/src/app/features/workstreams/workstream-card/workstream-card.component.scss`
- `apps/factory-cockpit/src/app/features/workstreams/workstream-card/workstream-card.component.spec.ts`
- `apps/factory-cockpit/src/app/features/workstreams/run-card/run-card.component.html`
- `apps/factory-cockpit/src/app/features/workstreams/run-card/run-card.component.scss`
- `apps/factory-cockpit/src/app/features/workstreams/run-card/run-card.component.spec.ts`

---

## Detailed Step-by-Step Instructions

### Task 1: WorkstreamCard Header Typography & Hierarchy

#### 1.1 Update `workstream-card.component.scss`
- Modify `.title` selector styles:
  - Increase `font-size` from `18px` to `24px`.
  - Set `font-weight: 800` (or `900`).
  - Set `letter-spacing: -0.02em`.
  - Ensure `color: var(--sf-text-primary)` or `var(--sf-text)` for strong contrast.
- Ensure metadata (`.namespace-id`, `.count`, `.cost`) remains present as secondary visual elements:
  - `.namespace-id`: Keep muted styling (`font-size: 12px`, `color: var(--sf-text-muted)`).
  - `.count`: Muted text (`font-size: 13px`, `color: var(--sf-text-muted)`).
  - `.cost`: Keep metric formatting (`font-size: 15px`, `font-weight: 700`).

#### 1.2 Verify `workstream-card.component.html` & `workstream-card.component.spec.ts`
- Ensure structure in `.workstream-head` remains:
  `<h2 class="title">{{ ws.title }}</h2>` followed by namespaceId badge, count, cost.
- Run `workstream-card.component.spec.ts` to ensure assertions on title text, namespaceId, count, and cost pass.

---

### Task 2: RunCard De-duplication of Title and ID in Internal Summary

#### 2.1 Update `run-card.component.html`
In the `<section class="run">` internal summary block:
- Remove `<a class="sf-workflow" [routerLink]="['/sessions', r.id]">{{ summary.workflow || r.id }}</a>`.
- Remove `<code class="sf-id" [title]="'Run ID: ' + r.id">{{ r.id }}</code>`.
- Retain all non-redundant elements inside `<div class="run-head">`:
  - Phase status chip (`Waiting for reply · ...` / `running · ...`).
  - `<span class="sf-spacer"></span>`.
  - Cost metric `<sf-metric icon="paid" ...>`.
  - Duration metric `<sf-metric icon="timer" ...>`.
  - Tokens metric `<sf-metric icon="link" ...>`.
- Retain the rest of `<section class="run">`:
  - `<p class="goal">{{ summary.goal }}</p>`
  - `<sf-step-dots [phases]="summary.phases" />`
- Retain the card top header (`<header class="head">`) which contains `<h3 class="name"><a [routerLink]="['/sessions', r.id]">{{ r.title }}</a></h3>`, status chip, and `workflowType` chip.

#### 2.2 Clean Up `run-card.component.scss`
- Remove unused SCSS rule `.run-head .sf-id`.

#### 2.3 Update `run-card.component.spec.ts`
- Inspect tests targeting the inner run section.
- If any spec assertion checked for `.sf-id` or duplicate `summary.workflow` within `.run-head`, adjust or remove those specific element checks while maintaining tests for identity in top header (`.name`), status, cost, metrics, and actions.

---

## Verification & Testing Commands

Run unit tests for the cockpit app:
```bash
pnpm nx test factory-cockpit
```
Ensure all 15 test suites and 268+ tests pass cleanly.
