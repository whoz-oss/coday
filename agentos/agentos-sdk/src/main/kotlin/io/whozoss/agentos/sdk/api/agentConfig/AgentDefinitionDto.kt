package io.whozoss.agentos.sdk.api.agentConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * HTTP response for `GET /api/agent-configs/{id}/definition`.
 *
 * Represents a fully-resolved agent definition: effective instructions (with namespace /
 * integration / user context injected), resolved model and provider, and the list of tools
 * that would be made available to the agent.
 *
 * Useful for debugging agent configurations without starting a case.
 */
@Schema(name = "AgentDefinition")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentDefinitionDto(
    @field:Schema(description = "The ID of the AgentConfig this definition was resolved from.")
    val agentConfigId: UUID,
    @field:Schema(description = "The agent's display name.")
    val name: String,
    @field:Schema(
        description = "Privileged namespace context block sent as a separate system message, " +
            "before instructions. Null when no namespace context is available.",
    )
    val systemPrompt: String?,
    @field:Schema(
        description = "Final system instructions sent to the LLM, including injected " +
            "namespace / integration / user context blocks.",
    )
    val instructions: String?,
    @field:Schema(description = "The actual API model name sent to the provider.")
    val resolvedModelApiName: String,
    @field:Schema(description = "The name of the resolved AI provider.")
    val resolvedProviderName: String,
    @field:Schema(description = "Summary of tools that would be made available to this agent.")
    val tools: List<ToolSummary>,
    @field:Schema(description = "Whether the agent uses the advanced multi-step execution engine.")
    val advancedExecution: Boolean,
    @field:Schema(description = "The namespace this agent belongs to.")
    val namespaceId: UUID,
    @field:Schema(description = "The user whose overlay was applied, or null for namespace-only resolution.")
    val userId: UUID?,
) {
    /**
     * Summary of a single tool available to the agent.
     */
    @Schema(name = "AgentDefinitionToolSummary")
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ToolSummary(
        val name: String,
        val description: String,
        val inputSchema: String,
    )
}
