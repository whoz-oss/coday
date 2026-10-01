package io.whozoss.agentos.tool

import com.fasterxml.jackson.databind.JsonNode
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Debug/test endpoints that expose the live integration-config tool surface for a namespace.
 *
 * **Endpoints**:
 * - `GET /api/tools` — lists all tools available in a namespace (and optional user overlay).
 *   Returns a [List] of [ToolSummary] sorted by name. Useful for populating a UI dropdown
 *   before invoking a specific tool.
 * - `POST /api/tools/invoke` — resolves a named tool and executes it with a caller-supplied
 *   JSON payload.
 *
 * **Security**: both endpoints require namespace WRITE (= namespace admin or super-admin) on
 * the supplied `namespaceId`. The listing endpoint reveals the namespace's full integration
 * surface and therefore must not be cheaper to obtain than the invocation itself — hence the
 * same `WRITE` gate. Super-admins retain access to all namespaces via the standard permission
 * bypass.
 *
 * Both endpoints bypass the normal agent-run permission model and operate with a synthetic
 * [io.whozoss.agentos.sdk.tool.ToolContext] (no case events, no credential provider, no agent
 * name). They are intended exclusively for development and troubleshooting.
 *
 * Business logic is delegated to [ToolInvokeService].
 */
@RestController
@RequestMapping("/api/tools", produces = [MediaType.APPLICATION_JSON_VALUE])
class ToolInvokeController(
    private val toolInvokeService: ToolInvokeService,
) {
    /**
     * GET /api/tools?namespaceId={uuid}&userId={uuid}
     *
     * Lists all tools available in the given namespace (and optional user overlay), using the
     * same four-layer integration-config resolution as a real agent run.
     *
     * The listing exists solely to serve the invoke screen and reveals the namespace's
     * integration surface, so it requires the same namespace WRITE gate as [invoke].
     *
     * @param namespaceId Required. Namespace used to resolve effective integration configs.
     * @param userId Optional. When provided, user-scoped overlay layers are included.
     * @return List of [ToolSummary] sorted by name.
     */
    @GetMapping
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun list(
        @RequestParam namespaceId: UUID,
        @RequestParam(required = false) userId: UUID?,
    ): List<ToolSummary> = toolInvokeService.listTools(namespaceId, userId)

    /**
     * POST /api/tools/invoke
     *
     * Resolves [ToolInvokeRequest.toolName] from the effective integration configs for
     * the given namespace (and optional user) and calls it with [ToolInvokeRequest.payload].
     *
     * Returns a [ToolInvokeResponse] with the raw result fields.
     *
     * Responds with 404 when no tool matching [ToolInvokeRequest.toolName] is found.
     * The error message intentionally includes the full list of available tool names for
     * the resolved namespace/user context — this enumeration is acceptable because the
     * endpoint requires namespace WRITE (admin rights) on the target namespace.
     */
    @PostMapping("/invoke", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#request.namespaceId, 'Namespace', 'WRITE')")
    suspend fun invoke(
        @RequestBody request: ToolInvokeRequest,
    ): ToolInvokeResponse {
        val result =
            toolInvokeService.invoke(
                namespaceId = request.namespaceId,
                userId = request.userId,
                toolName = request.toolName,
                payloadJson = request.payload,
            )
        return ToolInvokeResponse(
            toolName = request.toolName,
            output = result.output,
            success = result.success,
            metadata = result.metadata,
            errorType = result.errorType,
            errorMessage = result.errorMessage,
            structuredOutput = result.structuredOutput,
        )
    }

    companion object : KLogging()
}

/**
 * Summary of one tool available in a namespace. Mirrors [io.whozoss.agentos.sdk.api.agentConfig.AgentDefinitionDto.ToolSummary].
 *
 * [inputSchema] is the raw JSON-Schema string the tool declares; the UI uses it to
 * pre-fill and document the payload field.
 */
data class ToolSummary(
    val name: String,
    val description: String,
    val inputSchema: String,
)

/**
 * Request body for [ToolInvokeController.invoke].
 *
 * @param namespaceId Namespace used to resolve effective integration configs.
 * @param userId Optional user id forwarded to the
 *   [io.whozoss.agentos.sdk.tool.ToolContext]. When provided, user-scoped overlay
 *   layers are included in config resolution.
 * @param toolName Exact name of the tool to invoke (e.g. `"MY_FILES__listFiles"`).
 * @param payload Raw JSON string passed verbatim to
 *   [io.whozoss.agentos.sdk.tool.StandardTool.executeWithJson].
 *   Pass `null` or omit for tools that take no input.
 */
data class ToolInvokeRequest(
    val namespaceId: UUID,
    val userId: UUID? = null,
    val toolName: String,
    val payload: String? = null,
)

/**
 * Response body for [ToolInvokeController.invoke].
 *
 * Mirrors [io.whozoss.agentos.sdk.tool.ToolExecutionResult] without the `images` field
 * (binary content is not suitable for a debug JSON response).
 */
data class ToolInvokeResponse(
    val toolName: String,
    val output: String,
    val success: Boolean,
    val metadata: Map<String, Any?> = emptyMap(),
    val errorType: String? = null,
    val errorMessage: String? = null,
    val structuredOutput: JsonNode?,
)
