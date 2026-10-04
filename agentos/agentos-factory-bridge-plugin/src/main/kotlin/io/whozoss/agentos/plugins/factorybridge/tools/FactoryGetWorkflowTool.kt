package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * Read the authoritative namespace-scoped state of a Factory workflow.
 *
 * Phase 6 (read-only Workstream Agent): an `existing` workflow is enriched
 * with the Factory-calculated `allowedActions`/`blockers` (authoritative
 * `/actions` read, returned verbatim — never recomputed by the tool), a
 * bounded secret-free `attempts` summary (`DurableAgentAttemptDto` fields
 * only) and a bounded, summarized `evidence` refs list (no free-text body).
 * The primary projection stays authoritative: a failing sub-read degrades to
 * an empty section instead of failing the whole read. Pure read — this tool
 * never mutates.
 */
class FactoryGetWorkflowTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) : StandardTool<FactoryGetWorkflowTool.Input> {
    data class Input(val workflowId: String)

    override val name = "FACTORY_WORKSTREAM__get_workflow"
    override val description =
        "Read the authoritative namespace-scoped state of a Factory workflow: current revision, steps, " +
            "Factory-calculated allowed actions and blockers, bounded attempts and summarized evidence. Read-only."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"}},"required":["workflowId"]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val workflowId = input?.workflowId
        if (workflowId == null || !SAFE_ID.matches(workflowId)) return failure("INVALID_WORKFLOW_ID", "workflowId is invalid.")
        val encoded = URLEncoder.encode(workflowId, Charsets.UTF_8).replace("+", "%20")
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$encoded?namespaceId=${context.namespaceId}")
                .get()
                .build()
        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { parseResponse(workflowId, it.code, it.body?.string()) }
            }
        } catch (_: SocketTimeoutException) {
            failure("FACTORY_TIMEOUT", "Factory did not respond before the timeout.")
        } catch (_: Exception) {
            failure("FACTORY_UNAVAILABLE", "Factory is unavailable.")
        }.let { primary ->
            if (primary.success) enrichExisting(encoded, context, primary) else primary
        }
    }

    /**
     * Merge the Factory-calculated `allowedActions`/`blockers`, a bounded
     * secret-free attempts summary and a bounded evidence-refs summary into an
     * `existing` read. Every sub-read degrades to an empty section on failure:
     * the primary projection stays authoritative.
     */
    private suspend fun enrichExisting(
        encoded: String,
        context: ToolContext,
        primary: ToolExecutionResult,
    ): ToolExecutionResult {
        val output =
            try {
                objectMapper.readTree(primary.output) as? ObjectNode
            } catch (_: Exception) {
                null
            } ?: return primary
        if (output.path("state").asText() != "existing") return primary
        val base = "${baseUrl.trimEnd('/')}/api/factory/workflows/$encoded"
        val query = "?namespaceId=${context.namespaceId}"

        val actions = fetchSubSection("$base/actions$query")?.path("data")
        output.set<JsonNode>("allowedActions", actions?.path("allowedActions")?.takeIf { it.isArray } ?: objectMapper.createArrayNode())
        output.set<JsonNode>("blockers", actions?.path("blockers")?.takeIf { it.isArray } ?: objectMapper.createArrayNode())

        val attempts = fetchSubSection("$base/attempts$query")?.path("data")?.takeIf { it.isArray }
        val boundedAttempts = attempts?.take(MAX_SUB_ITEMS)?.map { FactoryGetStepAttemptsTool.boundedAttempt(it) } ?: emptyList()
        output.set<JsonNode>("attempts", objectMapper.valueToTree(boundedAttempts))

        val evidenceData = fetchSubSection("$base/evidence$query")?.path("data")
        val evidenceItems = evidenceData?.path("items")?.takeIf { it.isArray } ?: evidenceData?.takeIf { it.isArray }
        val boundedEvidence =
            evidenceItems?.take(MAX_SUB_ITEMS)?.map { item ->
                mapOf(
                    "evidenceId" to (item.path("evidenceId").takeIf { it.isTextual }?.asText()
                        ?: item.path("id").takeIf { it.isTextual }?.asText()),
                    "stepId" to item.path("stepId").takeIf { it.isTextual }?.asText(),
                    "kind" to (item.path("kind").takeIf { it.isTextual }?.asText()
                        ?: item.path("type").takeIf { it.isTextual }?.asText()),
                    "createdAt" to item.path("createdAt").takeIf { it.isTextual }?.asText(),
                )
            } ?: emptyList()
        output.set<JsonNode>("evidence", objectMapper.valueToTree(boundedEvidence))

        return ToolExecutionResult.success(objectMapper.writeValueAsString(output), metadata = primary.metadata)
    }

    /** GET one sub-read; any transport/HTTP/JSON failure degrades to `null`. */
    private suspend fun fetchSubSection(url: String): JsonNode? =
        try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (response.code !in 200..299) {
                        null
                    } else {
                        try {
                            objectMapper.readTree(response.body?.string())
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }

    internal fun parseResponse(
        workflowId: String,
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
            val code = root.path("error").path("code").takeIf { it.isTextual }?.asText() ?: "FACTORY_REQUEST_FAILED"
            val message =
                root.path("error").path("message").takeIf { it.isTextual }?.asText() ?: "Factory rejected the lookup."
            return failure(code, message)
        }
        val data = root.path("data")
        val state = data.path("state").takeIf { it.isTextual }?.asText()
        if (data.path("workflowId").asText(null) != workflowId || state !in STATES) {
            return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        val output =
            if (state != "existing") {
                mapOf("state" to state, "workflowId" to workflowId)
            } else {
                val revision = data.path("revision").takeIf { it.isIntegralNumber }?.asLong()
                val projection = data.path("projection").takeIf { it.isObject }
                val workflowType = projection?.path("workflowType")?.takeIf { it.isTextual }?.asText()
                if (revision == null || projection == null || projection.path("workflowId").asText(null) != workflowId || workflowType == null) {
                    return failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
                }
                mapOf(
                    "state" to state,
                    "workflowId" to workflowId,
                    "revision" to revision,
                    "workflowType" to workflowType,
                    "status" to projection.path("status").asText(),
                    "projection" to projection,
                )
            }
        return ToolExecutionResult.success(objectMapper.writeValueAsString(output), metadata = output.filterKeys { it != "projection" })
    }

    private fun failure(
        code: String,
        message: String,
    ) = ToolExecutionResult.error(message, errorType = code, errorMessage = message)

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val STATES = setOf("absent", "existing", "removed", "purged")

        /** Cap applied to each merged sub-section (attempts, evidence). */
        const val MAX_SUB_ITEMS = 50
    }
}
