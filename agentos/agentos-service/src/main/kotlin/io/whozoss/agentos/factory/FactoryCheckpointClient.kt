package io.whozoss.agentos.factory

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.caseEvent.FactoryCheckpointRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Minimal HTTP wrapper for submitting a human decision to the Factory interaction-reply
 * endpoint before [io.whozoss.agentos.sdk.caseEvent.AnswerEvent] is persisted.
 *
 * This client is intentionally free of Spring annotations so it can be constructed
 * directly in [io.whozoss.agentos.caseFlow.CaseServiceImpl.buildRuntime] and injected
 * into [io.whozoss.agentos.caseFlow.CaseRuntime] as a nullable constructor parameter.
 *
 * ## Trust model
 *
 * - [caseId] and [actorId] come from [io.whozoss.agentos.sdk.tool.ToolContext] and the
 *   authenticated HTTP session — never from model-authored input.
 * - [ref] fields ([FactoryCheckpointRef.workflowId], [FactoryCheckpointRef.interactionId],
 *   [FactoryCheckpointRef.interactionRevision]) are set from the Factory response body
 *   when the checkpoint was opened — server-authoritative, not model-authored.
 * - [decision] is the user's free-text answer, verbatim.
 *
 * ## Result semantics
 *
 * Returns [Result.success] on any 2xx response from the Factory.
 * Returns [Result.failure] with a descriptive [FactoryCheckpointException] on:
 * - 4xx (Factory rejected: revision conflict, closed interaction, authorization failure)
 * - 5xx (Factory server error)
 * - network / timeout error
 * - malformed response body
 *
 * The caller ([CaseRuntime.addUserMessage]) must NOT persist [AnswerEvent] on failure.
 */
open class FactoryCheckpointClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Submit a human decision to the Factory.
     *
     * @param ref The checkpoint reference embedded in the [io.whozoss.agentos.sdk.caseEvent.QuestionEvent].
     * @param decision The user's answer text ("approve" / "reject" or any free-text).
     * @param caseId The AgentOS case id — used as the controlling execution reference.
     * @param actorId The authenticated user id who submitted the answer.
     */
    open suspend fun submitDecision(
        ref: FactoryCheckpointRef,
        decision: String,
        caseId: String,
        actorId: String,
    ): Result<Unit> {
        val workflowId = URLEncoder.encode(ref.workflowId, Charsets.UTF_8).replace("+", "%20")
        val interactionId = URLEncoder.encode(ref.interactionId, Charsets.UTF_8).replace("+", "%20")
        val url = "${baseUrl.trimEnd('/')}/api/factory/workflows/$workflowId/interactions/$interactionId/reply"
        val body = objectMapper.writeValueAsString(
            mapOf(
                "interactionRevision" to ref.interactionRevision,
                "decision" to decision,
            ),
        )
        val request = Request.Builder()
            .url(url)
            .header("x-factory-case-id", caseId)
            .header("x-factory-actor-id", actorId)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (response.isSuccessful) {
                        logger.info { "Factory checkpoint accepted: workflow=${ref.workflowId} interaction=${ref.interactionId} decision=$decision" }
                        Result.success(Unit)
                    } else {
                        val errorCode = runCatching {
                            objectMapper.readTree(responseBody)?.path("error")?.path("code")?.asText()
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "FACTORY_REJECTED"
                        val errorMessage = runCatching {
                            objectMapper.readTree(responseBody)?.path("error")?.path("message")?.asText()
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Factory rejected the decision (HTTP ${response.code})"
                        logger.warn { "Factory checkpoint rejected: workflow=${ref.workflowId} interaction=${ref.interactionId} code=$errorCode message=$errorMessage" }
                        Result.failure(FactoryCheckpointException(errorCode, errorMessage))
                    }
                }
            }
        } catch (e: Exception) {
            if (e is FactoryCheckpointException) return Result.failure(e)
            logger.warn(e) { "Factory checkpoint call failed: workflow=${ref.workflowId} interaction=${ref.interactionId}" }
            Result.failure(FactoryCheckpointException("FACTORY_UNAVAILABLE", "Factory is unavailable: ${e.message}"))
        }
    }

    companion object : KLogging()
}

/**
 * Thrown (wrapped in [Result.failure]) when the Factory rejects a decision or is unreachable.
 *
 * @param code Machine-readable error code from the Factory response, or a synthetic code
 *   such as `FACTORY_UNAVAILABLE` when the request never reached the Factory.
 * @param message Human-readable explanation surfaced to the user via [WarnEvent].
 */
class FactoryCheckpointException(
    val code: String,
    override val message: String,
) : RuntimeException(message)
