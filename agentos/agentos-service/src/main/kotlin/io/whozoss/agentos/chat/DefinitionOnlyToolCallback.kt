package io.whozoss.agentos.chat

import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * A tool the model is told about but that can never run: [call] throws.
 *
 * Used to send the exact tool definitions of an earlier request again (so that the request keeps the same
 * prefix and hits the provider's prompt cache) on a call that must not change anything. It is a last line
 * of defense: such a call also disables tool execution itself ([ChatClientProvider.getChatClient] `runTools`).
 */
class DefinitionOnlyToolCallback(
    private val definition: ToolDefinition,
) : ToolCallback {
    override fun getToolDefinition(): ToolDefinition = definition

    override fun call(toolInput: String): String =
        throw IllegalStateException("Tool ${definition.name()} is declared only and must not run")
}
