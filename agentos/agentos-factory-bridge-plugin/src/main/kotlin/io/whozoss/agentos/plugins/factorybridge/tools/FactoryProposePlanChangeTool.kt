package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * Phase 7 command tool: submit an append-only plan-change proposal to the Factory.
 *
 * The agent only *proposes* — the Factory validates, classifies (Rule 1–3) and gates
 * the change; the proposal store is append-only and the agent never applies a plan
 * change directly. `namespaceId` is injected from the trusted [ToolContext] (never
 * exposed in the schema) and the execution identity travels via `x-factory-*` trust
 * headers; the controller fails closed when the trust context is incomplete.
 */
@Deprecated(
    "Not exposed under the Workstream/Worker trust boundary; see docs/factory-trust-boundary-migration.md",
    level = DeprecationLevel.WARNING,
)
class FactoryProposePlanChangeTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val runtimeId: String,
) : StandardTool<FactoryProposePlanChangeTool.Input> {
    /** One proposed dependency edge change (op + the two existing step ids). */
    data class DependencyChange(
        val op: String,
        val fromStepId: String,
        val toStepId: String,
    )

    /** One proposed scope change (op + target + optional detail). */
    data class ScopeChange(
        val op: String,
        val target: String,
        val detail: String? = null,
    )

    data class Input(
        val workflowId: String,
        val expectedRevision: Long,
        val reasonCode: String,
        val summary: String,
        val proposalType: String,
        val affectedStepIds: List<String>? = null,
        val proposedDependencyChanges: List<DependencyChange>? = null,
        val proposedScopeChanges: List<ScopeChange>? = null,
        val evidenceRefs: List<String>? = null,
        val idempotencyKey: String,
    )

    override val name = "FACTORY__propose_plan_change"
    override val description =
        "Submit an append-only plan-change proposal for a governed workflow. The Factory validates, classifies " +
            "and gates the proposal — the agent never applies a plan change itself."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128},"expectedRevision":{"type":"integer","minimum":1},"reasonCode":{"type":"string","maxLength":64},"summary":{"type":"string","minLength":1,"maxLength":2000},"proposalType":{"enum":["RETRY","PATH_SELECTION","OPTIONAL_STEP","DEPENDENCY","SCOPE","NEW_STEP","CONTRACT_OR_ORACLE"]},"affectedStepIds":{"type":"array","maxItems":100,"uniqueItems":true,"items":{"type":"string","maxLength":128}},"proposedDependencyChanges":{"type":"array","maxItems":50,"items":{"type":"object","additionalProperties":false,"properties":{"op":{"enum":["ADD","REMOVE"]},"fromStepId":{"type":"string","maxLength":128},"toStepId":{"type":"string","maxLength":128}},"required":["op","fromStepId","toStepId"]}},"proposedScopeChanges":{"type":"array","maxItems":50,"items":{"type":"object","additionalProperties":false,"properties":{"op":{"enum":["EXPAND","REDUCE","MODIFY"]},"target":{"type":"string","maxLength":128},"detail":{"type":"string","maxLength":2000}},"required":["op","target"]}},"evidenceRefs":{"type":"array","maxItems":100,"items":{"type":"string","maxLength":512}},"idempotencyKey":{"type":"string","minLength":1,"maxLength":128}},"required":["workflowId","expectedRevision","reasonCode","summary","proposalType","idempotencyKey"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return fail("INVALID_PLAN_CHANGE_PROPOSAL", "A complete plan-change proposal is required.")
        val caseIds = context.caseEvents.map { it.caseId }.distinct()
        if (caseIds.size != 1) return fail("CASE_CONTEXT_UNAVAILABLE", "A single controlling case is required.")
        val agent =
            context.agentName?.takeIf { it.isNotBlank() }
                ?: return fail("AGENT_CONTEXT_UNAVAILABLE", "A controlling agent is required.")
        val actor =
            context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
                ?: return fail("USER_CONTEXT_UNAVAILABLE", "A controlling actor is required.")
        // SubmitPlanChangeRequest wire shape: namespaceId injected from the trusted
        // context, content fields authored by the model — the controller rejects any
        // other key with INVALID_PLAN_CHANGE_PROPOSAL.
        val payload =
            linkedMapOf<String, Any?>(
                "workflowId" to input.workflowId,
                "namespaceId" to context.namespaceId.toString(),
                "expectedRevision" to input.expectedRevision,
                "reasonCode" to input.reasonCode,
                "summary" to input.summary,
                "proposalType" to input.proposalType,
                "idempotencyKey" to input.idempotencyKey,
            )
        input.affectedStepIds?.let { payload["affectedStepIds"] = it }
        input.proposedDependencyChanges?.let { payload["proposedDependencyChanges"] = it }
        input.proposedScopeChanges?.let { payload["proposedScopeChanges"] = it }
        input.evidenceRefs?.let { payload["evidenceRefs"] = it }
        val body = objectMapper.writeValueAsString(payload)
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/plan-change-proposals")
                .header("x-factory-namespace-id", context.namespaceId.toString())
                .header("x-factory-runtime-id", runtimeId)
                .header("x-factory-agent-id", agent)
                .header("x-factory-case-id", caseIds.single().toString())
                .header("x-factory-actor-id", actor)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val root =
                        try {
                            objectMapper.readTree(response.body?.string())
                        } catch (_: Exception) {
                            return@use fail("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
                        }
                    if (!response.isSuccessful) {
                        return@use fail(
                            root.path("error").path("code").asText("FACTORY_REQUEST_FAILED"),
                            root.path("error").path("message").asText("Factory rejected the plan-change proposal."),
                        )
                    }
                    val data = root.path("data")
                    val proposalId = data.path("proposalId").takeIf { it.isTextual }?.asText()
                    val revision = data.path("revision").takeIf { it.isIntegralNumber }?.asLong()
                    val proposalStatus = data.path("status").takeIf { it.isTextual }?.asText()
                    val recommendedVerdict = data.path("recommendedVerdict").takeIf { it.isTextual }?.asText()
                    if (proposalId == null || revision == null || proposalStatus == null) {
                        return@use fail("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
                    }
                    val status =
                        when (proposalStatus) {
                            "AUTO_APPLIED" -> "accepted"
                            "REJECTED" -> "rejected"
                            else -> "pending-human" // PENDING_VALIDATION / GATE_REQUIRED / REQUIRES_NEW_DEFINITION
                        }
                    val allowedActions = fetchAllowedActions(input.workflowId, context)
                    val message =
                        when (status) {
                            "accepted" -> "Plan-change proposal $proposalId auto-applied."
                            "rejected" -> "Plan-change proposal $proposalId rejected."
                            else -> "Plan-change proposal $proposalId recorded ($proposalStatus); awaiting governance decision."
                        }
                    val output =
                        linkedMapOf<String, Any?>(
                            "status" to status,
                            "revision" to revision,
                            "reasonCode" to (recommendedVerdict ?: proposalStatus),
                            "interactionId" to null,
                            "proposalId" to proposalId,
                            "allowedActions" to allowedActions,
                            "message" to message,
                            "workflowId" to data.path("workflowId").asText(input.workflowId),
                            "proposalStatus" to proposalStatus,
                            "idempotent" to data.path("idempotent").asBoolean(false),
                        )
                    ToolExecutionResult.success(objectMapper.writeValueAsString(output))
                }
            }
        } catch (_: SocketTimeoutException) {
            fail("FACTORY_TIMEOUT", "Factory call timed out.")
        } catch (_: Exception) {
            fail("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }
    }

    /** Best-effort enrichment: a failed actions read never fails the command. */
    private fun fetchAllowedActions(
        workflowId: String,
        context: ToolContext,
    ): JsonNode =
        try {
            val url =
                "${baseUrl.trimEnd('/')}/api/factory/workflows/${encode(workflowId)}/actions" +
                    "?namespaceId=${encode(context.namespaceId.toString())}"
            httpClient
                .newCall(Request.Builder().url(url).get().build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return@use objectMapper.createArrayNode()
                    val root = objectMapper.readTree(response.body?.string())
                    root.path("data").path("allowedActions").takeIf { it.isArray }
                        ?: objectMapper.createArrayNode()
                }
        } catch (_: Exception) {
            objectMapper.createArrayNode()
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun fail(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)
}
