package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.plugins.factorybridge.FactoryAnswerAwaiter
import io.whozoss.agentos.plugins.factorybridge.FactoryAwaitAnswer
import io.whozoss.agentos.plugins.factorybridge.FactoryCheckpointRef
import io.whozoss.agentos.plugins.factorybridge.HostAgentInterruptAwaiter
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FactoryRequestHumanDecisionTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val runtimeId: String,
    /**
     * Open human-checkpoint interactions shared at plugin level, keyed by controlling case id.
     * The reference is registered here before the run suspends so [io.whozoss.agentos.plugins.factorybridge.FactoryAnswerInterceptor]
     * can submit the user's decision once the answer arrives — no Factory type ever crosses the host boundary.
     */
    private val pendingCheckpoints: MutableMap<UUID, FactoryCheckpointRef> = ConcurrentHashMap(),
    private val awaiter: FactoryAnswerAwaiter = HostAgentInterruptAwaiter,
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

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || !validActions(input.actions)) {
            return failure("INVALID_INTERACTION", "A valid approve/reject human decision request is required.")
        }
        val caseIds = context.caseEvents.map { it.caseId }.distinct()
        if (caseIds.size != 1) return failure("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val agent =
            context.agentName?.takeIf { it.isNotBlank() }
                ?: return failure("AGENT_CONTEXT_UNAVAILABLE", "A controlling agent is required.")
        val workflowId = URLEncoder.encode(input.workflowId, Charsets.UTF_8).replace("+", "%20")
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "stepId" to input.stepId,
                    "expectedRevision" to input.expectedRevision,
                    "kind" to "approval",
                    "prompt" to input.prompt,
                    "actions" to input.actions,
                    "idempotencyKey" to input.idempotencyKey,
                ),
            )
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$workflowId/interactions")
                .header("x-factory-namespace-id", context.namespaceId.toString())
                .header("x-factory-runtime-id", runtimeId)
                .header("x-factory-agent-id", agent)
                .header("x-factory-case-id", caseIds.single().toString())
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

        // The HTTP call must never swallow the suspension signal, so the interaction is
        // opened and parsed first, and the awaiter is invoked outside the try/catch.
        val opened = openInteraction(request, input.workflowId)
        val reference =
            when (opened) {
                is OpenOutcome.Error -> return failure(opened.code, opened.message)
                is OpenOutcome.Opened -> opened.reference
            }
        // Register the checkpoint in the plugin-level registry so the answer interceptor can
        // submit the user's decision once it arrives, then suspend the run. Throws — never returns.
        pendingCheckpoints[caseIds.single()] = reference
        awaiter.awaitAnswer(
            FactoryAwaitAnswer(
                question = input.prompt,
                options = input.actions.map { it.label },
                questionType = QuestionType.SINGLE_CHOICE,
                userId = context.userId,
            ),
        )
    }

    private suspend fun openInteraction(
        request: Request,
        workflowId: String,
    ): OpenOutcome =
        try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    val parseResult = parseResponse(response.code, responseBody)
                    if (parseResult.success != true) return@use OpenOutcome.Error(parseResult.errorType ?: "FACTORY_REQUEST_FAILED", parseResult.errorMessage ?: parseResult.output)
                    val root =
                        runCatching { objectMapper.readTree(responseBody) }
                            .getOrElse { return@use OpenOutcome.Error("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.") }
                    val data = root.path("data")
                    val interactionId = data.path("interaction").path("interactionId").asText("")
                    val interactionRevision = data.path("interaction").path("revision").asLong(-1L)
                    if (interactionId.isBlank() || interactionRevision < 0) {
                        return@use OpenOutcome.Error("MALFORMED_FACTORY_RESPONSE", "Factory response missing interaction id or revision.")
                    }
                    OpenOutcome.Opened(FactoryCheckpointRef(workflowId, interactionId, interactionRevision))
                }
            }
        } catch (_: Exception) {
            OpenOutcome.Error("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }

    internal fun parseResponse(
        status: Int,
        body: String?,
    ): ToolExecutionResult {
        val root =
            try {
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
        val interactionId = data.path("interaction").path("interactionId").asText("")
        val interactionRevision = data.path("interaction").path("revision").asLong(-1L)
        if (interactionId.isBlank() || interactionRevision < 0) {
            return failure("MALFORMED_FACTORY_RESPONSE", "Factory response missing interaction id or revision.")
        }
        return ToolExecutionResult.success(objectMapper.writeValueAsString(data))
    }

    private fun validActions(actions: List<Action>): Boolean =
        actions.size == 2 &&
            actions.map { it.id }.toSet() == setOf("approve", "reject") &&
            actions.all {
                it.label.isNotBlank() && it.label.length <= 128 &&
                    ((it.id == "approve" && it.requestedStatus == "completed") ||
                        (it.id == "reject" && it.requestedStatus == "failed"))
            }

    private fun failure(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)

    private sealed interface OpenOutcome {
        data class Opened(val reference: FactoryCheckpointRef) : OpenOutcome

        data class Error(val code: String, val message: String) : OpenOutcome
    }
}
