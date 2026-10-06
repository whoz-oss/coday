package io.whozoss.factory.proxy

import mu.KotlinLogging
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * HTTP adapter for the AgentOS proxy.
 *
 * Port of `factory/dashboard/agentos-proxy.mjs` and (for the W8.3 agent turn)
 * `factory/src/adapters/agentos/agentos-http-client.ts` +
 * `agentos-runtime-observer.ts`. Propagates the trusted `X-External-User-Id`
 * header; an AgentOS 404 resolves to `null`, any other failure to
 * [AgentOsUnavailableException] (502).
 *
 * The agent-turn transport is HTTP only and reaches the real AgentOS routes:
 * `POST /api/cases` (create), `POST /api/cases/{id}/messages` (post the brief),
 * `GET /api/case-events/by-parentId/{id}` (poll to quiescence),
 * `POST /api/cases/{id}/kill` (best-effort budget kill).
 *
 * Quiescence is read from `CaseStatusEvent` (`status ∈ {IDLE, KILLED, ERROR}`),
 * exactly like the Node observer: the message POST is asynchronous, so the turn
 * first waits for `RUNNING`, then waits for quiescence *after the last RUNNING*
 * (an agent can chain several turns without new user input — F7).
 */
class HttpAgentOsProxyClient(
    builder: RestClient.Builder,
    baseUrl: String,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val startTimeoutMs: Long = DEFAULT_START_TIMEOUT_MS,
    private val workTimeoutMs: Long = DEFAULT_WORK_TIMEOUT_MS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
) : AgentOsProxyClient {

    private val client: RestClient = builder.baseUrl(baseUrl).build()

    private val logger = KotlinLogging.logger {}

    @Suppress("UNCHECKED_CAST")
    private fun <T> relay(path: String, namespaceUserId: String?, type: ParameterizedTypeReference<T>): T {
        try {
            val spec = client.get().uri(path)
            if (!namespaceUserId.isNullOrBlank()) spec.header("X-External-User-Id", namespaceUserId)
            return spec.retrieve().body(type) as T
        } catch (error: RestClientResponseException) {
            if (error.statusCode == HttpStatusCode.valueOf(404)) throw AgentOsNotFound()
            throw AgentOsUnavailableException("AgentOS ${error.statusCode.value()}", error)
        } catch (error: AgentOsNotFound) {
            throw error
        } catch (error: Exception) {
            throw AgentOsUnavailableException(error.message ?: error.toString(), error)
        }
    }

    private class AgentOsNotFound : RuntimeException()

    override fun fetchAgents(namespaceId: String, externalUserId: String?): Any? =
        relay("/api/agent-configs/by-parentId/$namespaceId", externalUserId, object : ParameterizedTypeReference<Any>() {})

    override fun fetchNamespaces(externalUserId: String?): Any? =
        relay("/api/namespaces", externalUserId, object : ParameterizedTypeReference<Any>() {})

    override fun fetchNamespace(namespaceId: String, externalUserId: String?): Map<String, Any?>? =
        try {
            relay(
                "/api/namespaces/$namespaceId",
                externalUserId,
                object : ParameterizedTypeReference<Map<String, Any?>>() {},
            )
        } catch (_: AgentOsNotFound) {
            null
        }

    override fun fetchCaseEvents(caseId: String, externalUserId: String?): Any? =
        relay("/api/case-events/by-parentId/$caseId", externalUserId, object : ParameterizedTypeReference<Any>() {})

    /**
     * Run-cost read (W-metrics). Unlike the other relays this NEVER throws: an
     * unknown case (404), an unreachable AgentOS or any error degrades to null
     * so a caller like the workflow metrics endpoint still answers.
     */
    override fun getRunCost(caseId: String, externalUserId: String?): RunCostDto? =
        try {
            val raw: Map<String, Any?>? = relay(
                "/api/cases/$caseId/run-cost",
                externalUserId,
                object : ParameterizedTypeReference<Map<String, Any?>>() {},
            )
            if (raw == null) {
                null
            } else {
                RunCostDto(
                    caseId = (raw["caseId"] as? String) ?: caseId,
                    cost = (raw["cost"] as? Number)?.toDouble() ?: 0.0,
                    unknownCostCount = (raw["unknownCostCount"] as? Number)?.toLong() ?: 0L,
                    runCostThreshold = (raw["runCostThreshold"] as? Number)?.toDouble(),
                    paused = raw["paused"] as? Boolean ?: false,
                    active = raw["active"] as? Boolean ?: false,
                    liveTokens = (raw["liveTokens"] as? Number)?.toLong() ?: 0L,
                    pausedCaseIds = (raw["pausedCases"] as? List<*>)
                        ?.mapNotNull { (it as? Map<*, *>)?.get("caseId") as? String }
                        ?: emptyList(),
                )
            }
        } catch (_: AgentOsNotFound) {
            null
        } catch (error: Exception) {
            // AgentOsUnavailableException or any transport failure: degrade, do
            // not propagate — /metrics must still answer 200.
            logger.warn(error) { "run-cost unavailable for case $caseId; degrading" }
            null
        }

    /**
     * Cost-control command relay. AgentOS owns the state machine; this adapter
     * only forwards the trusted external identity and the optional threshold.
     *
     * A disabled usage-tracking surface (AgentOS 503) is surfaced as a
     * [UsageTrackingUnavailableException] (503) so the caller can degrade
     * cleanly; any other transport/HTTP failure is an [AgentOsUnavailableException]
     * (502) which the cost-control service maps to a 503 as well.
     */
    override fun continueRunCost(caseId: String, expectedThreshold: Double?, externalUserId: String?): Boolean =
        postRunCostCommand("/api/cases/$caseId/run-cost/continue", externalUserId, expectedThreshold)

    override fun stopRunCost(caseId: String, externalUserId: String?): Boolean =
        postRunCostCommand("/api/cases/$caseId/run-cost/stop", externalUserId, null)

    private fun postRunCostCommand(path: String, externalUserId: String?, expectedThreshold: Double?): Boolean {
        val spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
        if (!externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", externalUserId)
        if (expectedThreshold != null) spec.body(mapOf("expectedThreshold" to expectedThreshold))
        return try {
            spec.retrieve().toBodilessEntity()
            true
        } catch (error: RestClientResponseException) {
            if (error.statusCode.value() == 503) {
                throw UsageTrackingUnavailableException(
                    agentOsMessage(error.responseBodyAsString) ?: "Usage tracking is disabled",
                    error,
                )
            }
            throw AgentOsUnavailableException("AgentOS ${error.statusCode.value()} on $path", error)
        } catch (error: Exception) {
            throw AgentOsUnavailableException(error.message ?: error.toString(), error)
        }
    }

    /** Best-effort extraction of the `message` field of an AgentOS error body. */
    private fun agentOsMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            org.springframework.boot.json.JsonParserFactory.getJsonParser().parseMap(body)["message"] as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    override fun resolveRepoRoot(namespaceId: String, externalUserId: String?): String? {
        val namespace = fetchNamespace(namespaceId, externalUserId) ?: return null
        val configPath = namespace["configPath"] as? String
        if (configPath.isNullOrBlank()) return null
        val trimmed = configPath.replace(Regex("/+$"), "")
        val parent = java.nio.file.Path.of(trimmed).parent ?: return null
        return parent.toString()
    }

    override fun resolveRunStoreRoot(namespaceId: String, externalUserId: String?): String? {
        val repoRoot = resolveRepoRoot(namespaceId, externalUserId) ?: return null
        return java.nio.file.Path.of(repoRoot, "forge", "factory-runs").toString()
    }

    // ------------------------------------------------------------------
    // Agent turn (W8.3)
    // ------------------------------------------------------------------

    override fun executeAgentTurn(
        namespaceId: String,
        persona: String,
        stepId: String,
        workflowId: String,
        brief: String?,
        externalUserId: String?,
        attemptId: String?,
        capabilityToken: String?,
        caseId: String?,
    ): AgentTurnExecutionResult {
        val resolvedCaseId = try {
            val created = createCase(
                namespaceId = namespaceId,
                title = "Factory session $workflowId · step $stepId",
                externalUserId = externalUserId,
                caseId = caseId,
                attemptId = attemptId,
                capabilityToken = capabilityToken,
            )
            created ?: return AgentTurnExecutionResult.Failed(
                "AGENTOS_CASE_CREATION",
                "AgentOS case creation returned an empty body.",
            )
        } catch (error: Exception) {
            return AgentTurnExecutionResult.Failed("AGENTOS_UNAVAILABLE", error.message ?: error.toString())
        }
        return try {
            val baselineEvents = try {
                listEvents(resolvedCaseId, externalUserId)
            } catch (_: Exception) {
                emptyList()
            }
            val baselineId = baselineEvents.lastOrNull()?.get("id") as? String
            val busyStatus = lastStatusOf(baselineEvents)
            if (busyStatus != null && busyStatus !in QUIESCENT_STATUSES) {
                return AgentTurnExecutionResult.Failed(
                    "AGENT_CASE_BUSY",
                    "Case $resolvedCaseId is in status $busyStatus; posting now would be silently abandoned.",
                )
            }
            postMessage(
                caseId = resolvedCaseId,
                content = "@$persona ${brief ?: defaultBrief(stepId, workflowId)}",
                externalUserId = externalUserId,
                attemptId = attemptId,
                capabilityToken = capabilityToken,
            )
            awaitQuiescence(resolvedCaseId, baselineId, externalUserId)
        } catch (error: RestClientResponseException) {
            AgentTurnExecutionResult.Failed(
                "AGENTOS_HTTP_${error.statusCode.value()}",
                "AgentOS answered HTTP ${error.statusCode.value()} during the agent turn.",
            )
        } catch (error: Exception) {
            AgentTurnExecutionResult.Failed("AGENTOS_UNAVAILABLE", error.message ?: error.toString())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createCase(
        namespaceId: String,
        title: String,
        externalUserId: String?,
        caseId: String?,
        attemptId: String?,
        capabilityToken: String?,
    ): String? {
        // The Factory supplies the case id and the result-capability facts so the
        // AgentOS host can bind them to the case it creates. Unknown fields are
        // ignored by the AgentOS `Case` DTO; the headers are the authoritative
        // channel for a host that reads them out-of-band.
        val body = LinkedHashMap<String, Any?>()
        body["namespaceId"] = namespaceId
        body["title"] = title
        if (!caseId.isNullOrBlank()) body["id"] = caseId
        if (!attemptId.isNullOrBlank()) body["attemptId"] = attemptId
        if (!capabilityToken.isNullOrBlank()) body["capabilityToken"] = capabilityToken
        val spec = client.post()
            .uri("/api/cases")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
        if (!externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", externalUserId)
        if (!attemptId.isNullOrBlank()) spec.header("X-Factory-Attempt-Id", attemptId)
        if (!capabilityToken.isNullOrBlank()) spec.header("X-Factory-Capability-Token", capabilityToken)
        val response = spec.retrieve().body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
        return (response?.get("id") as? String) ?: caseId
    }

    private fun postMessage(
        caseId: String,
        content: String,
        externalUserId: String?,
        attemptId: String? = null,
        capabilityToken: String? = null,
    ) {
        val spec = client.post()
            .uri("/api/cases/$caseId/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("content" to content))
        if (!externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", externalUserId)
        if (!attemptId.isNullOrBlank()) spec.header("X-Factory-Attempt-Id", attemptId)
        if (!capabilityToken.isNullOrBlank()) spec.header("X-Factory-Capability-Token", capabilityToken)
        spec.retrieve().toBodilessEntity()
    }

    private fun killQuietly(caseId: String, externalUserId: String?) {
        try {
            val spec = client.post().uri("/api/cases/$caseId/kill")
            if (!externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", externalUserId)
            spec.retrieve().toBodilessEntity()
        } catch (_: Exception) {
            // ignored: the failure verdict takes precedence over the kill succeeding
        }
    }

    private fun listEvents(caseId: String, externalUserId: String?): List<Map<String, Any?>> {
        val spec = client.get().uri("/api/case-events/by-parentId/$caseId")
        if (!externalUserId.isNullOrBlank()) spec.header("X-External-User-Id", externalUserId)
        return spec.retrieve()
            .body(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {}) ?: emptyList()
    }

    private fun awaitQuiescence(
        caseId: String,
        baselineId: String?,
        externalUserId: String?,
    ): AgentTurnExecutionResult {
        val startDeadline = now() + startTimeoutMs
        var started = false
        var workDeadline = 0L
        var runningIndex = -1
        while (true) {
            sleep(pollIntervalMs)
            val all = try {
                listEvents(caseId, externalUserId)
            } catch (error: Exception) {
                return AgentTurnExecutionResult.Failed("AGENTOS_UNAVAILABLE", error.message ?: error.toString())
            }
            val turn = sliceAfterId(all, baselineId)
            if (!started) {
                val running = findStatus(turn, listOf(STATUS_RUNNING))
                if (running != null) {
                    runningIndex = running.index
                    workDeadline = now() + workTimeoutMs
                    started = true
                } else if (now() > startDeadline) {
                    killQuietly(caseId, externalUserId)
                    return AgentTurnExecutionResult.Failed(
                        "AGENT_TURN_START_TIMEOUT",
                        "Case $caseId did not reach RUNNING within ${startTimeoutMs}ms.",
                        mapOf("caseId" to caseId, "caseStatus" to lastStatusOf(all)),
                    )
                } else {
                    continue
                }
            }
            // F7 — advance to the most recent RUNNING, then look for quiescence after it.
            val lastRunning = findLastStatus(turn, listOf(STATUS_RUNNING))
            if (lastRunning != null && lastRunning.index > runningIndex) runningIndex = lastRunning.index
            val quiescent = findStatus(turn, QUIESCENT_STATUSES, runningIndex + 1)
            if (quiescent != null) return verdict(caseId, turn, quiescent.event)
            if (now() > workDeadline) {
                killQuietly(caseId, externalUserId)
                return AgentTurnExecutionResult.Failed(
                    "AGENT_TURN_WORK_TIMEOUT",
                    "Case $caseId did not reach quiescence within ${workTimeoutMs}ms.",
                    mapOf("caseId" to caseId, "caseStatus" to lastStatusOf(all)),
                )
            }
        }
    }

    private fun verdict(caseId: String, events: List<Map<String, Any?>>, quiescent: Map<String, Any?>): AgentTurnExecutionResult {
        val status = quiescent["status"] as? String ?: ""
        val facts = turnFacts(caseId, events, status)
        return when (status) {
            STATUS_KILLED -> AgentTurnExecutionResult.Failed("AGENT_CASE_KILLED", "Case $caseId was killed.", facts)
            STATUS_ERROR -> AgentTurnExecutionResult.Failed("AGENT_CASE_ERROR", "Case $caseId ended in ERROR.", facts)
            STATUS_IDLE -> {
                val pendingQuestion = lastUnansweredQuestion(events)
                if (pendingQuestion != null) {
                    AgentTurnExecutionResult.Failed(
                        "AGENT_TURN_PENDING_QUESTION",
                        "Case $caseId is idle but waiting for a human answer.",
                        facts + ("question" to pendingQuestion),
                    )
                } else {
                    // Legacy troubleshooting path only: even here a raw agent
                    // message is NOT an authoritative success. Only a structured
                    // result submitted through the capability channel may make
                    // an agent step succeed — never a success from free text.
                    val summary = lastAgentMessage(events)
                    AgentTurnExecutionResult.Failed(
                        AGENT_NO_STRUCTURED_RESULT,
                        "Case $caseId reached IDLE without a structured capability-backed result.",
                        facts + ("summary" to summary),
                    )
                }
            }
            else -> AgentTurnExecutionResult.Failed(
                "AGENT_CASE_STATUS",
                "Case $caseId reached unexpected status '$status'.",
                facts,
            )
        }
    }

    private fun turnFacts(caseId: String, events: List<Map<String, Any?>>, caseStatus: String): Map<String, Any?> = mapOf(
        "caseId" to caseId,
        "caseStatus" to caseStatus,
        "agentTurns" to events.count { it["type"] == "AgentFinishedEvent" },
        "toolCalls" to events.count { it["type"] == "ToolResponseEvent" },
        // AgentOS events do not carry a change list; the durable modified-file
        // facts come from the worker's step-result binding (W8.4 projection).
        "modifiedFiles" to emptyList<String>(),
    )

    private fun lastStatusOf(events: List<Map<String, Any?>>): String? =
        findLastStatus(events, QUIESCENT_STATUSES + STATUS_RUNNING)?.event?.get("status") as? String

    /** Events that follow [baselineId]; missing id → the whole (permissive) history. */
    private fun sliceAfterId(events: List<Map<String, Any?>>, baselineId: String?): List<Map<String, Any?>> {
        if (baselineId == null) return events
        val index = events.indexOfFirst { it["id"] == baselineId }
        return if (index < 0) events else events.subList(index + 1, events.size)
    }

    private fun defaultBrief(stepId: String, workflowId: String): String =
        "Execute the Factory session step '$stepId' of workflow '$workflowId'."

    private data class StatusHit(val event: Map<String, Any?>, val index: Int)

    private fun findStatus(
        events: List<Map<String, Any?>>,
        statuses: List<String>,
        fromIndex: Int = 0,
    ): StatusHit? {
        for (index in fromIndex until events.size) {
            val event = events[index]
            if (event["type"] == CASE_STATUS_EVENT && event["status"] in statuses) return StatusHit(event, index)
        }
        return null
    }

    private fun findLastStatus(events: List<Map<String, Any?>>, statuses: List<String>): StatusHit? {
        for (index in events.indices.reversed()) {
            val event = events[index]
            if (event["type"] == CASE_STATUS_EVENT && event["status"] in statuses) return StatusHit(event, index)
        }
        return null
    }

    private fun lastAgentMessage(events: List<Map<String, Any?>>): String {
        val message = events.lastOrNull { event ->
            event["type"] == "MessageEvent" && (event["actor"] as? Map<*, *>)?.get("role") == "AGENT"
        } ?: return ""
        return messageContent(message["content"])
    }

    private fun messageContent(content: Any?): String = when (content) {
        is String -> content
        is List<*> -> content.joinToString("") { part ->
            ((part as? Map<*, *>)?.get("content") as? String) ?: ""
        }
        else -> ""
    }

    private fun lastUnansweredQuestion(events: List<Map<String, Any?>>): String? {
        val answered = events
            .filter { it["type"] == "AnswerEvent" }
            .mapNotNull { it["questionId"] as? String }
            .toSet()
        return events
            .filter { it["type"] == "QuestionEvent" && (it["id"] as? String) !in answered }
            .lastOrNull()
            ?.let { (it["question"] as? String) ?: "The agent is waiting for a human answer." }
    }

    companion object {
        const val CASE_STATUS_EVENT = "CaseStatusEvent"

        /**
         * Failure code of an IDLE turn with no pending question and no
         * structured capability-backed result. Same vocabulary as
         * [io.whozoss.factory.adapter.agentos.VerdictDeriver.AGENT_NO_STRUCTURED_RESULT].
         */
        const val AGENT_NO_STRUCTURED_RESULT = "AGENT_NO_STRUCTURED_RESULT"
        val QUIESCENT_STATUSES: List<String> = listOf("IDLE", "KILLED", "ERROR")

        private const val STATUS_RUNNING = "RUNNING"
        private const val STATUS_IDLE = "IDLE"
        private const val STATUS_KILLED = "KILLED"
        private const val STATUS_ERROR = "ERROR"

        const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        const val DEFAULT_START_TIMEOUT_MS = 30_000L
        const val DEFAULT_WORK_TIMEOUT_MS = 10L * 60L * 1_000L
    }
}
