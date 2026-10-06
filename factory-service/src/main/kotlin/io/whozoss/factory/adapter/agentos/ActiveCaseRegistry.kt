package io.whozoss.factory.adapter.agentos

import java.util.concurrent.ConcurrentHashMap
import mu.KotlinLogging
import org.springframework.stereotype.Component

/** Lifecycle state of a case tracked by the [ActiveCaseRegistry]. */
enum class ActiveCaseState { CREATED, RUNNING, WAITING_HUMAN, QUIESCENT, TERMINATED }

/** One tracked case: its trusted identities, current state and per-turn baseline. */
data class ActiveCaseEntry(
    val binding: TrustedCaseBinding,
    val state: ActiveCaseState,
    val baseline: HighWaterMark?,
    val startedAtEpochMs: Long,
)

/**
 * Process-wide, thread-safe registry of **every** active AgentOS case driven
 * by this Factory instance.
 *
 * Distinct from the per-workflow `activeAgentCase` projection persisted in
 * the workflow instance document (a per-step marker): this registry is the
 * enumerable, in-memory pilot of all live cases, used for status tracking and
 * for the graceful shutdown ([shutdownActiveCases]) — see
 * [AgentOsCaseShutdownHook].
 *
 * All identities come from the [TrustedCaseBinding] of the Factory boundary,
 * never from LLM arguments.
 */
@Component
class ActiveCaseRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val logger = KotlinLogging.logger {}

    /** caseId → tracked entry. */
    private val cases = ConcurrentHashMap<String, ActiveCaseEntry>()

    /** Track a (new or recovered) case; a repeated registration refreshes the binding. */
    fun register(binding: TrustedCaseBinding, baseline: HighWaterMark? = null) {
        val previous = cases.put(
            binding.caseId,
            ActiveCaseEntry(
                binding = binding,
                state = ActiveCaseState.CREATED,
                baseline = baseline,
                startedAtEpochMs = clock(),
            ),
        )
        if (previous == null) {
            logger.info {
                "Registered active case ${binding.caseId} (attempt ${binding.attemptId}, runtime ${binding.runtimeId ?: "unknown"})"
            }
        } else {
            logger.info { "Re-registered active case ${binding.caseId} (attempt ${binding.attemptId})" }
        }
    }

    /** Update the tracked state of a case (no-op when the case is not tracked). */
    fun markState(caseId: String, state: ActiveCaseState) {
        cases.computeIfPresent(caseId) { _, entry -> entry.copy(state = state) }
    }

    /** Record the per-turn baseline captured before a turn started on the case. */
    fun markBaseline(caseId: String, baseline: HighWaterMark) {
        cases.computeIfPresent(caseId) { _, entry -> entry.copy(baseline = baseline) }
    }

    /** Stop tracking a case (closed/sealed, or shut down). */
    fun deregister(caseId: String) {
        cases.remove(caseId)?.let { logger.info { "Deregistered case $caseId (last state ${it.state})" } }
    }

    /** Enumeration of every tracked case, oldest registration first. */
    fun snapshot(): List<ActiveCaseEntry> = cases.values.sortedBy { it.startedAtEpochMs }

    fun activeCount(): Int = cases.size

    /**
     * Graceful shutdown: interrupt then kill every tracked non-terminal case
     * through [adapter], logging each case's state and outcome, then
     * deregister it. Best-effort and never throws: a case whose runtime is
     * unreachable is logged as such — never silently treated as done.
     */
    fun shutdownActiveCases(adapter: AgentRuntimeAdapter, reason: String = "factory-service shutdown") {
        val active = snapshot()
        if (active.isEmpty()) {
            logger.info { "No active AgentOS case to shut down" }
            return
        }
        logger.info { "Shutting down ${active.size} active AgentOS case(s): $reason" }
        for (entry in active) {
            val caseId = entry.binding.caseId
            try {
                if (entry.state == ActiveCaseState.TERMINATED) {
                    logger.info { "Case $caseId already TERMINATED; skipping interrupt/kill" }
                } else {
                    logger.info { "Interrupting case $caseId (attempt ${entry.binding.attemptId}, state ${entry.state})" }
                    runCatching { adapter.interrupt(caseId, reason) }
                        .onFailure {
                            logger.warn(it) { "Interrupt of case $caseId failed (runtime unreachable?); the case is NOT confirmed stopped" }
                        }
                    runCatching { adapter.kill(caseId) }
                        .onFailure {
                            logger.warn(it) { "Kill of case $caseId failed (runtime unreachable?); the case is NOT confirmed stopped" }
                        }
                }
            } finally {
                deregister(caseId)
            }
        }
    }
}
