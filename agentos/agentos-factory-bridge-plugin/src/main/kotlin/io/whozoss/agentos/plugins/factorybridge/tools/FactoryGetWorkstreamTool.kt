package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Phase 6 read-only Workstream Agent tool: bounded aggregated projection of a
 * workstream (identity, active workflows, aggregated step states, pending human
 * decisions and the main blockers).
 *
 * Pure read of the Phase 5 `GET /api/factory/workstreams/{workstreamId}/projection`
 * endpoint — the tool never recomputes state and never mutates. The model
 * supplies only the `workstreamId` (path); `namespaceId` is injected from the
 * trusted [ToolContext]. An id outside the caller's trusted scope is rejected
 * by the Factory with 403 `WORKSTREAM_BOUNDARY_VIOLATION`, surfaced verbatim.
 */
class FactoryGetWorkstreamTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) : StandardTool<FactoryGetWorkstreamTool.Input> {
    data class Input(val workstreamId: String)

    override val name = "FACTORY_WORKSTREAM__get_workstream"
    override val description =
        "Read the bounded aggregated projection of a Factory workstream: identity and revision, active workflows, " +
            "aggregated step states, pending human decisions and the main blockers. Read-only."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workstreamId":{"type":"string","maxLength":128}},"required":["workstreamId"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val workstreamId = input?.workstreamId
        if (workstreamId == null || !FactoryReadSupport.SAFE_ID.matches(workstreamId)) {
            return FactoryReadSupport.failure("INVALID_WORKSTREAM_SLUG", "workstreamId is invalid.")
        }
        val encoded = FactoryReadSupport.encodePathSegment(workstreamId)
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workstreams/$encoded/projection?namespaceId=${context.namespaceId}")
                .get()
                .build()
        return FactoryReadSupport.executeGet(httpClient, request) { status, body -> parseResponse(status, body) }
    }

    internal fun parseResponse(
        status: Int,
        body: String?,
    ): ToolExecutionResult {
        if (status !in 200..299) {
            return FactoryReadSupport.errorResult(objectMapper, body, "Factory rejected the workstream read.")
        }
        // Phase 5 projection endpoint: the RAW WorkstreamProjectionResponse body
        // (NOT the `{ "data": ... }` envelope used by the workflow endpoints).
        val root =
            FactoryReadSupport.parseJson(objectMapper, body)
                ?: return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        if (!root.path("workstreamId").isTextual || !root.path("workstreamRevision").isTextual) {
            return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        val activeWorkflows = root.path("activeWorkflows")
        val steps = root.path("steps")
        val humanActions = root.path("humanActions")
        val failedOracles = root.path("failedOracles")
        // Bounded projection: every section is already capped server-side; the
        // per-section `count`/`truncated` markers are carried through verbatim.
        val output =
            mapOf(
                "workstreamId" to root.path("workstreamId").asText(),
                "namespaceId" to root.path("namespaceId").takeIf { it.isTextual }?.asText(),
                "status" to root.path("status").asText(),
                "workstreamRevision" to root.path("workstreamRevision").asText(),
                "activeWorkflows" to
                    mapOf(
                        "count" to activeWorkflows.path("count").asInt(0),
                        "truncated" to activeWorkflows.path("truncated").asBoolean(false),
                        "items" to activeWorkflows.path("items"),
                    ),
                "steps" to
                    mapOf(
                        "running" to steps.path("running").asInt(0),
                        "waitingHuman" to steps.path("waitingHuman").asInt(0),
                        "blocked" to steps.path("blocked").asInt(0),
                        "truncated" to steps.path("truncated").asBoolean(false),
                    ),
                "pendingHumanActions" to
                    mapOf(
                        "count" to humanActions.path("count").asInt(0),
                        "truncated" to humanActions.path("truncated").asBoolean(false),
                        "items" to humanActions.path("items"),
                    ),
                "mainBlockers" to
                    mapOf(
                        "blockedSteps" to steps.path("blocked").asInt(0),
                        "waitingHumanSteps" to steps.path("waitingHuman").asInt(0),
                        "pendingHumanActions" to humanActions.path("count").asInt(0),
                        "failedOracles" to failedOracles.path("count").asInt(0),
                        "boundaryViolations" to root.path("boundaryViolations").asInt(0),
                    ),
            )
        return ToolExecutionResult.success(
            objectMapper.writeValueAsString(output),
            metadata =
                mapOf(
                    "workstreamId" to root.path("workstreamId").asText(),
                    "workstreamRevision" to root.path("workstreamRevision").asText(),
                    "status" to root.path("status").asText(),
                ),
        )
    }
}
