package io.whozoss.agentos.usage

import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
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

    private data class Session(
        val caseId: UUID,
        val since: Instant,
        val ancestorIds: Set<UUID>,
        var cost: Double,
        var unknown: Long,
        var threshold: Double?,
        val live: MutableSet<UsageAccumulator> = mutableSetOf(),
        var confirmation: CompletableFuture<Void>? = null,
        var stopped: Boolean = false,
        val cancellation: CompletableFuture<Void> = CompletableFuture(),
        val historyUnavailable: Boolean = false,
    )

    inner class Registration internal constructor(
        private val accumulator: UsageAccumulator,
        private val caseIds: List<UUID>,
    ) {
        fun beforeCall(): CompletableFuture<Void> =
            synchronized(this@RunCostService) {
                val waits =
                    caseIds.map { id ->
                        val session = sessions.getValue(id)
                        if (session.stopped) return@synchronized CompletableFuture.failedFuture(CostRunStopped())
                        val threshold = session.threshold
                        if (threshold != null && snapshot(session).cost >= threshold) {
                            session.confirmation ?: CompletableFuture<Void>().also { session.confirmation = it }
                        } else {
                            CompletableFuture.completedFuture(null)
                        }
                    }
                CompletableFuture
                    .anyOf(
                        CompletableFuture.allOf(*waits.toTypedArray()),
                        *caseIds.map { sessions.getValue(it).cancellation }.toTypedArray(),
                    ).thenApply { null }
            }

        /** Read before finish removes this registration; a graceful stop is not a failure. */
        fun isStopped(): Boolean = synchronized(this@RunCostService) {
            caseIds.any { sessions[it]?.stopped == true }
        }

        /** Keep the transfer from live usage to persisted usage atomic for readers. */
        fun finish(persist: () -> Unit) =
            synchronized(this@RunCostService) {
                try {
                    persist()
                } finally {
                    val usage = accumulator.snapshot()
                    caseIds.forEach { id ->
                        sessions[id]?.let { session ->
                            if (session.live.remove(accumulator)) {
                                session.cost += usage.knownCost
                                session.unknown += usage.unknownCalls
                            }
                            if (session.live.isEmpty()) sessions.remove(id)
                        }
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
        // Prepare the entire lineage before publishing any registration: a later read can fail.
        val runSessions = lineage.map { case ->
            sessions[case.id] ?: newSession(case, thresholds.getValue(case.id), allowHistoryFailure)
        }
        runSessions.forEach { session ->
            sessions[session.caseId] = session
            session.live.add(accumulator)
        }
        return Registration(accumulator, lineage.map { it.id }).also { accumulator.beforeCall = it::beforeCall }
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
        cases.save(case.copy(runCostThreshold = next))
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
    }

    /** Waiting on a descendant's human confirmation also pauses a delegation's deadline. */
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
