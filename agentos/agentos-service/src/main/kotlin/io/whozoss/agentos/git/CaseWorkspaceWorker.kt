package io.whozoss.agentos.git

import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prepares requested namespace repositories outside request threads.
 * The in-process guard coordinates one AgentOS instance; multiple instances require a lease.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class CaseWorkspaceWorker(
    private val associationService: GitRepositoryAssociationService,
    private val checkoutService: RepositoryCheckoutService,
    private val checkoutProvisioner: RepositoryCheckoutProvisioner,
) {
    private val sweeping = AtomicBoolean(false)

    @Scheduled(
        initialDelayString = "\${agentos.git.worker.initial-delay-ms:15000}",
        fixedDelayString = "\${agentos.git.worker.interval-ms:10000}",
    )
    fun provisionPending() {
        if (!sweeping.compareAndSet(false, true)) return
        try {
            prepareRequestedCheckouts()
        } catch (e: Exception) {
            logger.error(e) { "Repository provisioning sweep failed" }
        } finally {
            sweeping.set(false)
        }
    }

    private fun prepareRequestedCheckouts() {
        val requested = checkoutService.findByStatusIn(listOf(RepositoryCheckoutStatus.PREPARING), BATCH_SIZE)
        requested.forEach { checkout ->
            try {
                val settings =
                    associationService.findSettings(checkout.namespaceId)
                        ?: run {
                            checkoutService.markStatus(
                                checkout.id,
                                RepositoryCheckoutStatus.FAILED,
                                "The namespace is no longer associated with a repository",
                            )
                            return@forEach
                        }
                checkoutProvisioner.ensureReady(settings)
            } catch (e: Exception) {
                // ensureReady already recorded FAILED with the cause; keep going through the batch.
                logger.error(e) { "Could not prepare the checkout of namespace ${checkout.namespaceId}" }
            }
        }
    }

    companion object : KLogging() {
        private const val BATCH_SIZE = 5
    }
}
