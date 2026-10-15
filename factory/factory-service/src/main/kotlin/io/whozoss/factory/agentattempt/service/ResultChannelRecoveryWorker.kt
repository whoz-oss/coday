package io.whozoss.factory.agentattempt.service

import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Startup reconciliation of the worker → Factory result channel.
 *
 * On `ApplicationReadyEvent` it runs ONE defensive pass over the AGENT-STEP
 * result aggregate ([AgentStepResultService.reconcileOnStartup]): attempts
 * whose result was durably submitted but that were left non-terminal are
 * terminalized coherently, and expired capabilities still waiting on an
 * unredeemed reservation are observed and reported. The pass never fabricates
 * an outcome — a terminal attempt is immutable and an expired reservation is
 * left to the durable-attempt recovery ([BridgeRecoveryWorker]), which owns
 * the execution lifecycle of the other attempt aggregate.
 */
@Component
class ResultChannelRecoveryWorker(
    private val service: AgentStepResultService,
) {

    private val logger = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        runCatching { service.reconcileOnStartup() }
            .onSuccess { report ->
                if (report.submittedFinalized > 0 || report.expiredReservations > 0 || report.needsResearchDeferred > 0) {
                    logger.info { "Result channel startup reconciliation: $report" }
                }
            }
            .onFailure { logger.warn(it) { "Result channel startup reconciliation failed" } }
    }
}
