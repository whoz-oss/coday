package io.whozoss.agentos.plugins.mcp

import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource
import io.modelcontextprotocol.spec.McpSchema.ResourceContents
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents

/**
 * Renders a [CallToolResult] as the plain text the model receives, for every MCP transport.
 *
 * - [TextContent]: its text.
 * - [EmbeddedResource] holding text: a one-line header (uri and mime type) followed by the text.
 * - [EmbeddedResource] holding a blob: a one-line summary, never the blob itself.
 * - anything else (image, audio...): a marker naming the content type.
 *
 * Parts are joined with a newline. No content at all gives [NO_OUTPUT].
 */
internal object McpResultFormatter {

    const val NO_OUTPUT = "(no output)"

    fun format(result: CallToolResult): String {
        val content = result.content() ?: return NO_OUTPUT
        val parts = content.map { item ->
            when (item) {
                is TextContent -> item.text()
                is EmbeddedResource -> formatResource(item.resource())
                else -> "[${item.type()}: unsupported content type]"
            }
        }
        return if (parts.isEmpty()) NO_OUTPUT else parts.joinToString("\n")
    }

    private fun formatResource(resource: ResourceContents?): String =
        when (resource) {
            is TextResourceContents ->
                "[resource ${resource.uri()}${mimeSuffix(resource.mimeType())}]\n${resource.text()}"
            is BlobResourceContents ->
                "[resource ${resource.uri()}: binary ${resource.mimeType() ?: "unknown type"}, " +
                    "${resource.blob()?.length ?: 0} bytes base64]"
            else -> "[resource ${resource?.uri() ?: "(unknown)"}: unsupported resource contents]"
        }

    private fun mimeSuffix(mimeType: String?): String = if (mimeType.isNullOrBlank()) "" else " ($mimeType)"
}
