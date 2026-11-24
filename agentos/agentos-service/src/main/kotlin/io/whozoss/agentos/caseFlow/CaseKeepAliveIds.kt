package io.whozoss.agentos.caseFlow

import mu.KLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The ids of the cases whose prompt cache keep-alive is on ([Case.keepAlive]), kept in memory so that
 * [CaseKeepAliveScheduler] reads no case at all while none is on, and only the cases that are on otherwise.
 *
 * Loaded from the store when the service is ready (or at the first use, if that load failed), then kept up to date by
 * [CaseService.setKeepAlive]. A deleted case may stay in the set: the scheduler reads cases without the removed ones.
 * Single instance, like the scheduler: a change made through another instance is only seen after a restart.
 */
@Component
class CaseKeepAliveIds(
    private val caseRepository: CaseRepository,
) {
    private val ids = ConcurrentHashMap.newKeySet<UUID>()

    @Volatile
    private var loaded = false

    @EventListener(ApplicationReadyEvent::class)
    fun load() {
        runCatching { ensureLoaded() }.onFailure { logger.warn(it) { "[KeepAlive] could not load the cases with keep-alive on, retried at the next use" } }
    }

    /** The ids of the cases with keep-alive on, empty when none is. */
    fun current(): Set<UUID> {
        ensureLoaded()
        return ids.toSet()
    }

    fun set(
        caseId: UUID,
        keepAlive: Boolean,
    ) {
        ensureLoaded()
        if (keepAlive) ids.add(caseId) else ids.remove(caseId)
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        ids.addAll(caseRepository.findKeepAliveIds())
        loaded = true
    }

    companion object : KLogging()
}
