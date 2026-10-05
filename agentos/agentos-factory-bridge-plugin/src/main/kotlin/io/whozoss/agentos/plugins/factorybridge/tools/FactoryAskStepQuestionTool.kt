package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.plugins.factorybridge.FactoryCheckpointRef
import io.whozoss.agentos.plugins.factorybridge.FactoryStepResultBindingRegistry
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
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
import java.security.MessageDigest
import java.util.UUID

/**
 * `FACTORY_WORKER__ask_step_question` — the WORKER tool of the Phase 4
 * ask-step-question channel.
 *
 * ## Design decision (why a dedicated tool)
 * Asking a human question is NOT a terminal business verdict: it must neither
 * consume the single-use `PASS`/`FAIL` result capability nor terminalize the
 * attempt. The structured step-result contract therefore keeps exactly
 * `{PASS, FAIL}` and the question flows through this dedicated tool backed by
 * the dedicated `POST /api/factory/agent-step-questions` endpoint, mirroring
 * the clean split between [FactorySubmitStepResultTool] and
 * [FactoryRequestHumanDecisionTool]. It is a WORKER capability (granted only
 * alongside `submit_step_result`), never granted to the Workstream Agent.
 *
 * ## Durable, non-blocking semantics
 * The tool returns as soon as the Factory has durably recorded the question
 * and parked the attempt in `waiting_human` (HTTP 202). It never awaits the
 * human answer in memory (unlike the `FactoryAwaitAnswer` control-flow
 * signal): the answer arrives later through the governed human reply endpoint,
 * which supersedes the current attempt and resumes the step as a brand-new
 * attempt carrying the bounded resumption context.
 *
 * ## Identity and capability lifecycle
 * Attempt identity comes from the case-scoped result binding and the case /
 * agent / namespace identities from the trusted [ToolContext] — never from
 * model-authored arguments. The `contextHash` is computed by the tool from
 * those trusted inputs so a retried call collapses onto the same durable
 * interaction (idempotent re-ask). Because a question is not a result, the
 * binding is only ever `acquire`d + `release`d: it is NEVER `acknowledge`d
 * (consumed) and never `invalidate`d, so the result capability stays fully
 * available for the resumed attempt to submit its final `PASS`/`FAIL`.
 */
@Deprecated(
    "Legacy Factory-owned question channel; agents must use AgentOS standard queryUser",
    level = DeprecationLevel.WARNING,
)
class FactoryAskStepQuestionTool(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val mapper: ObjectMapper,
    private val bindings: FactoryStepResultBindingRegistry,
    private val registerQuestion: (UUID, FactoryCheckpointRef) -> Unit = { _, _ -> },
) : StandardTool<FactoryAskStepQuestionTool.Input> {
    data class Input(
        val prompt: String,
        val type: String = "FREE_TEXT",
        val options: List<String> = emptyList(),
        val recipientRole: String? = null,
    )

    override val name = "FACTORY_WORKER__ask_step_question"
    override val description =
        "Ask a durable human question for this Factory step attempt and park it until a human answers. " +
            "Attempt identity is injected by the runtime; the call returns as soon as the question is recorded."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"prompt":{"type":"string","minLength":1,"maxLength":2000},"type":{"enum":["FREE_TEXT","SINGLE_CHOICE","OPEN_CHOICE"]},"options":{"type":"array","maxItems":20,"items":{"type":"string","minLength":1,"maxLength":500}},"recipientRole":{"type":"string","maxLength":128}},"required":["prompt"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return failure("QUESTION_SCHEMA_INVALID", "A structured step question is required.")
        val caseId = context.caseEvents.map { it.caseId }.distinct().singleOrNull()
            ?: return failure("FACTORY_WORKER_BINDING_INVALID", "Factory worker invocation requires exactly one controlling case.")
        val agent = context.agentName
            ?: return failure("FACTORY_WORKER_BINDING_INVALID", "Factory worker invocation requires an agent identity.")
        val binding = bindings.acquire(caseId, context.namespaceId, agent)
            ?: return failure(
                "FACTORY_WORKER_BINDING_MISSING",
                "No active Factory attempt binding matches this case, namespace, and agent, or the binding is unavailable.",
            )
        val contextHash = contextHash(binding.attemptId, input)
        val question = buildMap<String, Any?> {
            put("prompt", input.prompt)
            put("type", input.type)
            if (input.options.isNotEmpty()) put("options", input.options)
            if (input.recipientRole != null) put("recipientRole", input.recipientRole)
            put("contextHash", contextHash)
        }
        val body = mapper.writeValueAsString(mapOf("attemptId" to binding.attemptId, "question" to question))
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/agent-step-questions")
                .header("Authorization", "Bearer ${binding.capabilityToken}")
                .header("X-AgentOS-Case-Id", caseId.toString())
                .header("X-AgentOS-Agent-Name", agent)
                .header("X-Idempotency-Key", contextHash)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { r ->
                    val root = runCatching { mapper.readTree(r.body?.string()) }.getOrNull()
                    val code = root?.path("error")?.path("code")?.asText("FACTORY_REQUEST_FAILED") ?: "FACTORY_REQUEST_FAILED"
                    // The result binding is always RELEASED, never consumed: a
                    // question is not the terminal result, and the resumed
                    // attempt N+1 must still be able to submit its PASS/FAIL.
                    bindings.release(binding)
                    when {
                        r.isSuccessful -> {
                            val data = root?.path("data")
                            val interactionId = data?.path("interactionId")?.asText()?.takeIf { it.isNotBlank() }
                                ?: return@use failure("FACTORY_RESPONSE_INVALID", "Factory accepted the question without an interactionId.")
                            val questionId = UUID.nameUUIDFromBytes("factory-question|$interactionId".toByteArray())
                            val workflowId = data.path("workflowId").asText().takeIf { it.isNotBlank() }
                                ?: return@use failure("FACTORY_RESPONSE_INVALID", "Factory accepted the question without a workflowId.")
                            val revision = data.path("revision").asLong(0L).takeIf { it > 0L }
                                ?: return@use failure("FACTORY_RESPONSE_INVALID", "Factory accepted the question without a revision.")
                            registerQuestion(questionId, FactoryCheckpointRef(workflowId, interactionId, revision))
                            val existing = context.caseEvents.filterIsInstance<QuestionEvent>()
                                .firstOrNull { it.id == questionId }
                            if (existing == null) {
                                val questionType = runCatching { QuestionType.valueOf(input.type) }.getOrDefault(QuestionType.FREE_TEXT)
                                context.emitEvent?.invoke(
                                    QuestionEvent(
                                        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(id = questionId),
                                        namespaceId = context.namespaceId,
                                        caseId = caseId,
                                        agentId = UUID.nameUUIDFromBytes(agent.toByteArray()),
                                        agentName = agent,
                                        question = input.prompt,
                                        options = input.options.takeIf { it.isNotEmpty() },
                                        questionType = questionType,
                                    ),
                                ) ?: return@use failure("FACTORY_MIRROR_UNAVAILABLE", "AgentOS cannot durably mirror the Factory question.")
                            }
                            ToolExecutionResult.success(mapper.writeValueAsString(data))
                        }

                        r.code >= 500 || code == "QUESTION_SCHEMA_INVALID" ->
                            failure(code, "Factory rejected the question: $code (HTTP ${r.code}). Correct the question arguments and retry.")

                        else ->
                            failure(code, "Factory rejected the question: $code (HTTP ${r.code}).")
                    }
                }
            }
        } catch (_: Exception) {
            bindings.release(binding)
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable; the question may be retried.")
        }
    }

    /**
     * Deterministic content hash of the question bound to the attempt
     * (`sha256:<hex>`). Computed by the tool from trusted identity + the
     * question content — never supplied by the model — so a retried call is an
     * idempotent re-ask collapsing onto the same durable interaction.
     */
    private fun contextHash(attemptId: String, input: Input): String {
        val canonical = mapper.writeValueAsString(
            linkedMapOf(
                "attemptId" to attemptId,
                "prompt" to input.prompt,
                "type" to input.type,
                "options" to input.options,
                "recipientRole" to input.recipientRole,
            ),
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun failure(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)
}
