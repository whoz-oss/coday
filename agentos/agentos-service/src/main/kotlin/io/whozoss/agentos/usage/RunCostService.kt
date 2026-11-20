package io.whozoss.agentos.usage

import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.saveChange
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.usageRecord.PausedCostDto
import io.whozoss.agentos.sdk.api.usageRecord.RunCostDto
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

class CostRunStopped : RuntimeException("Execution stopped by the user")

/**
 * Shared live accounting for a case and its delegated work. A new human message starts
 * a new cost window; redirects and nested agent invocations share the same window.
 * Gates hold the actual request, so continuation does not replay tools or prompts.
 * Checks happen between requests: a response already in flight can overshoot the limit.
 */
@Service
class RunCostService(
    private val cases: CaseRepository,
    private val events: CaseEventService,
    private val namespaces: NamespaceService,
    private val records: UsageRecordService,
    private val usageConfig: UsageConfigProperties = UsageConfigProperties(),
) {
    private val sessions = mutableMapOf<UUID, Session>()

    internal data class Session(
        val caseId: UUID,
        val since: Instant,
        val ancestorIds: Set<UUID>,
        var cost: Double,
        var unknown: Long,
        var threshold: Double?,
        val live: MutableSet<UsageAccumulator> = mutableSetOf(),
        /** The live accumulators of [live] that never pause ([registerNonPausing]): they do not keep a stopped session. */
        val nonPausing: MutableSet<UsageAccumulator> = mutableSetOf(),
        var confirmation: CompletableFuture<Void>? = null,
        var stopped: Boolean = false,
        val cancellation: CompletableFuture<Void> = CompletableFuture(),
        val historyUnavailable: Boolean = false,
    )

    /** The live usage of one invocation in the sessions of its case and their ancestors ([runSessions]). */
    inner class Registration internal constructor(
        private val accumulator: UsageAccumulator,
        private val runSessions: List<Session>,
        /** False for [registerNonPausing]: over the threshold, its requests go on instead of asking a confirmation. */
        private val pausing: Boolean = true,
    ) {
        fun beforeCall(): CompletableFuture<Void> =
            synchronized(this@RunCostService) {
                val waits =
                    runSessions.map { session ->
                        if (session.stopped) return@synchronized CompletableFuture.failedFuture(CostRunStopped())
                        val threshold = session.threshold
                        if (pausing && threshold != null && snapshot(session).cost >= threshold) {
                            session.confirmation ?: CompletableFuture<Void>().also { session.confirmation = it }
                        } else {
                            CompletableFuture.completedFuture(null)
                        }
                    }
                CompletableFuture
                    .anyOf(
                        CompletableFuture.allOf(*waits.toTypedArray()),
                        *runSessions.map { it.cancellation }.toTypedArray(),
                    ).thenApply { null }
            }

        /** Read before finish removes this registration; a graceful stop is not a failure. */
        fun isStopped(): Boolean = synchronized(this@RunCostService) {
            runSessions.any { it.stopped }
        }

        /** Keep the transfer from live usage to persisted usage atomic for readers. */
        fun finish(persist: () -> Unit) =
            synchronized(this@RunCostService) {
                try {
                    persist()
                } finally {
                    val usage = accumulator.snapshot()
                    runSessions.forEach { session ->
                        session.nonPausing.remove(accumulator)
                        if (session.live.remove(accumulator)) {
                            // A stopped session may have ended while a non-pausing registration was in flight, and
                            // a new one started for the case: it summed the persisted usage before this one was
                            // written, so this usage goes to it.
                            val current = sessions[session.caseId] ?: session
                            current.cost += usage.knownCost
                            current.unknown += usage.unknownCalls
                        }
                        removeIfOver(session)
                    }
                }
            }
    }

    @Synchronized
    fun register(
        caseId: UUID,
        accumulator: UsageAccumulator,
    ): Registration {
        requireEnabled()
        val runSessions = prepareSessions(caseId)
        publish(runSessions, accumulator, pausing = true)
        return Registration(accumulator, runSessions).also { accumulator.beforeCall = it::beforeCall }
    }

    /** What [registerNonPausing] decided. */
    sealed interface NonPausingRegistration {
        class Registered(
            val registration: Registration,
        ) : NonPausingRegistration

        /** Nothing was registered: [caseId] (the case or one of its ancestors) is [reason]. */
        data class Refused(
            val caseId: UUID,
            val reason: Reason,
        ) : NonPausingRegistration

        enum class Reason { OVER_THRESHOLD, PAUSED, STOPPED }
    }

    /**
     * Registers spend that must never pause its case (a side question), in one atomic step: refused when the case or
     * one of its ancestors is stopped, waiting for a cost confirmation, or at or over its threshold.
     *
     * Once registered, its spend counts toward the cost of the case and its ancestors like a run's: a running turn
     * may pause at its next gate because of it. Its own requests never wait for a confirmation (they only fail when
     * the case is stopped), and it never keeps a stopped session alive, so a later run does not inherit `stopped`.
     */
    @Synchronized
    fun registerNonPausing(
        caseId: UUID,
        accumulator: UsageAccumulator,
    ): NonPausingRegistration {
        requireEnabled()
        val runSessions = prepareSessions(caseId)
        runSessions.forEach { session ->
            val threshold = session.threshold
            val reason =
                when {
                    session.stopped -> NonPausingRegistration.Reason.STOPPED
                    session.confirmation != null -> NonPausingRegistration.Reason.PAUSED
                    threshold != null && snapshot(session).cost >= threshold -> NonPausingRegistration.Reason.OVER_THRESHOLD
                    else -> null
                }
            if (reason != null) return NonPausingRegistration.Refused(session.caseId, reason)
        }
        publish(runSessions, accumulator, pausing = false)
        val registration = Registration(accumulator, runSessions, pausing = false)
        accumulator.beforeCall = registration::beforeCall
        return NonPausingRegistration.Registered(registration)
    }

    /** The sessions of the lineage of [caseId], existing or new (not published): a later read can fail. */
    private fun prepareSessions(caseId: UUID): List<Session> {
        val lineage = ancestors(caseId)
        val thresholds = lineage.associate { case ->
            val existing = sessions[case.id]
            // A running session keeps its snapshot, including an unlimited (null) threshold.
            case.id to if (existing != null) existing.threshold else resolveThreshold(case)
        }
        val allowHistoryFailure = thresholds.values.all { it == null }
        if (!allowHistoryFailure) {
            lineage.forEach { case -> sessions[case.id]?.let(::requireHistory) }
        }
        return lineage.map { case ->
            sessions[case.id] ?: newSession(case, thresholds.getValue(case.id), allowHistoryFailure)
        }
    }

    private fun publish(
        runSessions: List<Session>,
        accumulator: UsageAccumulator,
        pausing: Boolean,
    ) {
        runSessions.forEach { session ->
            sessions[session.caseId] = session
            session.live.add(accumulator)
            if (!pausing) session.nonPausing.add(accumulator)
        }
    }

    /** A session ends with its last registration; a stopped one as soon as only non-pausing ones remain. */
    private fun removeIfOver(session: Session) {
        val over = session.live.isEmpty() || (session.stopped && session.live.all { it in session.nonPausing })
        if (over && sessions[session.caseId] === session) sessions.remove(session.caseId)
    }

    @Synchronized
    fun state(caseId: UUID): RunCostDto {
        requireEnabled()
        val state = snapshot(sessions[caseId] ?: newSession(getCase(caseId)))
        val blockingAncestors = sessions[caseId]?.takeUnless { it.stopped }?.ancestorIds.orEmpty() - caseId
        val paused =
            sessions.values
                .filter {
                    (caseId in it.ancestorIds || it.caseId in blockingAncestors) &&
                        it.confirmation != null && !it.stopped
                }
                .map {
                    PausedCostDto(it.caseId, snapshot(it).cost, it.threshold!!, ancestor = it.caseId in blockingAncestors)
                }
        return state.copy(paused = state.paused || paused.any { it.ancestor }, pausedCases = paused)
    }

    /** Optimistic precondition prevents a retried/double-clicked request doubling twice. */
    @Synchronized
    fun continueRun(
        caseId: UUID,
        expectedThreshold: Double,
    ): RunCostDto {
        requireEnabled()
        val session = sessions[caseId] ?: throw ResponseStatusException(HttpStatus.CONFLICT, "No paused execution")
        val pending = session.confirmation ?: throw ResponseStatusException(HttpStatus.CONFLICT, "No cost confirmation pending")
        if (session.threshold != expectedThreshold) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The threshold changed; refresh before confirming")
        }
        val case = getCase(caseId)
        // Zero has no positive double. An explicit positive edit is required first.
        val current = case.runCostThreshold ?: session.threshold!!
        val next = if (current != session.threshold) current else current * 2
        if (!next.isFinite() || next <= 0 || next <= expectedThreshold) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Set a positive, higher threshold before continuing")
        }
        cases.saveChange(case) { it.copy(runCostThreshold = next) }
        session.threshold = next
        // One click = one doubling. If a response overshot more than 2x, another
        // explicit confirmation is needed; never silently multiply several times.
        if (snapshot(session).cost < next) {
            session.confirmation = null
            pending.complete(null)
        }
        return state(caseId)
    }

    @Synchronized
    fun stop(caseId: UUID) {
        if (!usageConfig.enabled) return
        sessions.values.filter { caseId in it.ancestorIds }.forEach { session ->
            session.stopped = true
            session.cancellation.completeExceptionally(CostRunStopped())
            session.confirmation?.completeExceptionally(CostRunStopped())
            session.confirmation = null
        }
        // Copied: a session left with only non-pausing registrations ends at once.
        sessions.values.toList().forEach(::removeIfOver)
    }

    /** Waiting on a descendant's human confirmation also pauses a delegation's budget. */
    @Synchronized
    fun isPaused(caseId: UUID): Boolean {
        if (!usageConfig.enabled) return false
        val ancestors = sessions[caseId]?.ancestorIds.orEmpty()
        return sessions.values.any { session ->
            session.confirmation != null && !session.stopped &&
                (session.caseId in ancestors || caseId in session.ancestorIds)
        }
    }

    private fun requireEnabled() {
        if (!usageConfig.enabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Usage tracking is disabled")
        }
    }

    private fun snapshot(session: Session): RunCostDto {
        requireHistory(session)
        val live = session.live.map { it.snapshot() }
        return RunCostDto(
            caseId = session.caseId,
            since = session.since,
            cost = session.cost + live.sumOf { it.knownCost },
            unknownCostCount = session.unknown + live.sumOf { it.unknownCalls },
            runCostThreshold = session.threshold,
            paused = session.confirmation != null && !session.stopped,
            active = session.live.isNotEmpty(),
            liveTokens = live.sumOf { it.usage.totalTokens },
        )
    }

    private fun requireHistory(session: Session) {
        if (session.historyUnavailable) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Usage history is unavailable for this run")
        }
    }

    private fun resolveThreshold(case: Case): Double? =
        case.runCostThreshold ?: namespaces.resolveRunCostThreshold(case.namespaceId)

    private fun newSession(
        case: Case,
        threshold: Double? = resolveThreshold(case),
        allowHistoryFailure: Boolean = false,
    ): Session {
        val since =
            events
                .findByParent(case.id)
                .filterIsInstance<MessageEvent>()
                .lastOrNull { it.actor.role == ActorRole.USER }
                ?.timestamp ?: case.metadata.created
        var historyUnavailable = false
        val total = try {
            records.sumCostByCaseTreeSince(case.id, since)
        } catch (failure: Exception) {
            if (!allowHistoryFailure || failure is CancellationException || failure is InterruptedException) throw failure
            historyUnavailable = true
            logger.warn(failure) { "Usage history unavailable for unlimited case ${case.id}; run cost remains unavailable" }
            null
        }
        return Session(
            case.id,
            since,
            ancestors(case.id).map { it.id }.toSet(),
            total?.cost ?: 0.0,
            total?.unknownCostCount ?: 0,
            threshold,
            historyUnavailable = historyUnavailable,
        )
    }

    private fun getCase(id: UUID): Case =
        cases.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found")

    private fun ancestors(caseId: UUID): List<Case> {
        val result = mutableListOf<Case>()
        var current: UUID? = caseId
        while (current != null) {
            if (result.size >= 11 || result.any { it.id == current }) {
                throw IllegalStateException("Invalid case ancestry")
            }
            val case = getCase(current)
            result.add(case)
            current = case.parentCaseId
        }
        return result
    }

    companion object : KLogging()
}
