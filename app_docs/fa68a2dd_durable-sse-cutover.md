# Durable SSE AgentOS bridge cutover

## Summary

`factory-service` now treats the durable AgentOS SSE bridge as the default execution driver for `agent` workflow steps. `CapabilityExecutionService` requires both `DurableAgentAttemptService` and `AgentOsExecutionAdapter`; its normal path claims an attempt, performs the remote turn without a Neo4j transaction, and finalizes durable result evidence. The historical `HttpAgentOsProxyClient` / `AgentOsAgentTurnCapability` polling path remains available only when the adapter is explicitly disabled.

This makes the bridge default by changing `AgentOsAdapterProperties.enabled` to `true` and creating the adapter bean unconditionally. Recovery and cancellation services are also enabled when the property is absent, while setting the property to `false` disables those bridge services and selects polling.

## Where the change lives

- `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt` makes the durable attempt service and execution adapter non-null constructor dependencies and dispatches to the SSE adapter by default. Polling remains in `resolveAgentViaPolling` as the explicit fallback. Finalization records `agent-result` evidence before the attempt becomes terminal.
- `factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/AgentOsAdapterProperties.kt` changes the default flag to enabled; `AgentOsAdapterConfiguration.kt` always supplies the adapter bean.
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/BridgeRecoveryWorker.kt` and `BridgeCancellationService.kt` now run by default and are absent when the bridge is explicitly disabled.
- Updated capability/workflow tests inject the now-required dependencies. `DurableAgentOsBridgeIntegrationTest.kt` adds coverage for dependency gating, durable A-to-B sequencing, and finalization failure/reconciliation.
- `app_docs/cutover_durable_agentos_sse_bridge.md` contains the detailed cutover and acceptance-test matrix; `specs/fa68a2dd_cutover_durable_agentos_sse_bridge.md` records the implementation scope and acceptance criteria.

## Runtime behavior and fallback

The default configuration is:

```yaml
factory:
  adapter:
    agentos:
      enabled: true
```

With the default, attempt identity and stable case identity prevent duplicate turns across retries/restarts, and event high-water marks provide SSE event deduplication. Reconnection/reconciliation can finalize a remote result after a dropped connection or Factory restart. Unknown states are not treated as success: unanswered questions become `WaitingHuman`, idle cases without structured output are indeterminate, and error, killed, timeout, or exhausted-observation cases do not succeed implicitly. Downstream B is released only after A’s committed `agent-result` evidence and successful attempt status; B reads A’s persisted outputs.

For troubleshooting only, set `factory.adapter.agentos.enabled=false` (or the equivalent relaxed-binding environment variable). `CapabilityExecutionService` then uses the legacy polling driver. The adapter bean remains available to satisfy the mandatory constructor dependency but is not invoked, and the bridge recovery/cancellation beans are not created. Remove the override or set it to `true` to restore SSE.

## Verification recorded by the change

The documented acceptance report identifies coverage for all 20 requested criteria. The bridge integration suite covers sequencing, durable evidence ordering, concurrent ownership, long-turn transaction boundaries, and finalization recovery. Dedicated adapter, SSE, verdict, high-water-mark, fencing, and recovery tests cover reconnect/replay, duplicate and stale events, restart/crash windows, lease fencing, verdict mapping, and reconciliation. The report states that all criteria are green.

Run the factory-service checks from `factory-service/`:

```bash
./gradlew compileKotlin compileTestKotlin
./gradlew test
./gradlew test --tests "io.whozoss.factory.workflow.DurableAgentOsBridgeIntegrationTest"
```

The full criterion-to-test mapping is in `app_docs/cutover_durable_agentos_sse_bridge.md`.
