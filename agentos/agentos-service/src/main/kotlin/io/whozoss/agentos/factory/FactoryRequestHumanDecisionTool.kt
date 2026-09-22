package io.whozoss.agentos.factory

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.caseEvent.FactoryCheckpointRef
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class FactoryRequestHumanDecisionTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val runtimeId: String,
) : StandardTool<FactoryRequestHumanDecisionTool.Input> {
    data class Action(val id: String, val label: String, val requestedStatus: String)
    data class Input(
        val workflowId: String,
        val stepId: String,
        val expectedRevision: Long,
        val prompt: String,
        val actions: List<Action>,
        val idempotencyKey: String,
    )

    override val name = "FACTORY__request_human_decision"
    override val description = "Open a governed human checkpoint. This only requests a decision; it cannot approve or reject."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128},"stepId":{"type":"string","maxLength":128},"expectedRevision":{"type":"integer","minimum":1},"prompt":{"type":"string","minLength":1,"maxLength":2000},"actions":{"type":"array","minItems":2,"maxItems":2,"uniqueItems":true,"items":{"type":"object","additionalProperties":false,"properties":{"id":{"enum":["approve","reject"]},"label":{"type":"string","minLength":1,"maxLength":128},"requestedStatus":{"enum":["completed","failed"]}},"required":["id","label","requestedStatus"]}},"idempotencyKey":{"type":"string","minLength":1,"maxLength":128}},"required":["workflowId","stepId","expectedRevision","prompt","actions","idempotencyKey"]}"""

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        if (input == null || !validActions(input.actions)) {
            return failure("INVALID_INTERACTION", "A valid approve/reject human decision request is required.")
        }
        val caseIds = context.caseEvents.mapNotNull { it.caseId }.distinct()
        if (caseIds.size != 1) return failure("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val agent = context.agentName?.takeIf { it.isNotBlank() }
            ?: return failure("AGENT_CONTEXT_UNAVAILABLE", "A controlling agent is required.")
        val workflowId = URLEncoder.encode(input.workflowId, Charsets.UTF_8).replace("+", "%20")
        val body = objectMapper.writeValueAsString(
            mapOf(
                "stepId" to input.stepId,
                "expectedRevision" to input.expectedRevision,
                "kind" to "approval",
                "prompt" to input.prompt,
                "actions" to input.actions,
                "idempotencyKey" to input.idempotencyKey,
            ),
        )
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$workflowId/interactions")
            .header("x-factory-namespace-id", context.namespaceId.toString())
            .header("x-factory-runtime-id", runtimeId)
            .header("x-factory-agent-id", agent)
            .header("x-factory-case-id", caseIds.single().toString())
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    val result = parseResponse(response.code, responseBody)
                    if (result.success == true) {
                        // Extract authoritative interaction reference from the response.
                        val root = objectMapper.readTree(responseBody)
                        val interactionId = root.path("data").path("interaction").path("interactionId").asText("")
                        val interactionRevision = root.path("data").path("interaction").path("revision").asLong(-1L)
                        if (interactionId.isNotBlank() && interactionRevision >= 0) {
                            // Suspend the agent — throws AgentInterrupt.AwaitAnswer, never returns.
                            throwAwaitAnswer(input, context, interactionId, interactionRevision)
                        }
                    }
                    result
                }
            }
        } catch (e: AgentInterrupt) {
            throw e // propagate control-flow signal unchanged
        } catch (_: Exception) {
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }
    }

    internal fun parseResponse(status: Int, body: String?): ToolExecutionResult {
        val root = try {
            objectMapper.readTree(body)
        } catch (_: Exception) {
            return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        if (status !in 200..299) {
            return failure(
                root.path("error").path("code").asText("FACTORY_REQUEST_FAILED"),
                root.path("error").path("message").asText("Factory rejected the human checkpoint."),
            )
        }
        val data = root.path("data")
        if (!data.isObject) return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")

        // Extract the authoritative interaction reference from the Factory response.
        // These fields are server-set — the model never supplied them.
        val interactionId = data.path("interaction").path("interactionId").asText("")
        val interactionRevision = data.path("interaction").path("revision").asLong(-1L)
        if (interactionId.isBlank() || interactionRevision < 0) {
            return failure("MALFORMED_FACTORY_RESPONSE", "Factory response missing interaction id or revision.")
        }
        return ToolExecutionResult.success(objectMapper.writeValueAsString(data))
    }

    /**
     * Extract the Factory interaction reference from a successful open response and throw
     * [AgentInterrupt.AwaitAnswer] to suspend the agent until the user decides.
     *
     * Called by [execute] after a successful HTTP response. Throws, never returns.
     */
    internal fun throwAwaitAnswer(
        input: Input,
        context: ToolContext,
        interactionId: String,
        interactionRevision: Long,
    ): Nothing {
        throw AgentInterrupt.AwaitAnswer(
            question = input.prompt,
            options = input.actions.map { it.label },
            questionType = QuestionType.SINGLE_CHOICE,
            userId = context.userId,
            factoryCheckpoint = FactoryCheckpointRef(
                workflowId = input.workflowId,
                interactionId = interactionId,
                interactionRevision = interactionRevision,
            ),
        )
    }

    private fun validActions(actions: List<Action>): Boolean =
        actions.size == 2 &&
            actions.map { it.id }.toSet() == setOf("approve", "reject") &&
            actions.all {
                it.label.isNotBlank() && it.label.length <= 128 &&
                    ((it.id == "approve" && it.requestedStatus == "completed") ||
                        (it.id == "reject" && it.requestedStatus == "failed"))
            }

    private fun failure(code: String, message: String) =
        ToolExecutionResult.error(message, errorType = code, errorMessage = message)
}
