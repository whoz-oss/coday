package io.whozoss.agentos.tool

import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Debug/test endpoint that resolves a named tool from the live integration-config overlay
 * and invokes it with a caller-supplied JSON payload.
 *
 * **Security**: restricted to SUPER_ADMIN — this endpoint bypasses the normal agent-run
 * permission model and executes tools with a synthetic
 * [io.whozoss.agentos.sdk.tool.ToolContext]. It is intended exclusively for development
 * and troubleshooting.
 *
 * Business logic is delegated to [ToolInvokeService].
 */
@RestController
@RequestMapping("/api/tools", produces = [MediaType.APPLICATION_JSON_VALUE])
class ToolInvokeController(
    private val toolInvokeService: ToolInvokeService,
) {
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
     * endpoint is restricted to SUPER_ADMIN callers.
     */
    @PostMapping("/invoke", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    suspend fun invoke(
        @RequestBody request: ToolInvokeRequest,
    ): ToolInvokeResponse {
        val result = toolInvokeService.invoke(
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
        )
    }

    companion object : KLogging()
}

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
)
