# Projection governance (Wave 2 / Salve A)

## What changed

The Factory Cockpit now has a vanilla-ESM governed workflow projection layer for the WORKSTREAM list. It uses Workflow Projection v2 snapshots as its source, groups workflows by case/ticket, nests child workflows from relation metadata, and leaves orphaned items in an “Autres workflows” group. Groups and sub-cases render as native collapsible `<details>` sections; this change does not add a sessions/cards navigation model or a detail/timeline view.

Workflow cards show the title, workflow ID, status and lifecycle, case/thread identity, step progress, actor-lane counts, and duration. AgentOS case IDs become links only when a safe absolute HTTP(S) `agentosUrl` is available from `/api/config`; credentials, unsupported schemes, malformed bases, and missing bases are rejected. Case IDs from `coday-express` executions and thread IDs are always plain text.

Temporal layout is exposed independently through `buildBlueprintLayout`. It flattens phase-wrapped steps, classifies them into `human`, `agent`, and `code` lanes (explicit responsibility first, deterministic name hints as fallback), resolves completed/active/pending/failed states, and returns lane counts, completion rate, order ratios, and timing metadata. A renderer serializes the lanes to escaped HTML.

The projection controller loads active or removed workflow lists for a namespace, resolves and caches the AgentOS URL, and can fetch workflow timing. It subscribes to the four named projection SSE events: updates/restores trigger targeted detail refreshes, removals move items to the removed map, and purges remove items from both maps; events without an ID refresh the relevant list. Lifecycle controls call restore, remove, and purge endpoints after confirmation, and handle 409/revision/lifecycle conflicts with feedback followed by a state reload. Mounting uses one delegated container click listener, registers teardown, unsubscribes SSE listeners, closes an owned SSE client, and becomes inert after teardown.

## Files

- `factory/dashboard/js/components/temporal-lanes.mjs` — lane classification, state/timing layout, and escaped lane HTML rendering.
- `factory/dashboard/js/components/workflow-card.mjs` — card HTML, progress/timing display, lifecycle controls, and SSRF-safe AgentOS link generation.
- `factory/dashboard/js/views/projection.mjs` — grouping/hierarchy, `ProjectionController`, SSE invalidation, lifecycle actions, native `<dialog>` confirmation, rendering, mount, and teardown.
- `factory/tests/test-projection-governance.mjs` — standalone offline Node coverage for lanes, card/link safety, SSE transitions and refetch, grouping, lifecycle conflicts, dialog confirmation, and listener/SSE leak-free teardown.
- `specs/b7f4bdfc_projection_governance.md` — the added implementation plan and scope specification for this wave.

## Use and verification

The view entry point is `mountProjectionView(container, options)`. Supply an API client, namespace ID, and either an injected SSE client or an `SseClient` implementation; the controller builds the stream URL as `/api/factory/workflows/stream?namespaceId=...`. It renders into the supplied container and returns `{ controller, teardown, ready, render }`. The default confirmation path uses the cockpit dialog elements when available, while tests or integrations can inject `confirm`.

Run the focused offline suite from the repository root:

```sh
node factory/tests/test-projection-governance.mjs
```

The test file is self-contained and uses doubles for the API, DOM, SSE transport, and dialog. It exits non-zero on failure and specifically checks that mount/teardown removes delegated listeners and all four SSE subscriptions, including for an auto-created client.
