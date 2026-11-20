package io.whozoss.agentos.caseFlow

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Which cases the user stopped (interrupted or killed) since their last run started: nothing that spends on their
 * behalf outside a run (a side question) is admitted for them, whether usage is tracked or not.
 *
 * A stop is recorded by [stopped] and cleared only by [runStarting], called when a run starts, before its agent is
 * resolved. So a stop that arrives after a run started (during the resolution of its agent, or while it runs) is
 * never cleared by that run: only a run started after the stop clears it. A deleted case is [forget]-ten.
 *
 * Known limitation: this state is in memory and lost on restart, like the live sessions of
 * [io.whozoss.agentos.usage.RunCostService]. After a restart, a case stopped before it admits side questions again
 * (a killed case does not: its status is persisted).
 */
@Component
class CaseRunAdmission {
    private val stoppedCases = mutableSetOf<UUID>()

    /** The user stopped [caseId]: no side question until its next run starts. */
    @Synchronized
    fun stopped(caseId: UUID) {
        stoppedCases += caseId
    }

    /** A run of [caseId] starts (before its agent is resolved): the stops recorded so far are cleared. */
    @Synchronized
    fun runStarting(caseId: UUID) {
        stoppedCases -= caseId
    }

    @Synchronized
    fun isStopped(caseId: UUID): Boolean = caseId in stoppedCases

    /** [caseId] was deleted. */
    @Synchronized
    fun forget(caseId: UUID) {
        stoppedCases -= caseId
    }
}
