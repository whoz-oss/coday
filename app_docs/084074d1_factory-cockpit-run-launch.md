# Factory Cockpit Wave 2: governed run launch

## What changed

The Factory Cockpit now has a dedicated `/launch` view for starting governed workflow runs. The new view loads workflow definitions from `GET /api/factory/workflow-definitions`, loads agents for a supplied namespace through `GET /api/agents?namespaceId=...`, and renders a Dockyard-styled form with workflow, namespace, `FACTORY_ROOT` context, optional Jira ticket, and agent controls.

Submitting the form calls only the governed endpoint `POST /api/factory/workflows/:workflowId/run` with `{ namespaceId, ticket? }`; the legacy `/api/factory/runs` route is explicitly avoided. Successful launches navigate to `/detail` with `workflowId` and `namespaceId`. Validation, definition/agent loading failures, 400 `INVALID_RUN_REQUEST`, 409 conflicts (including returned detail codes), server failures, and network errors are surfaced in the view.

The view has a leak-safe lifecycle: it uses delegated listeners, aborts in-flight requests, clears delayed redirects, and provides an idempotent `unmount()` handle. The router also guards asynchronous mounts so a view that finishes after navigation away is torn down rather than attached to the old route.

## Files carrying the change

- `factory/dashboard/js/views/run-launch.mjs` — exports `mountRunLaunchView` (plus `mount` and default export), URL/detail-hash helpers, normalization and error helpers, rendering, data loading, submission, navigation, and teardown behavior.
- `factory/dashboard/js/app.mjs` — adds the `/launch` route and a view-mounter registry; `createRouter` accepts injectable clients/mounters, mounts the launch view, and registers its teardown while leaving routes without mounters unchanged. Bootstrap supplies the existing `ApiClient`.
- `factory/dashboard/cockpit.html` — adds the “Lancer” navigation link and `view-launch` host section containing the initial placeholder.
- `factory/tests/test-cockpit-run-launch.mjs` — offline DOM-less coverage for helpers, rendering, definitions and agents, governed POST payloads, success navigation, 400/409/500/network errors, retry behavior, listener/request/timer cleanup, and router mount/teardown.
- `specs/084074d1_cockpit_wave2_run_launch.md` — records the implementation plan, constraints, route contract, test scenarios, and verification commands for this cockpit work.

## How to verify

Run the new offline suite:

```sh
node factory/tests/test-cockpit-run-launch.mjs
```

For regression coverage, run the existing cockpit suites named by the change:

```sh
node factory/tests/test-cockpit-shell.mjs
node factory/tests/test-projection-governance.mjs
node factory/tests/test-cockpit-run-detail.mjs
```

In the cockpit, open `#/launch`, select a definition, enter a namespace, optionally enter a Jira ticket, and submit. The API call should target `/api/factory/workflows/<encoded-workflow-id>/run`; after acceptance the router should move to a detail URL carrying both `workflowId` and `namespaceId`.
