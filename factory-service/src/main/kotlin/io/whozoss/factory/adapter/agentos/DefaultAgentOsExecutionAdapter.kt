package io.whozoss.factory.adapter.agentos

import java.util.concurrent.ConcurrentHashMap
import mu.KotlinLogging
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/** Thrown by [AgentOsExecutionAdapter.startTurn] when the case is not quiescent. */
class AgentOsCaseBusyException(caseId: String, status: String) : RuntimeException(
    "Case $caseId is in status $status; posting now would be silently abandoned.",
)

/**
 * Default Spring implementation of the AgentOS execution boundary.
 *
 * REST calls (`/api/cases`, `/api/cases/{id}/messages`,
 * `/api/case-events/by-parentId/{id}`, `/api/cases/{id}/kill`) mirror the
 * proven shapes of `HttpAgentOsProxyClient` (same bodies, same trusted
 * `X-External-User-Id` and `X-Factory-*` headers); SSE streaming uses the
 * JDK `HttpClient` through [AgentOsSseClient].
 *
 * Idempotency: a boundary record keyed by `attemptId` remembers the case it
 * created; a repeated [createOrRecoverExecution] with the same `attemptId`
 * recovers instead of re-creating. Phase-1 the record is process-local —
 * losing it fails safe (a new case is created) and never produces a verdict
 * by silence.
 *
 * Per-turn baseline: [startTurn] captures the durable high-water mark of the
 * case **before** posting the turn, so the replayed history of a reused case
 * (an old `IDLE`, an old `AgentFinishedEvent`) is fenced out of the new
 * turn's observation and reconciliation — a previous turn can never close the
 * current one prematurely. Multi-turn safe: every turn captures its own
 * baseline, keyed by `(caseId, attemptId)`.
 *
 * Active-case tracking: every created/recovered case is registered in the
 * [ActiveCaseRegistry] (enumerable, process-wide) so the graceful shutdown
 * can interrupt/kill each of them — see [AgentOsCaseShutdownHook].
 */
class DefaultAgentOsExecutionAdapter(
    builder: RestClient.Builder,
    private val baseUrl: String,
    private val bindingSecret: String? = null,
    private val sseClientFactory: (baseUrl: String) -> AgentOsSseClient = { AgentOsSseClient(it) },
    private val checkpointStore: HighWaterMarkStore = HighWaterMarkStore(),
    private val registry: ActiveCaseRegistry = ActiveCaseRegistry(),
) : AgentOsExecutionAdapter {

    private val logger = KotlinLogging.logger {}

    private val client: RestClient = builder.baseUrl(baseUrl).build()

    /** attemptId → the execution it created (idempotency record). */
    private val executions = ConcurrentHashMap<String, ExecutionRecord>()

    /** caseId → caller-initiated interruption reason (intent for verdict mapping). */
    private val interruptReasons = ConcurrentHashMap<String, String>()

    private data class ExecutionRecord(
        val caseId: String,
        val namespaceId: String?,
        val externalUserId: String?,
        val capabilityToken: String?,
        val agentName: String? = null,
        val runtimeId: String? = null,
        val environmentRef: String? = null,
        val environmentRevision: Int? = null,
    ) {
        fun toBinding(attemptId: String): TrustedCaseBinding = TrustedCaseBinding(
            caseId = caseId,
            namespaceId = namespaceId,
            attemptId = attemptId,
            runtimeId = runtimeId,
            capabilityToken = capabilityToken,
            agentName = agentName,
            environmentRef = environmentRef,
            environmentRevision = environmentRevision,
            externalUserId = externalUserId,
        )
    }

    // ---- AgentRuntimeAdapter contract (binding/token based) ----

    override fun createOrRecoverExecution(binding: TrustedCaseBinding, workflowId: String, stepId: String): CaseHandle {
        executions[binding.attemptId]?.let {
            registry.register(it.toBinding(binding.attemptId))
            return CaseHandle(it.caseId, it.namespaceId, recovered = true)
        }
        val body = LinkedHashMap<String, Any?>()
        body["namespaceId"] = binding.namespaceId
        body["title"] = "Factory session $workflowId · step $stepId"
        body["id"] = binding.caseId
        body["attemptId"] = binding.attemptId
        if (!binding.capabilityToken.isNullOrBlank()) body["capabilityToken"] = binding.capabilityToken
        val spec = client.post()
            .uri("/api/cases")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
        if (!binding.externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", binding.externalUserId)
        spec.header("X-Factory-Attempt-Id", binding.attemptId)
        if (!binding.capabilityToken.isNullOrBlank()) {
            val secret = bindingSecret?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Factory worker binding secret is required before creating a capability-bound AgentOS case")
            spec.header("X-Factory-Capability-Token", binding.capabilityToken)
            spec.header("X-Factory-Agent-Name", binding.agentName ?: "*")
            spec.header("X-Factory-Agentos-Secret", secret)
        }
        val response = spec.retrieve().body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
        val resolvedCaseId = (response?.get("id") as? String) ?: binding.caseId
        val record = ExecutionRecord(
            caseId = resolvedCaseId,
            namespaceId = binding.namespaceId,
            externalUserId = binding.externalUserId,
            capabilityToken = binding.capabilityToken,
            agentName = binding.agentName,
            runtimeId = binding.runtimeId,
            environmentRef = binding.environmentRef,
            environmentRevision = binding.environmentRevision,
        )
        val raced = executions.putIfAbsent(binding.attemptId, record)
        val effective = raced ?: record
        registry.register(effective.toBinding(binding.attemptId))
        return if (raced != null) {
            CaseHandle(raced.caseId, raced.namespaceId, recovered = true)
        } else {
            CaseHandle(resolvedCaseId, binding.namespaceId, recovered = false)
        }
    }

    override fun startTurn(binding: TrustedCaseBinding, persona: String, brief: String): TurnToken {
        val caseId = binding.caseId
        // Baseline FIRST: the durable high-water mark of everything the case
        // already did (older turns of a reused case) is captured BEFORE the
        // turn is posted, so no pre-existing IDLE/AgentFinishedEvent can ever
        // close this new turn prematurely.
        val durable = listEvents(caseId, binding.externalUserId)
            .filter { it.caseId == caseId && !it.isTransient() }
        val lastStatus = durable.lastOrNull { it.type == CaseEventView.CASE_STATUS_EVENT }?.status
        if (lastStatus != null && lastStatus !in CaseEventView.QUIESCENT_STATUSES) {
            throw AgentOsCaseBusyException(caseId, lastStatus)
        }
        val baseline = EventCheckpoint.baselineOf(durable)
        checkpointStore.setBaseline(caseId, binding.attemptId, baseline)
        registry.markBaseline(caseId, baseline)
        val spec = client.post()
            .uri("/api/cases/$caseId/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("content" to "@$persona $brief"))
        if (!binding.externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", binding.externalUserId)
        spec.header("X-Factory-Attempt-Id", binding.attemptId)
        if (!binding.capabilityToken.isNullOrBlank()) spec.header("X-Factory-Capability-Token", binding.capabilityToken)
        spec.retrieve().toBodilessEntity()
        registry.markState(caseId, ActiveCaseState.RUNNING)
        return TurnToken(caseId, binding.attemptId, baseline)
    }

    override fun observeTurn(
        turn: TurnToken,
        timeoutMs: Long,
        onIntermediateVerdict: (AgentOsExecutionVerdict.WaitingHuman) -> Unit,
        onAnswerObserved: (CaseEventView) -> Unit,
    ): AgentOsExecutionVerdict {
        val caseId = turn.caseId
        val record = executions[turn.attemptId] ?: executions.values.firstOrNull { it.caseId == caseId }
        val baseline = checkpointStore.baseline(caseId, turn.attemptId) ?: turn.baseline
        val checkpoint = checkpointStore.checkpointWithBaseline(caseId, turn.attemptId, baseline)
        val verdict = sseClientFactory(baseUrl).observe(
            caseId = caseId,
            timeoutMs = timeoutMs,
            externalUserId = record?.externalUserId,
            attemptId = turn.attemptId,
            capabilityToken = record?.capabilityToken,
            checkpoint = checkpoint,
            context = contextFor(caseId),
            reconcile = { reconcileResult(caseId, record?.externalUserId, baseline.takeIf { it.isPositioned() }) },
            onIntermediateVerdict = onIntermediateVerdict,
            onAnswerObserved = onAnswerObserved,
        )
        when (verdict) {
            is AgentOsExecutionVerdict.WaitingHuman -> registry.markState(caseId, ActiveCaseState.WAITING_HUMAN)
            // Undecided is not an end: the case stays tracked for
            // reconciliation/escalation and the graceful shutdown.
            is AgentOsExecutionVerdict.Indeterminate -> Unit
            else -> registry.markState(caseId, ActiveCaseState.TERMINATED)
        }
        return verdict
    }

    override fun reconcile(turn: TurnToken): AgentOsExecutionVerdict {
        val caseId = turn.caseId
        val record = executions[turn.attemptId] ?: executions.values.firstOrNull { it.caseId == caseId }
        val baseline = checkpointStore.baseline(caseId, turn.attemptId)
            ?: turn.baseline.takeIf { it.isPositioned() }
            ?: checkpointStore.latestBaseline(caseId)
        val result = try {
            reconcileResult(caseId, record?.externalUserId, baseline?.takeIf { it.isPositioned() })
        } catch (e: Exception) {
            // An unreachable runtime makes the case state unknowable: the
            // verdict stays honestly indeterminate — never Succeeded, never a
            // failure proof, never a conclusion from silence.
            logger.warn(e) { "AgentOS runtime unreachable while reconciling case $caseId" }
            return AgentOsExecutionVerdict.Indeterminate(
                VerdictDeriver.RUNTIME_UNREACHABLE,
                mapOf("caseId" to caseId, "cause" to (e.message ?: e.javaClass.simpleName)),
            )
        }
        return result.verdict ?: AgentOsExecutionVerdict.Indeterminate(
            VerdictDeriver.NOT_QUIESCENT,
            mapOf(
                "caseId" to caseId,
                "caseStatus" to result.events
                    .lastOrNull { it.type == CaseEventView.CASE_STATUS_EVENT }
                    ?.status,
            ),
        )
    }

    override fun persistedEvents(caseId: String): List<CaseEventView> {
        val record = executions.values.firstOrNull { it.caseId == caseId }
        return listEvents(caseId, record?.externalUserId)
            .filter { it.caseId == caseId && !it.isTransient() }
    }

    override fun interrupt(caseId: String, reason: String) {
        interruptReasons[caseId] = reason
        postKill(caseId)
    }

    override fun kill(caseId: String) {
        postKill(caseId)
        registry.markState(caseId, ActiveCaseState.TERMINATED)
    }

    /**
     * Close/seal: the AgentOS HTTP contract exposes no seal route today, so
     * closing is Factory-local bookkeeping only — the case leaves the active
     * registry. Best-effort, never throws.
     */
    override fun close(caseId: String) {
        logger.info { "Closing case $caseId (no AgentOS seal route; Factory-local deregistration)" }
        registry.deregister(caseId)
    }

    // ---- Historical signatures, adapted onto the binding/token contract ----

    override fun createOrRecoverExecution(
        namespaceId: String,
        workflowId: String,
        stepId: String,
        externalUserId: String?,
        attemptId: String,
        capabilityToken: String?,
        caseId: String,
    ): CaseHandle = createOrRecoverExecution(
        TrustedCaseBinding(
            caseId = caseId,
            namespaceId = namespaceId,
            attemptId = attemptId,
            capabilityToken = capabilityToken,
            externalUserId = externalUserId,
        ),
        workflowId = workflowId,
        stepId = stepId,
    )

    override fun startTurn(
        caseId: String,
        persona: String,
        brief: String,
        externalUserId: String?,
        attemptId: String,
        capabilityToken: String?,
    ) {
        startTurn(
            TrustedCaseBinding(
                caseId = caseId,
                namespaceId = executions[attemptId]?.namespaceId
                    ?: executions.values.firstOrNull { it.caseId == caseId }?.namespaceId,
                attemptId = attemptId,
                capabilityToken = capabilityToken,
                externalUserId = externalUserId,
            ),
            persona = persona,
            brief = brief,
        )
    }

    override fun observeTurn(
        caseId: String,
        attemptId: String,
        timeoutMs: Long,
    ): AgentOsExecutionVerdict = observeTurn(caseId, attemptId, timeoutMs, { }, { })

    override fun observeTurn(
        caseId: String,
        attemptId: String,
        timeoutMs: Long,
        onIntermediateVerdict: (AgentOsExecutionVerdict.WaitingHuman) -> Unit,
        onAnswerObserved: (CaseEventView) -> Unit,
    ): AgentOsExecutionVerdict = observeTurn(TurnToken.unbaselined(caseId, attemptId), timeoutMs, onIntermediateVerdict, onAnswerObserved)

    override fun reconcile(caseId: String): AgentOsExecutionVerdict =
        reconcile(TurnToken.unbaselined(caseId, attemptId = ""))

    @Deprecated("Authenticated answers must supply answeringUserId")
    override fun answerQuestion(caseId: String, questionEventId: String, answer: String, attemptId: String) {
        throw IllegalArgumentException("answeringUserId is required")
    }

    override fun answerQuestion(
        caseId: String,
        questionEventId: String,
        answer: String,
        attemptId: String,
        answeringUserId: String,
    ) {
        require(answer.isNotBlank()) { "answer must not be blank" }
        require(answer.length <= MAX_ANSWER_LENGTH) { "answer must contain at most $MAX_ANSWER_LENGTH characters" }
        val record = executions[attemptId] ?: executions.values.firstOrNull { it.caseId == caseId }
        val spec = client.post()
            .uri("/api/cases/$caseId/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("content" to answer, "answerToEventId" to questionEventId))
        require(answeringUserId.isNotBlank()) { "answeringUserId must not be blank" }
        spec.header("X-External-User-Id", answeringUserId)
        spec.header("X-Factory-Attempt-Id", attemptId)
        record?.capabilityToken?.takeIf { it.isNotBlank() }?.let { spec.header("X-Factory-Capability-Token", it) }
        spec.retrieve().toBodilessEntity()
    }

    /** Best-effort kill — mirrors `HttpAgentOsProxyClient.killQuietly`: never throws. */
    private fun postKill(caseId: String) {
        try {
            client.post().uri("/api/cases/$caseId/kill").retrieve().toBodilessEntity()
        } catch (_: Exception) {
            // ignored: the caller decides the verdict; the kill is best-effort
        }
    }

    /**
     * REST catch-up against `GET /api/case-events/by-parentId/{caseId}`: the
     * durable, ordered events plus the verdict they derive (null when the case
     * is not yet quiescent). When a per-turn [baseline] is known, events at or
     * before it (older turns of a reused case) are filtered out first.
     */
    private fun reconcileResult(
        caseId: String,
        externalUserId: String?,
        baseline: HighWaterMark? = null,
    ): AgentOsSseClient.ReconcileResult {
        val durable = if (externalUserId == null) {
            persistedEvents(caseId)
        } else {
            listEvents(caseId, externalUserId)
                .filter { it.caseId == caseId && !it.isTransient() }
        }
        val events = if (baseline == null) durable else durable.filter { !EventCheckpoint.covers(baseline, it) }
        return AgentOsSseClient.ReconcileResult(
            events = events,
            verdict = VerdictDeriver.derive(events, contextFor(caseId)),
        )
    }

    private fun contextFor(caseId: String): VerdictDeriver.DerivationContext = VerdictDeriver.DerivationContext(
        caseId = caseId,
        interruptRequested = interruptReasons.containsKey(caseId),
        interruptReason = interruptReasons[caseId],
    )

    private fun listEvents(caseId: String, externalUserId: String?): List<CaseEventView> {
        val spec = client.get().uri("/api/case-events/by-parentId/$caseId")
        if (!externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", externalUserId)
        val raw = spec.retrieve()
            .body(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {}) ?: emptyList()
        return raw.mapNotNull { CaseEventView.fromJson(it) }
    }

    private companion object {
        const val MAX_ANSWER_LENGTH = 2_000
    }
}
