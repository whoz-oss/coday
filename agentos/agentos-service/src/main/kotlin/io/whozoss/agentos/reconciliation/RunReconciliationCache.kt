package io.whozoss.agentos.reconciliation

/**
 * Per-agent-run memoization of [ConfigReconciliationService.resolve] results.
 *
 * Lifetime: instantiated per agent run by [io.whozoss.agentos.agent.AgentServiceImpl.createAgentInstance],
 * passed to all reconciliation call sites within that run, then discarded with the run.
 *
 * NOT thread-safe by design — a single agent run is single-threaded by Spring AI / our
 * orchestration layer. If we ever execute tool resolution concurrently within a run,
 * wrap the [cache] map with ConcurrentHashMap.
 */
class RunReconciliationCache {
    private val cache = mutableMapOf<CacheKey, Any>()

    data class CacheKey(val name: String, val type: Class<*>)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrCompute(
        name: String,
        type: Class<T>,
        compute: () -> T,
    ): T = cache.getOrPut(CacheKey(name, type), compute) as T
}
