package io.whozoss.agentos.usage

import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.usageRecord.PausedCostDto
import io.whozoss.agentos.sdk.api.usageRecord.RunCostDto
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
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
                        val state = snapshot(session)
                        val threshold = session.threshold
                        if (threshold != null && state.cost >= threshold) {
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
        val lineage = ancestors(caseId)
        lineage.forEach { case ->
            sessions.getOrPut(case.id) { newSession(case) }.live.add(accumulator)
        }
        return Registration(accumulator, lineage.map { it.id }).also { accumulator.beforeCall = it::beforeCall }
    }

    @Synchronized
    fun state(caseId: UUID): RunCostDto {
        val state = snapshot(sessions[caseId] ?: newSession(getCase(caseId)))
        val paused =
            sessions.values
                .filter { caseId in it.ancestorIds && it.confirmation != null && !it.stopped }
                .map { PausedCostDto(it.caseId, snapshot(it).cost, it.threshold!!) }
        return state.copy(pausedCases = paused)
    }

    /** Optimistic precondition prevents a retried/double-clicked request doubling twice. */
    @Synchronized
    fun continueRun(
        caseId: UUID,
        expectedThreshold: Double,
    ): RunCostDto {
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
        val ancestors = sessions[caseId]?.ancestorIds.orEmpty()
        return sessions.values.any { session ->
            session.confirmation != null && !session.stopped &&
                (session.caseId in ancestors || caseId in session.ancestorIds)
        }
    }

    private fun snapshot(session: Session): RunCostDto {
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

    private fun newSession(case: Case): Session {
        val since =
            events
                .findByParent(case.id)
                .filterIsInstance<MessageEvent>()
                .lastOrNull { it.actor.role == ActorRole.USER }
                ?.timestamp ?: case.metadata.created
        val total = records.sumCostByCaseTreeSince(case.id, since)
        return Session(
            case.id,
            since,
            ancestors(case.id).map { it.id }.toSet(),
            total?.cost ?: 0.0,
            total?.unknownCostCount ?: 0,
            case.runCostThreshold ?: namespaces.resolveRunCostThreshold(case.namespaceId),
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
}
