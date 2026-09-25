package io.whozoss.agentos.git

import mu.KLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Qualifier
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Observes stored resources even when no conversation has an in-memory runtime or SSE subscriber. */
@Component
class GitWorkspaceMonitor(
    private val bindings: CaseResourceBindingService,
    private val roots: GitExchangeRootResolver,
    private val statuses: GitWorkspaceStatusService,
    @Qualifier("gitWorkspaceExecutor") private val executor: Executor = Executor { it.run() },
) {
    private val active = AtomicBoolean()
    private var cursor: CaseResourceBindingCursor? = null

    @Scheduled(fixedDelayString = "\${agentos.git.status.interval-ms:60000}", initialDelayString = "\${agentos.git.status.initial-delay-ms:30000}")
    fun poll() = submitWorkspaceSweep(executor, active) { pollBatch() }

    private fun pollBatch() {
        try {
            val page = bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 5, cursor)
            val batch = if (page.isEmpty() && cursor != null) {
                bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 5)
            } else page
            cursor = batch.takeIf { it.size == 5 }?.last()?.let(CaseResourceBindingCursor::after)
            batch.forEach { binding ->
                if (Thread.currentThread().isInterrupted) return
                try {
                    statuses.refresh(binding, roots.resolveGit(binding.rootCaseId).repositoryPath.toAbsolutePath().normalize())
                } catch (e: Exception) {
                    logger.warn(e) { "Workspace status refresh failed for ${binding.rootCaseId}" }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Workspace status sweep failed" }
        }
    }
    companion object : KLogging()
}
