package io.whozoss.factory.adapter.agentos

import java.util.concurrent.ConcurrentHashMap
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
 */
class DefaultAgentOsExecutionAdapter(
    builder: RestClient.Builder,
    private val baseUrl: String,
    private val sseClientFactory: (baseUrl: String) -> AgentOsSseClient = { AgentOsSseClient(it) },
    private val checkpointStore: HighWaterMarkStore = HighWaterMarkStore(),
) : AgentOsExecutionAdapter {

    private val client: RestClient = builder.baseUrl(baseUrl).build()

    /** attemptId → the execution it created (idempotency record). */
    private val executions = ConcurrentHashMap<String, ExecutionRecord>()

    /** caseId → caller-initiated interruption reason (intent for verdict mapping). */
    private val interruptReasons = ConcurrentHashMap<String, String>()

    private data class ExecutionRecord(
        val caseId: String,
        val namespaceId: String,
        val externalUserId: String?,
        val capabilityToken: String?,
    )

    override fun createOrRecoverExecution(
        namespaceId: String,
        workflowId: String,
        stepId: String,
        externalUserId: String?,
        attemptId: String,
        capabilityToken: String?,
        caseId: String,
    ): CaseHandle {
        executions[attemptId]?.let { return CaseHandle(it.caseId, it.namespaceId, recovered = true) }
        val body = LinkedHashMap<String, Any?>()
        body["namespaceId"] = namespaceId
        body["title"] = "Factory session $workflowId · step $stepId"
        body["id"] = caseId
        body["attemptId"] = attemptId
        if (!capabilityToken.isNullOrBlank()) body["capabilityToken"] = capabilityToken
        val spec = client.post()
            .uri("/api/cases")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
        if (!externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", externalUserId)
        spec.header("X-Factory-Attempt-Id", attemptId)
        if (!capabilityToken.isNullOrBlank()) spec.header("X-Factory-Capability-Token", capabilityToken)
        val response = spec.retrieve().body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
        val resolvedCaseId = (response?.get("id") as? String) ?: caseId
        val record = ExecutionRecord(resolvedCaseId, namespaceId, externalUserId, capabilityToken)
        val raced = executions.putIfAbsent(attemptId, record)
        return if (raced != null) {
            CaseHandle(raced.caseId, raced.namespaceId, recovered = true)
        } else {
            CaseHandle(resolvedCaseId, namespaceId, recovered = false)
        }
    }

    override fun startTurn(
        caseId: String,
        persona: String,
        brief: String,
        externalUserId: String?,
        attemptId: String,
        capabilityToken: String?,
    ) {
        val lastStatus = listEvents(caseId, externalUserId)
            .lastOrNull { it.type == CaseEventView.CASE_STATUS_EVENT }
            ?.status
        if (lastStatus != null && lastStatus !in CaseEventView.QUIESCENT_STATUSES) {
            throw AgentOsCaseBusyException(caseId, lastStatus)
        }
        val spec = client.post()
            .uri("/api/cases/$caseId/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("content" to "@$persona $brief"))
        if (!externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", externalUserId)
        spec.header("X-Factory-Attempt-Id", attemptId)
        if (!capabilityToken.isNullOrBlank()) spec.header("X-Factory-Capability-Token", capabilityToken)
        spec.retrieve().toBodilessEntity()
    }

    override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict {
        val record = executions[attemptId] ?: executions.values.firstOrNull { it.caseId == caseId }
        val checkpoint = checkpointStore.checkpoint(caseId, attemptId)
        return sseClientFactory(baseUrl).observe(
            caseId = caseId,
            timeoutMs = timeoutMs,
            externalUserId = record?.externalUserId,
            attemptId = attemptId,
            capabilityToken = record?.capabilityToken,
            checkpoint = checkpoint,
            context = contextFor(caseId),
            reconcile = { reconcileResult(it, record?.externalUserId) },
        )
    }

    override fun reconcile(caseId: String): AgentOsExecutionVerdict {
        val record = executions.values.firstOrNull { it.caseId == caseId }
        val result = reconcileResult(caseId, record?.externalUserId)
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

    override fun interrupt(caseId: String, reason: String) {
        interruptReasons[caseId] = reason
        postKill(caseId)
    }

    override fun kill(caseId: String) {
        postKill(caseId)
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
     * is not yet quiescent).
     */
    private fun reconcileResult(caseId: String, externalUserId: String?): AgentOsSseClient.ReconcileResult {
        val events = listEvents(caseId, externalUserId)
            .filter { it.caseId == caseId && !it.isTransient() }
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
}
