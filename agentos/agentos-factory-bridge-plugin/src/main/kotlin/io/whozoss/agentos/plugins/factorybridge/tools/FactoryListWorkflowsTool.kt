package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Phase 6 read-only Workstream Agent tool: bounded, paginated list of the
 * workflow projections visible in the caller's trusted namespace.
 *
 * Pure read of `GET /api/factory/workflows` — the tool never recomputes state
 * and never mutates. `namespaceId` is always injected from the trusted
 * [ToolContext]; the model only supplies bounded filters (`state`,
 * `workflowType`, `limit`, `cursor`).
 *
 * Pagination is mandatory: `limit` defaults to [DEFAULT_LIMIT] and is coerced
 * into `[1, MAX_LIMIT]`; `cursor` is an opaque integer-offset token. Because
 * the Factory applies its own bound server-side, the tool requests
 * `offset + limit` (capped at [MAX_LIMIT]) and drops the offset client-side,
 * emitting `nextCursor` only while more items remain. The `purged` lifecycle is
 * not list-exposed by the Factory: `purged` and `all` map to the supported
 * bounded `active` view.
 */
class FactoryListWorkflowsTool(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) : StandardTool<FactoryListWorkflowsTool.Input> {
    data class Input(
        val state: String? = null,
        val workflowType: String? = null,
        val limit: Int? = null,
        val cursor: String? = null,
    )

    override val name = "FACTORY__list_workflows"
    override val description =
        "List the Factory workflow projections of the trusted namespace with bounded filters " +
            "(state, workflowType) and mandatory pagination (limit/cursor). Read-only."
    override val version = "1.0.0"
    override val paramType = Input::class.java
    override val inputSchema =
        """{"type":"object","additionalProperties":false,"properties":""" +
            """{"state":{"type":"string","enum":["active","removed","purged","all"]},""" +
            """"workflowType":{"type":"string","maxLength":128},""" +
            """"limit":{"type":"integer","minimum":1,"maximum":200},""" +
            """"cursor":{"type":"string","maxLength":256}},"required":[]}"""

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val state = input?.state?.takeIf { it.isNotBlank() } ?: "active"
        if (state !in STATES) return FactoryReadSupport.failure("INVALID_REQUEST", "state must be active, removed, purged or all.")
        val workflowType = input?.workflowType?.takeIf { it.isNotBlank() }
        if (workflowType != null && !FactoryReadSupport.WORKFLOW_ID.matches(workflowType)) {
            return FactoryReadSupport.failure("INVALID_REQUEST", "workflowType is invalid.")
        }
        val limit = (input?.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset =
            input?.cursor?.takeIf { it.isNotBlank() }?.let { raw ->
                if (raw.length > MAX_CURSOR_LENGTH) {
                    return FactoryReadSupport.failure("INVALID_REQUEST", "cursor is invalid.")
                }
                raw.toIntOrNull()?.takeIf { it >= 0 }
                    ?: return FactoryReadSupport.failure("INVALID_REQUEST", "cursor is invalid.")
            } ?: 0
        // The `purged` lifecycle is not list-exposed: `purged`/`all` read the
        // supported bounded `active` view.
        val serverState = if (state == "removed") "removed" else "active"
        val serverLimit = (offset + limit).coerceIn(1, MAX_LIMIT)
        val query =
            buildString {
                append("namespaceId=").append(context.namespaceId)
                append("&state=").append(serverState)
                append("&limit=").append(serverLimit)
                if (workflowType != null) append("&workflowType=").append(FactoryReadSupport.encodeQueryValue(workflowType))
            }
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows?$query")
                .get()
                .build()
        return FactoryReadSupport.executeGet(httpClient, request) { status, body -> parseResponse(status, body, offset, limit) }
    }

    internal fun parseResponse(
        status: Int,
        body: String?,
        offset: Int,
        limit: Int,
    ): ToolExecutionResult {
        if (status !in 200..299) {
            return FactoryReadSupport.errorResult(objectMapper, body, "Factory rejected the workflow list read.")
        }
        val root =
            FactoryReadSupport.parseJson(objectMapper, body)
                ?: return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        val data = root.path("data")
        val rawItems = data.path("items")
        if (!rawItems.isArray) {
            return FactoryReadSupport.failure("MALFORMED_FACTORY_RESPONSE", "Factory returned an invalid response.")
        }
        val serverTruncated = data.path("truncated").asBoolean(false)
        val window = rawItems.drop(offset).take(limit)
        val items =
            window.map { item ->
                val projection = item.path("projection")
                mapOf(
                    "workflowId" to item.path("workflowId").asText(),
                    "workflowType" to projection.path("workflowType").takeIf { it.isTextual }?.asText(),
                    "title" to projection.path("title").takeIf { it.isTextual }?.asText(),
                    "status" to projection.path("status").takeIf { it.isTextual }?.asText(),
                    "revision" to item.path("revision").takeIf(JsonNode::isIntegralNumber)?.asLong(),
                )
            }
        val hasMore = rawItems.size() > offset + items.size || serverTruncated
        val nextCursor = if (hasMore && items.isNotEmpty()) (offset + items.size).toString() else null
        val output = mapOf("items" to items, "nextCursor" to nextCursor)
        return ToolExecutionResult.success(
            objectMapper.writeValueAsString(output),
            metadata = mapOf("count" to items.size, "nextCursor" to nextCursor),
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
        const val MAX_CURSOR_LENGTH = 256
        val STATES = setOf("active", "removed", "purged", "all")
    }
}
