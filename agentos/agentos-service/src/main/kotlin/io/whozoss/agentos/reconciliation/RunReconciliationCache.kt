package io.whozoss.agentos.reconciliation

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-agent-run memoization of [ConfigMergeService.resolve] results.
 *
 * Lifetime: instantiated per agent run by [io.whozoss.agentos.agent.AgentServiceImpl.createAgentInstance],
 * passed to all reconciliation call sites within that run, then discarded with the run.
 *
 * Backed by [ConcurrentHashMap] so the cache stays safe under concurrent tool resolution
 * within a run (defensive — the current execution model is single-threaded by Spring AI
 * / our orchestration layer, but this removes an entire class of latent regression should
 * that ever change).
 *
 * Defence-in-depth: the cache key includes `(namespaceId, userId)` even though a single
 * cache instance lives for one `(namespaceId, userId)` run by construction. Should the
 * lifecycle ever be broken (singleton bean, refactor, accidental sharing across runs),
 * the scoped key prevents a cross-tenant config leak. Cost is one extra `UUID` per entry.
 */
class RunReconciliationCache {
    private val cache = ConcurrentHashMap<CacheKey, Any>()

    data class CacheKey(
        val name: String,
        val type: Class<*>,
        val namespaceId: UUID,
        val userId: UUID,
    )

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrCompute(
        name: String,
        type: Class<T>,
        namespaceId: UUID,
        userId: UUID,
        compute: () -> T,
    ): T = cache.getOrPut(CacheKey(name, type, namespaceId, userId), compute) as T
}
