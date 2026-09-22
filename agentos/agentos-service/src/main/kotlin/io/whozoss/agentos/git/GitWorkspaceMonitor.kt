package io.whozoss.agentos.git

import mu.KLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** Observes stored resources even when no conversation has an in-memory runtime or SSE subscriber. */
@Component
class GitWorkspaceMonitor(
    private val bindings: CaseResourceBindingService,
    private val roots: ExchangeRootResolver,
    private val statuses: GitWorkspaceStatusService,
) {
    private val active = AtomicBoolean()
    private var offset = 0

    @Scheduled(fixedDelayString = "\${agentos.git.status.interval-ms:60000}", initialDelayString = "\${agentos.git.status.initial-delay-ms:30000}")
    fun poll() {
        if (!active.compareAndSet(false, true)) return
        try {
            val candidates = bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 10000)
            if (candidates.isEmpty()) return
            if (offset >= candidates.size) offset = 0
            val batch = candidates.drop(offset).take(5)
            offset += batch.size
            batch.forEach { binding ->
                try {
                    statuses.refresh(binding, roots.resolve(binding.rootCaseId).repositoryPath.toAbsolutePath().normalize())
                } catch (e: Exception) {
                    logger.warn(e) { "Workspace status refresh failed for ${binding.rootCaseId}" }
                }
            }
        } finally { active.set(false) }
    }
    companion object : KLogging()
}
