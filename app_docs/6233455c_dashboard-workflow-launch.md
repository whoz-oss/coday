# Dashboard workflow launch and ticket propagation

## What changed

The Factory Cockpit now exposes the previously orphaned workflow-launch view. `factory/dashboard/cockpit.html` adds the **Lancer** topbar link and its `view-launch` panel. `factory/dashboard/js/app.mjs` registers `/launch`, imports the launch mounter, and mounts it with the resolved namespace, cockpit navigation callback, API client, and router-owned teardown. The router supports marking this route as owned by `onMount`, preventing the generic mounter from mounting the same view twice.

`factory/dashboard/js/views/run-launch.mjs` now matches the governed workflow API contract. Submitting the form:

1. Treats the selected definition as `workflowType` and generates a fresh `wf-...` instance ID.
2. Posts the workflow metadata and dashboard execution identity to `POST /api/factory/workflows/{id}/start`.
3. Posts `{ namespaceId, ticket?, repoRoot? }` to the corresponding `/run` endpoint.
4. Navigates to the detail view with the generated workflow and namespace IDs after a successful run.

A start error identified as an existing/identity-conflict instance is tolerated and still proceeds to `/run`; other start and run failures remain visible in the launch view. Empty ticket and repository-root values are omitted from the relevant payloads. The view also registers its unmount handler with the cockpit router.

## Backend ticket support

The backend accepts an optional `ticket` in `WorkflowStartCommand` and in the workflow-start request. `WorkflowController` permits the field, resolves it from the workflow object or top-level request, passes it into the command, and mirrors it into persisted relations. Run requests accept an optional ticket and pass it to `SessionRunService.runSession`.

`SessionRunService` resolves a run-time ticket first, then falls back to the ticket stored on the workflow instance or its relations. It passes the effective value into capability execution and persists it on both the instance and projection. `CapabilityExecutionService` forwards the value to agent resolution as a brief such as `Execute this Factory session step for ticket JIRA-42.` This keeps the ticket available to downstream session/branch context without changing the deeper capability engine. `WorkflowInstance.kt` also records a start-time ticket when creating the instance.

## Tests and verification

The new `factory/dashboard/js/views/run-launch.test.mjs` uses Node’s built-in test runner with a fake DOM container and recording API client. It covers URL encoding and generated IDs, the start-then-run payload sequence (including `ticket` and `repoRoot`), omission of optional fields, identity-conflict handling, validation for missing workflow or namespace, failure handling, navigation, and teardown registration.

Backend coverage was extended in `factory-service/src/test/kotlin/io/whozoss/factory/workflow/SessionSequencerIntegrationTest.kt` for start-time and run-time ticket persistence and agent-brief propagation, and in `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowControllerHttpTest.kt` for HTTP start propagation into instance data and relations.

Useful targeted checks documented by the change are:

```text
node --test factory/dashboard/js/views/run-launch.test.mjs
pnpm nx test factory-service
```

The implementation scope is represented by the changed dashboard files, the five backend implementation files, their two Kotlin test files, and `specs/6233455c_dashboard_workflow_launch_ticket.md`, which records the launch/ticket requirements and verification plan.
