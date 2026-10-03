# Governed workflow actions and run-cost control

## What changed

`factory-service` now provides the authoritative workflow action/blocker read model instead of leaving action availability to the Cockpit. `GET /api/factory/workflows/{workflowId}/actions` returns the standard `{ data: { allowedActions, blockers } }` envelope. The service derives the result from the persisted projection, open human interactions, durable attempts, verification evidence, and the real-cost aggregate. It emits only state- and caller-authorized actions, and includes an `expectedRevision` fence on every action.

Supported actions are `reply`, `retry`, `cancel_attempt`, `continue_cost`, and `stop_cost`. Blocker codes include waiting human interaction, blocked/failed steps or attempts, paused real cost, failed verification, and unknown runtime. Human `reply` actions require an authorized human caller; the blocker remains visible when the caller cannot reply.

Two workflow-scoped cost-control endpoints were added:

- `POST /api/factory/workflows/{workflowId}/cost/continue`
- `POST /api/factory/workflows/{workflowId}/cost/stop`

They resolve case IDs from the persisted workflow controller execution and durable attempts, deduplicate them, optionally honor a requested case only if it belongs to the workflow, and forward the trusted external identity to AgentOS. Continue accepts an optional `expectedThreshold`; both commands accept an optional `expectedRevision` for workflow revision fencing. Unsupported request fields are rejected. Namespace and identity are resolved through the trusted caller path, with the controller's existing namespace resolution behavior.

AgentOS proxy support now forwards to `/api/cases/{caseId}/run-cost/continue` and `/api/cases/{caseId}/run-cost/stop`. Disabled or unavailable usage tracking is represented as a structured 503 (`SERVICE_UNAVAILABLE`, `Usage tracking is disabled`) rather than an unhandled 500. The run-cost DTO also carries paused case IDs so cost actions can target the paused cases.

## Main implementation locations

- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowActionsModels.kt` defines action/blocker constants and DTOs, including the cost-control request shape.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt` resolves trusted workflow case IDs, aggregates paused-cost case information, computes governed actions/blockers, applies revision fencing, and performs the cost-control relay.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt` exposes the actions and cost endpoints using `resolveWorkflowCaller` and response envelopes.
- `factory-service/src/main/kotlin/io/whozoss/factory/proxy/AgentOsProxyClient.kt` and `HttpAgentOsProxyClient.kt` define and implement the AgentOS cost commands and 503 degradation exception.
- `factory-service/src/main/kotlin/io/whozoss/factory/proxy/RunCostDto.kt` adds `pausedCaseIds`.

## Verification

The added unit coverage in `WorkflowActionsServiceTest.kt` checks interaction authorization, blocked-step retry, active-attempt cancellation, terminal-attempt blockers, paused-cost actions, expected revisions, empty completed state, case resolution, stale revisions, disabled/unreachable AgentOS, and no-case errors. `WorkflowActionsHttpTest.kt` checks the enveloped HTTP read model, unknown-workflow handling, and clean cost-control responses. `AgentOsProxyMockTest.kt` checks threshold and identity forwarding, stop forwarding, 503 handling, and non-503 proxy failures.

Run the factory-service test suite from its module directory:

```bash
cd factory-service
./gradlew test
```

The implementation and test scope are also summarized in `specs/a2ac55da_governed_actions_cost_proxy.md`.
