# Workflow `realCost` metrics: aggregated run cost from AgentOS

`GET /api/factory/workflows/{workflowId}/metrics` now exposes an additive
`realCost` block holding the **real, aggregated run cost** of the workflow,
fetched live from AgentOS (`GET /api/cases/{caseId}/run-cost`) and summed over
the workflow's distinct case ids. The existing `/metrics` contract
(`timing`, `retries`, `evidenceCount`, `interactionCount`, …) is untouched —
only one key is appended.

## Response shape

The map returned by `WorkflowService.metrics(...)` gains:

```json
"realCost": {
  "cost": 12.5,               // KNOWN lower bound (Double)
  "unknownCostCount": 2,      // turns AgentOS could not price — real cost is strictly higher
  "liveTokens": 1234,
  "paused": false,            // true if ANY case is paused
  "active": true,             // true if ANY case is active
  "runCostThreshold": 50.0    // max non-null threshold across cases, else null
}
```

`unknownCostCount` is surfaced verbatim and is never folded into `cost` as 0.
When AgentOS is disabled, unreachable, returns errors, or the workflow has no
case at all, the block degrades to
`{cost: 0.0, unknownCostCount: 0, liveTokens: 0, paused: false, active: false, runCostThreshold: null}`
and `/metrics` still answers 200.

## How it works

**Case id resolution (trusted boundary only).** Case ids come exclusively from
persisted state, never from client input:

1. `instance["controllerExecution"]["caseId"]` of the persisted workflow
   instance (the controller/root case), when non-blank.
2. The `caseId` of every persisted `DurableAgentAttempt` of the workflow, via
   the new `DurableAgentAttemptService.findByWorkflow(scope, namespaceId, workflowId)`.

Ids are deduped by exact value into a `LinkedHashSet`. AgentOS's `run-cost`
already rolls up the whole descendant tree of each case (delegations included),
so each distinct persisted case id is queried exactly once — a case and one of
its own sub-cases are never summed twice.

**Per-case read.** `AgentOsProxyClient.getRunCost(caseId, externalUserId = null)`
calls `/api/cases/{caseId}/run-cost`. Unlike the other proxy relays it **never
throws**: a 404 (`AgentOsNotFound`) or any other failure
(`AgentOsUnavailableException`, transport error) degrades to `null` with a warn
log, so the metrics endpoint is bulletproof. The JSON reply is parsed loosely
into a local `RunCostDto` (factory-service deliberately does **not** depend on
`agentos-sdk`), with `as? Number`/`as? Boolean` casts so a missing or renamed
field degrades instead of crashing. The metrics call passes
`externalUserId = null`, the same convention as `resolveRepoRoot(namespaceId, null)`.

**Aggregation.** In `WorkflowService.aggregateRealCost(...)` (private helper,
plus a private `RealCostAggregate` data class with `toJson()`): sums `cost`,
`unknownCostCount`, `liveTokens`; OR-combines `paused` and `active`; keeps the
greatest non-null `runCostThreshold` via a `maxThreshold(a, b)` helper. The
whole aggregation is wrapped in a defensive `try/catch` returning the zero
aggregate on any unexpected error. No new constructor parameters were added —
the existing nullable `agentOsProxyClient` and `durableAgentAttemptService` are
reused. No controller change was needed: `realCost` appears automatically in
the `{ "data": ... }` envelope.

## Files

Main code (factory-service):

- `proxy/RunCostDto.kt` — **new** local DTO mirroring the AgentOS contract
  (subset: `caseId, cost, unknownCostCount, runCostThreshold, paused, active, liveTokens`).
- `proxy/AgentOsProxyClient.kt` — new `getRunCost(caseId, externalUserId): RunCostDto?` on the interface.
- `proxy/HttpAgentOsProxyClient.kt` — implementation; never throws, adds a
  `KotlinLogging` logger for degradation warnings.
- `workflow/service/WorkflowService.kt` — `realCost` key in `metrics(...)`,
  `aggregateRealCost(...)`, `RealCostAggregate`, `maxThreshold(...)`.
- `agentattempt/persistence/DurableAgentAttemptRepository.kt` — new
  `findByWorkflow(scope, namespaceId, workflowId)` port method.
- `agentattempt/persistence/Neo4jDurableAgentAttemptRepository.kt` — its implementation.
- `agentattempt/persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt` —
  new Cypher query `findByWorkflowId(...)` matching all attempts of a workflow.
- `agentattempt/service/DurableAgentAttemptService.kt` — `@Transactional(readOnly = true) findByWorkflow(...)`.

Tests:

- `workflow/WorkflowRealCostMetricsTest.kt` — **new** MockK unit suite covering:
  single known root case (and presence of all pre-existing metrics keys);
  partially unknown cost summed verbatim across two distinct cases (paused OR,
  threshold max); an attempt case id equal to the controller case id queried
  only once; unreachable AgentOS / null results degrading to the zero block;
  a workflow with no case (proxy never called); a disabled proxy.
- `proxy/AgentOsProxyMockTest.kt` — `MockRestServiceServer` tests: parsing of
  the run-cost JSON into `RunCostDto`; degradation to `null` on 404 and on 500
  without throwing (in contrast to `fetchNamespace`, which throws on 500).
- `proxy/AgentOsAgentTurnTest.kt` — the inline fake `AgentOsProxyClient` gains
  the `getRunCost` override.

Also added: `specs/0d8266a6_workflow_real_cost_metrics.md` — the plan/spec this
change was built from (recon findings, contract shape, verification checklist).

## Verify

Run the factory-service tests:

```
cd factory-service && ./gradlew test --tests '*WorkflowRealCostMetrics*' --tests '*AgentOsProxyMock*'
```

Or hit the endpoint against a live stack:
`GET /api/factory/workflows/{workflowId}/metrics` and check the `realCost`
block under `data`. With AgentOS stopped, the endpoint must still return 200
with the zero block.

No cockpit v2 (Angular) files were touched.
