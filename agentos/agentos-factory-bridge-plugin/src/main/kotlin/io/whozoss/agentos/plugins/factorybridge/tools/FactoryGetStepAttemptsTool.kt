package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Phase 6 read-only Workstream Agent tool: bounded list of the durable
 * execution attempts of one workflow step (state, worker/agentName, case,
 * timestamps, failure code, artifact/evidence refs).
 *
 * Pure read of `GET /api/factory/workflows/{workflowId}/attempts?stepId=…` —
 * the tool never recomputes state and never mutates. The output is derived
 * from the secret-free `DurableAgentAttemptDto`: no execution secret and no
 * raw LLM prose ever crosses the boundary. `namespaceId` is injected from the
 * trusted [ToolContext]; an unknown workflow or step degrades to `[]`.
 */
class FactoryGetStepAttemptsTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) : StandardTool<FactoryGetStepAttemptsTool.Input> {
    data class Input(val workflowId: String, val stepId: String)

    override val name = "FACTORY_WORKSTREAM__get_step_attempts"
    override val description =
        "List the bounded, secret-free durable execution attempts of one Factory workflow step " +
            "(status, agentName, case, timestamps, failureCode, evidence refs). Read-only."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":""" +
            """{"workflowId":{"type":"string","maxLength":128},""" +
            """"stepId":{"type":"string","maxLength":128}},"required":["workflowId","stepId"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val workflowId = input?.workflowId
        if (workflowId == null || !FactoryReadSupport.WORKFLOW_ID.matches(workflowId)) {
            return FactoryReadSupport.failure("INVALID_WORKFLOW_ID", "workflowId is invalid.")
        }
        val stepId = input?.stepId
        if (stepId == null || !FactoryReadSupport.SAFE_ID.matches(stepId)) {
            return FactoryReadSupport.failure("INVALID_REQUEST", "stepId is invalid.")
        }
        val encodedWorkflow = FactoryReadSupport.encodePathSegment(workflowId)
        val request =
            Request
                .Builder()
                .url(
                    "${baseUrl.trimEnd('/')}/api/factory/workflows/$encodedWorkflow/attempts" +
                        "?namespaceId=${context.namespaceId}&stepId=${FactoryReadSupport.encodeQueryValue(stepId)}",
                )
                .get()
                .build()
        return FactoryReadSupport.executeGet(httpClient, request) { status, body -> parseResponse(status, body) }
    }

    internal fun parseResponse(
        status: Int,
        body: String?,
    ): ToolExecutionResult {
        if (status !in 200..299) {
            return FactoryReadSupport.errorResult(objectMapper, body, "Factory rejected the step attempts read.")
        }
        val root =
            FactoryReadSupport.parseJson(objectMapper, body)
                ?: return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        val data = root.path("data")
        if (!data.isArray) {
            return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        val attempts = data.take(MAX_ATTEMPTS).map { boundedAttempt(it) }
        return ToolExecutionResult.success(
            objectMapper.writeValueAsString(attempts),
            metadata = mapOf("count" to attempts.size, "truncated" to (data.size() > attempts.size)),
        )
    }

    internal companion object {
        const val MAX_ATTEMPTS = 50

        /** Bounded, secret-free projection of one `DurableAgentAttemptDto`. */
        fun boundedAttempt(attempt: JsonNode): Map<String, Any?> =
            mapOf(
                "attemptId" to attempt.path("attemptId").asText(),
                "stepId" to attempt.path("stepId").asText(),
                "attemptNumber" to attempt.path("attemptNumber").takeIf(JsonNode::isIntegralNumber)?.asInt(),
                "agentName" to attempt.path("agentName").asText(),
                "status" to attempt.path("status").asText(),
                "caseId" to attempt.path("caseId").asText(),
                "failureCode" to attempt.path("failureCode").takeIf { it.isTextual }?.asText(),
                "resultEvidenceId" to attempt.path("resultEvidenceId").takeIf { it.isTextual }?.asText(),
                "revision" to attempt.path("revision").takeIf(JsonNode::isIntegralNumber)?.asInt(),
                "createdAt" to attempt.path("createdAt").takeIf { it.isTextual }?.asText(),
                "startedAt" to attempt.path("startedAt").takeIf { it.isTextual }?.asText(),
                "completedAt" to attempt.path("completedAt").takeIf { it.isTextual }?.asText(),
            )
    }
}
