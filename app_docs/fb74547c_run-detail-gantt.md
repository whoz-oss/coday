# Factory Cockpit Wave 2: run-detail Gantt

## What changed

Wave 2 (salve A) adds a standalone Vanilla ESM run-detail view backed exclusively by Governed Projection v2 workflow, timing, evidence, and metrics DTOs. It does not use legacy `/api/runs` or `/api/factory/runs` data, JSONL streams, or line/done SSE events.

The new view normalizes projection steps by stable ID, combines them with timing data, maps statuses to readable bar states, assigns code/human work to the orchestrator lane and agent work to the observed agent lane, and renders a deterministic, collision-aware Gantt timeline. It also provides a selected-step inspector with classified facts, recorded evidence, and best-effort Jira or AgentOS case enrichment. Dynamic values in component markup are escaped; Gantt bars use data attributes for delegated selection rather than inline handlers.

The view lifecycle is explicit: `mount(container, { workflowId, namespaceId, apiClient, sseClient })` performs the initial workflow load plus timing/evidence/metrics requests, subscribes to `workflow-projection-updated`, debounces matching refreshes, and returns an idempotent `unmount()` that removes the click listener, SSE subscription, refresh timer, aborts requests, and clears state.

## Files

- `factory/dashboard/js/components/facts.mjs` defines `FACT_GROUPS`, `FLAGS`, `emptySuccess`, escaping/formatting helpers, flag collection, and classified fact rendering. Unknown facts remain visible under “Autres”; successful steps with zero executed tasks receive the empty-success warning.
- `factory/dashboard/js/components/gantt.mjs` contains projection-v2 normalization and the ported timeline algorithms: `laneOf`, `timelineBounds`, `buildGlobalTimeline`, `layoutLaneBars`, `renderBar`, `buildTicks`, `laneSubtitle`, and Gantt rendering. Statuses such as `completed`, `failed`, and `waiting_human` are mapped to bar states, with timestamps and timing durations used where available.
- `factory/dashboard/js/components/phase-panel.mjs` renders the selected-step header, status/flag chips, facts, step-filtered evidence, and optional external narrative columns. Case events come from `/api/cases/:caseId/events`; Jira content comes from `/api/factory/jira/:ticketId`. Missing IDs or failed enrichment produce an explicit degraded notice instead of breaking the panel.
- `factory/dashboard/js/views/run-detail.mjs` owns the detail-view fetch, rendering, delegated bar selection, live refresh subscription, enrichment loading, and teardown contract.
- `factory/tests/test-cockpit-run-detail.mjs` is an offline Node test suite covering fact classification, empty-success behavior, v2 normalization, lane/timeline/layout determinism, escaped hostile values, panel/enrichment degradation, mount/selection, SSE refresh filtering, and leak-free unmount behavior.
- `specs/fb74547c_run_detail_gantt_view.md` records the implementation perimeter, component contracts, test scenarios, and verification commands.

## Verification

Run the focused offline suite and the existing cockpit-shell regression test:

```bash
node factory/tests/test-cockpit-run-detail.mjs
node factory/tests/test-cockpit-shell.mjs
```

The focused suite uses mocked DTOs and element-like clients, so it requires no network or external dependency. The run-detail module expects the existing API and SSE client interfaces supplied by the cockpit shell; shell files themselves are consumed unchanged.
