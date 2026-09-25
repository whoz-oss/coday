package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.spi.CaseLifecycleObserver
import mu.KLogging
import org.pf4j.Extension
import java.util.UUID

/**
 * Observes case lifecycle transitions for the Factory bridge.
 *
 * The Factory bridge holds volatile, in-memory step-result capabilities: when a case
 * reaches a terminal status ([CaseStatus.KILLED] or [CaseStatus.ERROR]) any pending
 * binding is invalidated so a stale capability can never be reused — the registry fails
 * closed by design.
 *
 * Event observation is logged at debug level only; it must never affect case execution.
 */
@Extension
class FactoryCaseLifecycleObserver
    @JvmOverloads
    constructor(
        private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
    ) : CaseLifecycleObserver {
        override fun onStatusChanged(
            caseId: UUID,
            oldStatus: CaseStatus,
            newStatus: CaseStatus,
        ) {
            if (oldStatus == newStatus) return
            logger.debug { "Factory bridge observed case $caseId status $oldStatus -> $newStatus" }
            if (newStatus.isTerminal()) {
                services().stepResultBindings.remove(caseId)
                services().pendingCheckpoints.remove(caseId)
                logger.info { "Factory bridge invalidated step-result binding for terminal case $caseId ($newStatus)" }
            }
        }

        override fun onEventStored(
            caseId: UUID,
            event: CaseEvent,
        ) {
            logger.debug { "Factory bridge observed event ${event.type} for case $caseId" }
        }

        companion object : KLogging()
    }
