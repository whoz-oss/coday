package io.whozoss.agentos.sdk.tool

import java.nio.file.Path
import java.util.UUID

/**
 * Execution context for tools that require access to runtime information.
 *
 * This context is passed to ContextAwareTool implementations to provide:
 * - namespace and case identification
 * - file system roots for file operations
 * - additional properties (like readOnly mode)
 *
 * @property namespaceId The namespace (project/federation) this tool is executing in
 * @property caseId The case (thread/channel) this tool is executing in
 * @property fileRoots Map of scope names to file system roots (e.g., "project" -> "/workspace/myproject")
 * @property properties Additional string properties for tool configuration (e.g., "readOnly" -> "true")
 */
data class ToolExecutionContext(
    val namespaceId: UUID,
    val caseId: UUID,
    val fileRoots: Map<String, Path> = emptyMap(),
    val properties: Map<String, String> = emptyMap(),
) {
    companion object {
        /**
         * Returns an empty context with zeroed UUIDs and no roots/properties.
         * Used as fallback when ContextAwareTool is called via StandardTool.execute()
         * without context awareness.
         */
        fun empty() =
            ToolExecutionContext(
                namespaceId = UUID(0, 0),
                caseId = UUID(0, 0),
            )
    }
}
