# Plan — Expose aggregated real run-cost on `GET /api/factory/workflows/{workflowId}/metrics`

## Goal

Add an **additive** `realCost` block to the map returned by
`WorkflowService.metrics(...)`, computed by calling AgentOS
`GET /api/cases/{caseId}/run-cost` for every distinct **root** case of the
workflow and aggregating the results. The existing `/metrics` HTTP contract
(`timing`, `retries`, `evidenceCount`, `interactionCount`, …) must stay
byte-for-byte unchanged. AgentOS being unreachable, disabled, or returning
errors must NEVER break `/metrics` — it degrades to a zero/empty cost block.
A cost that AgentOS cannot price (`unknownCostCount > 0`) must be surfaced
explicitly and never silently coerced to `cost = 0`.

Do **not** touch anything in cockpit v2 (Angular).

---

## Key facts discovered during recon (read these before coding)

- **factory-service does NOT depend on `agentos-sdk`** (grep of
  `factory-service/build.gradle.kts` + sources found no `whozoss.agentos.sdk`
  import, no `agentos-sdk` coordinate). → We MUST define a **local**
  `RunCostDto` inside factory-service. Do not add the SDK dependency.
- The AgentOS contract (authoritative shape to mirror) is
  `agentos/agentos-sdk/.../usageRecord/RunCostDto.kt`:
  ```kotlin
  data class RunCostDto(
      val caseId: UUID,
      val since: Instant,            // NOT nullable in the real contract
      val cost: Double,              // KNOWN lower bound
      val unknownCostCount: Long,    // # of un-priced turns
      val runCostThreshold: Double?,
      val paused: Boolean,
      val active: Boolean,
      val liveTokens: Long,
      val pausedCases: List<PausedCostDto> = emptyList(),
  )
  ```
  The AgentOS endpoint is `@RequestMapping("/api/cases/{caseId}/run-cost")`,
  `@GetMapping` → returns a `RunCostDto` JSON, `caseId` is a `UUID`, protected by
  `Case READ`. On the Factory side we read it as a loosely-typed JSON map (see
  the existing `HttpAgentOsProxyClient.relay(...)` pattern) and map it into our
  local DTO, so a missing/renamed field degrades gracefully.
- **The proxy already exists**: `AgentOsProxyClient` (interface) +
  `HttpAgentOsProxyClient` (impl, uses Spring `RestClient`, relays
  `X-External-User-Id`, maps 404 → an internal `AgentOsNotFound`, any other
  failure → `AgentOsUnavailableException`). Wired in `ProxyConfiguration`
  (`agentOsProxyClient` bean) from `ProxyProperties.agentosUrl`.
- **`WorkflowService` already receives `agentOsProxyClient: AgentOsProxyClient? = null`**
  as a constructor param (nullable, used today only by `resolveRepoRoot`). We
  reuse it — no new constructor wiring needed for the proxy.
- **`WorkflowService` already receives `durableAgentAttemptService: DurableAgentAttemptService? = null`**
  — reuse it to collect the per-step attempt `caseId`s.
- **Attempt → caseId**: `DurableAgentAttempt.caseId: String` (persisted on
  `DurableAgentAttemptNode`, a UUID string produced by
  `CapabilityExecutionService.stableCaseId(workflowId, stepId)` =
  `UUID.nameUUIDFromBytes("$workflowId#$stepId")`). There is currently **no**
  repository method that lists *all* attempts of a workflow — `findByAttemptId`
  needs an `attemptId`, `find` needs `stepId`+`attemptId`. We must add one
  (see step 2).
- **Controller case**: the workflow instance JSON carries
  `instance["controllerExecution"]` (a `Map`) whose `caseId` field (when
  present) is the controller/root case. See `WorkflowModels.WorkflowExecution`
  (`caseId: String?`) and `WorkflowInstance.kt` line 62. Also surfaced in
  `publicInstanceSnapshot`/`publicSnapshot` under `controllerExecution`.
- **`metrics(...)` is `@Transactional(readOnly = true)`** and already loads
  `timing`, `retries`, evidence and interactions. It does NOT currently load the
  instance record directly — we will fetch it via `repository.findInstance`
  (used elsewhere in the class) / `findProjection` to read `controllerExecution`.
- **Identity / trust boundary**: `metrics` receives `scope: TenantScope` and
  `namespaceId` already resolved from `TrustContext` by
  `resolveWorkflowCaller(...)` in `WorkflowHttp.kt`. `WorkflowCaller.caseId`
  (the trusted context case) is NOT passed into `metrics` today and must NOT be
  trusted as a workflow case — resolve caseIds ONLY from persisted
  instance/attempts. The external user id for the AgentOS call: the controller
  does not pass one to `metrics` today; pass `null` (the proxy already handles a
  null `X-External-User-Id`, exactly like `resolveRepoRoot(namespaceId, null)`
  does). Do not invent a user id from client input.

---

## Changes

### 1. Local `RunCostDto` in factory-service

**New file:** `factory-service/src/main/kotlin/io/whozoss/factory/proxy/RunCostDto.kt`

A faithful, Spring-agnostic local copy of the AgentOS contract (do NOT import
the SDK). Use primitives that survive loose JSON parsing:

```kotlin
package io.whozoss.factory.proxy

/**
 * Local, boundary-faithful view of the AgentOS `RunCostDto`
 * (GET /api/cases/{caseId}/run-cost). factory-service does not depend on
 * agentos-sdk, so this is parsed from the JSON reply as plain fields.
 *
 * `cost` is a KNOWN lower bound; `unknownCostCount > 0` means some turns could
 * not be priced and the real cost is strictly higher — never treat an unknown
 * cost as 0.
 */
data class RunCostDto(
    val caseId: String,
    val cost: Double,
    val unknownCostCount: Long,
    val runCostThreshold: Double?,
    val paused: Boolean,
    val active: Boolean,
    val liveTokens: Long,
)
```

Keep it minimal: we only aggregate the fields the `realCost` block exposes. We
intentionally omit `since`/`pausedCases` (not needed by the metrics block).

### 2. Add a "list attempts by workflow" read to the attempt port

We need every `caseId` the workflow has produced across its steps.

**a.** `factory-service/.../agentattempt/persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt`
— add a read-only query (mirror the style of `findByAttemptId`, but without the
`attemptId` filter):

```kotlin
@Query(
    """
    MATCH (a:DurableAgentAttempt)
    WHERE a.organizationId = ${'$'}organizationId
      AND a.workstreamId = ${'$'}workstreamId
      AND a.namespaceId = ${'$'}namespaceId
      AND a.workflowId = ${'$'}workflowId
    RETURN a
    """,
)
fun findByWorkflowId(
    organizationId: String,
    workstreamId: String,
    namespaceId: String,
    workflowId: String,
): List<DurableAgentAttemptNode>
```

**b.** `factory-service/.../agentattempt/persistence/DurableAgentAttemptRepository.kt`
(the port interface) — add:

```kotlin
/** Every attempt of a workflow, across all its steps. Used by cost aggregation. */
fun findByWorkflow(
    scope: TenantScope,
    namespaceId: String,
    workflowId: String,
): List<DurableAgentAttempt>
```

**c.** `factory-service/.../agentattempt/persistence/Neo4jDurableAgentAttemptRepository.kt`
— implement it (mirror `findByAttemptId`):

```kotlin
override fun findByWorkflow(
    scope: TenantScope,
    namespaceId: String,
    workflowId: String,
): List<DurableAgentAttempt> =
    attempts.findByWorkflowId(
        organizationId = scope.organizationId,
        workstreamId = scope.workstreamId,
        namespaceId = namespaceId,
        workflowId = workflowId,
    ).map { it.toDomain() }
```

**d.** `factory-service/.../agentattempt/service/DurableAgentAttemptService.kt`
— expose it (mirror `findByAttemptId`):

```kotlin
@Transactional(readOnly = true)
fun findByWorkflow(
    scope: TenantScope,
    namespaceId: String,
    workflowId: String,
): List<DurableAgentAttempt> = repository.findByWorkflow(scope, namespaceId, workflowId)
```

> If there is any in-memory/test fake implementing `DurableAgentAttemptRepository`,
> implement the new method there too (search the codebase for
> `: DurableAgentAttemptRepository` to be sure; the Neo4j impl is the only one
> found during recon, but verify).

### 3. `getRunCost` on the proxy

**a.** `factory-service/.../proxy/AgentOsProxyClient.kt` (interface) — add:

```kotlin
/**
 * Read the aggregated run cost of a case tree
 * (GET /api/cases/{caseId}/run-cost). AgentOS already aggregates the whole
 * descendant tree (delegations included), so the caller must pass ROOT case
 * ids only. Returns null when the case is unknown (404) or AgentOS is
 * unreachable / errors — the caller degrades gracefully and never fails.
 */
fun getRunCost(caseId: String, externalUserId: String? = null): RunCostDto?
```

**b.** `factory-service/.../proxy/HttpAgentOsProxyClient.kt` (impl) — add. Reuse
the existing `relay(...)` helper and `AgentOsNotFound` handling, but swallow
**all** failures to null (metrics must never throw because of AgentOS):

```kotlin
override fun getRunCost(caseId: String, externalUserId: String?): RunCostDto? =
    try {
        val raw = relay(
            "/api/cases/$caseId/run-cost",
            externalUserId,
            object : ParameterizedTypeReference<Map<String, Any?>>() {},
        ) ?: return null
        RunCostDto(
            caseId = (raw["caseId"] as? String) ?: caseId,
            cost = (raw["cost"] as? Number)?.toDouble() ?: 0.0,
            unknownCostCount = (raw["unknownCostCount"] as? Number)?.toLong() ?: 0L,
            runCostThreshold = (raw["runCostThreshold"] as? Number)?.toDouble(),
            paused = raw["paused"] as? Boolean ?: false,
            active = raw["active"] as? Boolean ?: false,
            liveTokens = (raw["liveTokens"] as? Number)?.toLong() ?: 0L,
        )
    } catch (_: AgentOsNotFound) {
        null
    } catch (error: Exception) {
        // AgentOsUnavailableException or any transport failure: degrade, do not
        // propagate — /metrics must still answer 200.
        logger.warn(error) { "run-cost unavailable for case $caseId; degrading" }
        null
    }
```

> `AgentOsNotFound` is currently a `private class` inside `HttpAgentOsProxyClient`
> — since `getRunCost` lives in the same class this is fine. Add a `KotlinLogging`
> logger field to the class if one is not already present (match the project's
> `mu.KotlinLogging` convention used in `WorkflowService`).

> **Note on types:** `relay(...)` uses `ParameterizedTypeReference<Map<String, Any?>>`
> exactly like `fetchNamespace`. JSON numbers deserialize as `Integer`/`Long`/`Double`;
> the `as? Number` casts above cover all of them.

### 4. Cost aggregation helper + `realCost` wiring in `WorkflowService`

Add a **private** aggregation helper and a small immutable result holder inside
`WorkflowService` (keep it in-file; single responsibility — pure aggregation
over the proxy). Do not add new constructor params: reuse the existing nullable
`agentOsProxyClient` and `durableAgentAttemptService`.

**Root case id resolution (step 2b of the prompt):**
1. `instance["controllerExecution"]["caseId"]` (controller/root case) when the
   workflow instance exists and that field is a non-blank String.
2. Each `DurableAgentAttempt.caseId` returned by
   `durableAgentAttemptService.findByWorkflow(scope, namespaceId, workflowId)`
   (non-blank).
3. **Dedupe by exact string value** into a `LinkedHashSet<String>`. AgentOS
   `run-cost` already rolls up the whole descendant tree of each case it is
   asked about, so we must NOT sum a case and one of its own sub-cases twice.
   The data model exposes no parent/child edges between these ids, so dedup by
   value is the faithful, pragmatic root-set: each distinct persisted case id is
   treated as a root and queried once. Document this in a code comment.

**Aggregation rules (step 2 of the prompt):**
- For each distinct caseId, call `agentOsProxyClient?.getRunCost(caseId, null)`.
- On a non-null result: `totalCost += cost`, `totalUnknown += unknownCostCount`,
  `totalLiveTokens += liveTokens`, `anyPaused = anyPaused || paused`,
  `anyActive = anyActive || active`, `maxThreshold = max(maxThreshold, threshold)`
  (keep the greatest non-null threshold; stays null if none seen).
- On a null result (case unknown / AgentOS down): skip that case's cost but DO
  NOT fail. (Optional, additive signal: you MAY count skipped cases, but the
  required contract only mandates the fields in step 5. Keep it simple: skip.)
- `unknownCostCount` is reported verbatim as the sum — it is the explicit
  "cost not fully known" signal and must never be folded into `cost`.

**Degraded default** (no instance case, no attempts, proxy null/disabled, or
every call returned null):
`cost = 0.0`, `unknownCostCount = 0L`, `liveTokens = 0L`, `paused = false`,
`active = false`, `runCostThreshold = null`.

Sketch:

```kotlin
private data class RealCostAggregate(
    val cost: Double = 0.0,
    val unknownCostCount: Long = 0L,
    val liveTokens: Long = 0L,
    val paused: Boolean = false,
    val active: Boolean = false,
    val runCostThreshold: Double? = null,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "cost" to cost,
        "unknownCostCount" to unknownCostCount,
        "liveTokens" to liveTokens,
        "paused" to paused,
        "active" to active,
        "runCostThreshold" to runCostThreshold,
    )
}

@Suppress("UNCHECKED_CAST")
private fun aggregateRealCost(
    scope: TenantScope,
    namespaceId: String,
    workflowId: String,
): RealCostAggregate {
    val proxy = agentOsProxyClient ?: return RealCostAggregate()
    val caseIds = LinkedHashSet<String>()
    // 1) controller/root case from the persisted instance (trusted boundary).
    repository.findInstance(scope, namespaceId, workflowId)?.let { instance ->
        ((instance.instance["controllerExecution"] as? Map<*, *>)?.get("caseId") as? String)
            ?.takeIf { it.isNotBlank() }
            ?.let(caseIds::add)
    }
    // 2) per-step attempt cases.
    durableAgentAttemptService
        ?.findByWorkflow(scope, namespaceId, workflowId)
        ?.forEach { attempt -> attempt.caseId.takeIf { it.isNotBlank() }?.let(caseIds::add) }
    // 3) sum over distinct roots; AgentOS already aggregates each tree.
    var agg = RealCostAggregate()
    for (caseId in caseIds) {
        val rc = proxy.getRunCost(caseId, null) ?: continue
        agg = RealCostAggregate(
            cost = agg.cost + rc.cost,
            unknownCostCount = agg.unknownCostCount + rc.unknownCostCount,
            liveTokens = agg.liveTokens + rc.liveTokens,
            paused = agg.paused || rc.paused,
            active = agg.active || rc.active,
            runCostThreshold = maxOf(agg.runCostThreshold, rc.runCostThreshold),
        )
    }
    return agg
}

/** max of two nullable thresholds, ignoring nulls. */
private fun maxOf(a: Double?, b: Double?): Double? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}
```

> Wrap the whole aggregation in a defensive `try/catch` that returns
> `RealCostAggregate()` on any unexpected error, so `metrics` is bulletproof
> even if the attempt read or map parsing throws. (The proxy already returns
> null on its own failures, but the repository read could throw.)

**Wire into `metrics(...)`** — additive only:

```kotlin
@Transactional(readOnly = true)
fun metrics(scope: TenantScope, namespaceId: String, workflowId: String, scopeName: String): Map<String, Any?> {
    val timing = timing(scope, namespaceId, workflowId)
    val retries = retries(scope, namespaceId, workflowId)
    val evidence = evidenceRepository.list(scope, namespaceId, workflowId)
    val interactions = interactionRepository.list(scope, namespaceId, workflowId, openOnly = false)
    val realCost = aggregateRealCost(scope, namespaceId, workflowId)
    return mapOf(
        "namespaceId" to namespaceId,
        "workflowId" to workflowId,
        "scope" to scopeName,
        "observedAt" to nowIso(),
        "timing" to timing,
        "retries" to retries,
        "evidenceCount" to evidence.size,
        "interactionCount" to interactions.size,
        "realCost" to realCost.toJson(),
    )
}
```

> Keep the existing keys and their order; only append `"realCost"`. The
> `retries(...)` call already runs `activeInstance(...)` which throws
> `WORKFLOW_NOT_FOUND`/`WORKFLOW_REMOVED` — that pre-existing behavior is
> unchanged (we add `realCost` after those existing calls, so a missing
> workflow still fails exactly as before, never reaching the proxy).

No controller change is required: `WorkflowController.metrics` already returns
`service.metrics(...)` inside the `{ "data": ... }` envelope, so `realCost`
appears automatically.

---

## Guard-rails (prompt §4)

- Scope, namespaceId and caseIds come only from the trusted boundary:
  `scope`/`namespaceId` are already resolved from `TrustContext`; caseIds come
  only from the persisted `WorkflowInstanceRecord.controllerExecution` and
  persisted `DurableAgentAttempt` rows. Never from query params / request body.
- The AgentOS call passes `externalUserId = null` (same trusted convention as
  `resolveRepoRoot(namespaceId, null)`); do not derive a user id from client
  input.
- Existing `/metrics` fields are untouched — `realCost` is strictly additive.

---

## Tests (prompt §5)

Prefer a focused **unit** test of `WorkflowService.aggregateRealCost` via a
fake `AgentOsProxyClient` + fake `DurableAgentAttemptService`, because the full
Neo4j integration harness (`Neo4jDomainIntegrationTest`) is heavier. Follow the
existing style in
`factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowServiceIntegrationTest.kt`
and `.../proxy/AgentOsProxyMockTest.kt`.

**New file:** `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowRealCostMetricsTest.kt`

Construct `WorkflowService` directly with lightweight fakes/mocks for the
repositories it needs (or extend the existing integration test if direct
construction proves awkward because of the many ctor params — a Mockito/MockK
`mock()` for the unused ports plus real fakes for `repository` +
`durableAgentAttemptService` + `agentOsProxyClient` is acceptable; match
whatever mocking lib the module already uses — check `build.gradle.kts`
test deps and existing tests before choosing). Cover:

1. **Single known root case** — instance has `controllerExecution.caseId = C1`,
   no attempts; proxy returns `cost=12.5, unknownCostCount=0, liveTokens=100,
   paused=false, active=true, runCostThreshold=50.0`. Assert `realCost` ==
   those values and that `timing`/`retries`/`evidenceCount`/`interactionCount`
   are still present and unchanged.
2. **Partially unknown cost** — proxy returns `unknownCostCount=3, cost=4.0`.
   Assert `realCost.unknownCostCount == 3` and `realCost.cost == 4.0` (NOT 0,
   NOT folded). Add a second attempt case so you can also assert summation
   (`cost` and `unknownCostCount` add across the two distinct cases, `paused`
   OR-combines, `runCostThreshold` keeps the max).
3. **AgentOS unreachable / case not found** — proxy `getRunCost` returns `null`
   for the case(s); assert `metrics` still returns 200-equivalent (the map is
   produced without throwing) and `realCost` == degraded default
   (`cost=0.0, unknownCostCount=0, liveTokens=0, paused=false, active=false,
   runCostThreshold=null`).
4. **Workflow with no case at all** — no `controllerExecution.caseId`, no
   attempts; proxy is never called; `realCost` == degraded default.

Also add (reusing `AgentOsProxyMockTest` style with `MockRestServiceServer`) a
proxy-level test in
`factory-service/src/test/kotlin/io/whozoss/factory/proxy/AgentOsProxyMockTest.kt`:
- `getRunCost` parses a 200 JSON body into the local `RunCostDto`.
- `getRunCost` returns `null` on 404 and on 500 (degrade, no throw) — contrast
  with `fetchNamespace` which throws on 500.

### Running the tests

From the repo root the factory build runs via Gradle composite:

```
cd factory-service && ./gradlew test --tests '*WorkflowRealCostMetrics*' --tests '*AgentOsProxyMock*'
```

(or `./gradlew :factory-service:test` depending on the composite settings — the
factory module ships its own `gradlew`; use it). The factory CI test suite is
run automatically after the build; you only run Gradle locally to validate the
new tests.

---

## Verification checklist

- [ ] `realCost` present in `metrics(...)` output with all six fields.
- [ ] Existing `/metrics` keys unchanged (name, order, values).
- [ ] AgentOS down / 404 / disabled → `/metrics` still succeeds, degraded block.
- [ ] `unknownCostCount` surfaced verbatim, never coerced into `cost`.
- [ ] caseIds sourced only from persisted instance + attempts (trust boundary).
- [ ] distinct caseIds deduped by value (no double counting of a root tree).
- [ ] No new constructor params on `WorkflowService` (reuse existing nullables).
- [ ] No agentos-sdk dependency added; local `RunCostDto` used.
- [ ] No cockpit v2 (Angular) files touched.
- [ ] New `findByWorkflow` implemented on every `DurableAgentAttemptRepository`
      implementation (verify there is only the Neo4j one).
- [ ] `./gradlew test` for factory-service passes.

## Files to touch (summary)

Create:
- `factory-service/src/main/kotlin/io/whozoss/factory/proxy/RunCostDto.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowRealCostMetricsTest.kt`

Edit:
- `factory-service/.../proxy/AgentOsProxyClient.kt` (interface: `getRunCost`)
- `factory-service/.../proxy/HttpAgentOsProxyClient.kt` (impl + logger)
- `factory-service/.../agentattempt/persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt` (`findByWorkflowId` query)
- `factory-service/.../agentattempt/persistence/DurableAgentAttemptRepository.kt` (`findByWorkflow`)
- `factory-service/.../agentattempt/persistence/Neo4jDurableAgentAttemptRepository.kt` (`findByWorkflow` impl)
- `factory-service/.../agentattempt/service/DurableAgentAttemptService.kt` (`findByWorkflow`)
- `factory-service/.../workflow/service/WorkflowService.kt` (`aggregateRealCost` + `metrics` wiring)
- `factory-service/.../proxy/AgentOsProxyMockTest.kt` (getRunCost tests)
