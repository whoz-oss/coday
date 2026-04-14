package io.whozoss.agentos.auth

import io.whozoss.agentos.sdk.tool.StandardTool
import mu.KLogging
import org.springframework.stereotype.Service

@Service
class ToolExecutionGuard(
    private val authorizationService: AuthorizationService,
    private val auditService: PermissionAuditService,
) {
    companion object : KLogging()

    fun executeWithPermissionCheck(
        tool: StandardTool<*>,
        args: String?,
        callerId: String,
        caseId: String,
        namespaceId: String,
    ): GuardedToolResult {
        val category = tool.category
        val allowed = try {
            authorizationService.canExecuteTool(callerId, caseId, tool.name, category)
        } catch (e: Exception) {
            logger.error(e) { "Permission check failed for tool ${tool.name}" }
            false // fail-closed (NFR9)
        }

        return when {
            !allowed -> {
                auditService.logDenied(callerId, namespaceId, tool.name, caseId)
                GuardedToolResult.Denied(
                    toolName = tool.name,
                    reason = "Permission denied for tool '${tool.name}'",
                )
            }
            else -> {
                auditService.logGranted(callerId, namespaceId, tool.name, caseId)
                try {
                    val output = tool.executeWithJson(args)
                    GuardedToolResult.Success(tool.name, output)
                } catch (e: Exception) {
                    GuardedToolResult.Error(tool.name, e.message ?: "Unknown error")
                }
            }
        }
    }
}

sealed class GuardedToolResult {
    data class Success(val toolName: String, val output: String) : GuardedToolResult()
    data class Denied(val toolName: String, val reason: String) : GuardedToolResult()
    data class Error(val toolName: String, val error: String) : GuardedToolResult()
}
