package io.whozoss.agentos.caseFlow.postprocessing

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.stereotype.Service

/**
 * Orchestrates all [CasePostProcessor] implementations.
 *
 * Called by [io.whozoss.agentos.caseFlow.CaseServiceImpl] at two lifecycle points:
 * - after a user message is added (first and subsequent messages)
 * - after a case turn completes (status transitions to IDLE)
 *
 * Each eligible processor is launched as an independent fire-and-forget coroutine
 * inside [scope]. Failures are isolated: one processor throwing does not affect others.
 *
 * @param processors All [CasePostProcessor] beans discovered by Spring. Empty list is
 *   valid — the service becomes a no-op.
 * @param scope The coroutine scope shared with [io.whozoss.agentos.caseFlow.CaseServiceImpl].
 *   Injected so the lifecycles are aligned: when the service shuts down its scope,
 *   all in-flight post-processing coroutines are cancelled cleanly.
 */
@Service
class CasePostProcessingService(
    private val processors: List<CasePostProcessor>,
    private val scope: CoroutineScope,
) {
    /**
     * Evaluate all processors against [case] + [events] and launch eligible ones
     * as independent fire-and-forget coroutines.
     *
     * @param emitEvent Callback forwarded to each processor so it can push transient
     *   SSE events without depending on [io.whozoss.agentos.caseFlow.CaseServiceImpl].
     */
    fun trigger(
        case: Case,
        events: List<CaseEvent>,
        emitEvent: (CaseEvent) -> Unit,
    ) {
        processors.forEach { processor ->
            if (processor.shouldProcess(case, events)) {
                scope.launch {
                    runCatching { processor.process(case, events, emitEvent) }
                        .onFailure { e ->
                            logger.error(e) {
                                "[CasePostProcessing] ${processor::class.simpleName} failed for case ${case.id}"
                            }
                        }
                }
            }
        }
    }

    companion object : KLogging()
}
