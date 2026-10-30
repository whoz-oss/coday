package io.whozoss.agentos.git

import mu.KLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Reports an instance where Git is available but its background worker is off.
 *
 * The worker is opt-in (`agentos.git.worker.enabled`). Without it, namespace repositories are never
 * cloned and nothing surfaces the reason, so a missing flag must at least show in the logs.
 */
@Component
class GitWorkerStartupCheck(
    private val gitAvailability: GitAvailability,
    private val worker: ObjectProvider<CaseWorkspaceWorker>,
) {
    fun workerMissing(): Boolean = gitAvailability.isAvailable() && worker.getIfAvailable() == null

    @EventListener(ApplicationReadyEvent::class)
    fun warnIfWorkerMissing() {
        if (workerMissing()) {
            logger.info {
                "The GIT plugin is loaded but the Git worker is disabled: namespace repositories will not be prepared. " +
                    "Set AGENTOS_GIT_WORKER_ENABLED=true to enable it."
            }
        }
    }

    companion object : KLogging()
}
